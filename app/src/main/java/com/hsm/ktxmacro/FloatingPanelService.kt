package com.hsm.ktxmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.app.NotificationCompat
import java.io.File
import kotlinx.coroutines.*

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var tvStatus: TextView
    private var tvLog: TextView? = null
    private lateinit var panelWMParams: WindowManager.LayoutParams
    private var panelExpanded = false
    private var macroEngine: MacroEngine? = null

    private val logBuffer = ArrayDeque<String>()

    // 버튼 반짝임
    private var blinkHandler: Handler? = null
    private var blinkRunnable: Runnable? = null
    private var blinkTarget: Button? = null
    private var blinkOn = false

    // 알람
    private var mediaPlayer: MediaPlayer? = null

    // 화면 캡처 관련
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureScope: CoroutineScope? = null
    @Volatile private var screenBitmap: Bitmap? = null
    var captureSnapshot: Bitmap? = null

    private val templates = mutableMapOf<String, Bitmap>()
    private val textTargets = mutableMapOf<String, String>()  // b2~b5 글자 인식 텍스트
    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    companion object {
        const val CHANNEL_ID = "ktx_macro"
        const val ACTION_SHOW_PANEL = "com.hsm.ktxmacro.SHOW_PANEL"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        var instance: FloatingPanelService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // 일단 기본 알림으로 포그라운드 시작 (MediaProjection 타입은 토큰 받은 후 재시작)
        startForeground(1, buildNotification())
        inflatePanel()
        loadTemplates()
        loadTextTargets()
        setupMacroEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_PANEL -> {
                if (::panelView.isInitialized) panelView.visibility = View.VISIBLE
            }
            else -> {
                // RESULT_OK = -1 이므로 기본값을 Int.MIN_VALUE로 구분
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode != Int.MIN_VALUE && data != null) {
                    initScreenCapture(resultCode, data)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        macroEngine?.stop()
        stopBlink()
        stopAlarm()
        captureScope?.cancel()
        captureScope = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val mp = mediaProjection
        mediaProjection = null   // 콜백 재진입 방지
        mp?.stop()
        recognizer.close()
        if (::panelView.isInitialized) windowManager.removeView(panelView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 화면 캡처 초기화 ─────────────────────────────────────────
    private fun initScreenCapture(resultCode: Int, data: Intent) {
        // 이전 캡처 리소스 정리 (재호출 시 누수 방지)
        captureScope?.cancel()
        captureScope = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        screenBitmap = null
        // mediaProjection?.stop() 호출 시 onStop 콜백이 재진입할 수 있어 직접 해제만
        mediaProjection = null

        try {
            setStatus("캡처 초기화 중...")
            val (w, h) = getScreenSize()
            val dpi = resources.displayMetrics.densityDpi

            // MediaProjection 타입으로 foreground 재선언 (getMediaProjection 전에 필수)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, buildNotification())
            }

            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            // Android 14 필수: createVirtualDisplay 전에 콜백 등록
            // onStop 시 서비스를 종료하지 않고 상태만 알림 (패널 유지)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    captureScope?.cancel()
                    captureScope = null
                    virtualDisplay?.release()
                    virtualDisplay = null
                    imageReader?.close()
                    imageReader = null
                    screenBitmap = null
                    mediaProjection = null
                    setStatus("화면 캡처 중단 → 앱 열어 재허용")
                }
            }, Handler(Looper.getMainLooper()))

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "KtxCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            captureScope!!.launch {
                while (isActive) {
                    try {
                        captureScreen()?.let { screenBitmap = it }
                    } catch (_: Throwable) {}
                    delay(100)
                }
            }
            setStatus("준비 완료")
        } catch (e: Throwable) {
            setStatus("캡처 오류: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── 화면 캡처 ────────────────────────────────────────────────
    fun captureScreen(): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * image.width
                val bmp = Bitmap.createBitmap(
                    image.width + rowPadding / plane.pixelStride,
                    image.height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            } finally {
                image.close()
            }
        } catch (_: Throwable) { null }
    }

    // ── 패널 생성 ────────────────────────────────────────────────
    private fun inflatePanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)
        tvStatus = panelView.findViewById(R.id.tv_status)
        tvLog = panelView.findViewById(R.id.tv_log)

        val pos = loadPanelPosition()
        panelWMParams = WindowManager.LayoutParams(
            dpToPx(130),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0  // 드래그 시 시스템 이동 애니메이션 제거
            x = pos.first; y = pos.second
        }

        windowManager.addView(panelView, panelWMParams)
        makeDraggable()
        setupButtons()
    }

    private fun makeDraggable() {
        val header = panelView.findViewById<TextView>(R.id.tv_header)
        var startX = 0f; var startY = 0f; var initX = 0; var initY = 0
        header.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    startX = event.rawX; startY = event.rawY
                    initX = panelWMParams.x; initY = panelWMParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, sh) = getScreenSize()
                    val newX = initX + (event.rawX - startX).toInt()
                    val newY = initY + (event.rawY - startY).toInt()
                    panelWMParams.x = newX.coerceIn(0, (sw - panelView.width).coerceAtLeast(0))
                    panelWMParams.y = newY.coerceIn(0, (sh - panelView.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(panelView, panelWMParams)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelPosition(panelWMParams.x, panelWMParams.y)
                    false
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        panelExpanded = !panelExpanded
        panelView.findViewById<View>(R.id.ll_expandable).visibility =
            if (panelExpanded) View.VISIBLE else View.GONE
        panelView.findViewById<Button>(R.id.btn_toggle).text =
            if (panelExpanded) "▲" else "▼"
        panelWMParams.width = dpToPx(if (panelExpanded) 220 else 130)
        windowManager.updateViewLayout(panelView, panelWMParams)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    // 오버레이 패널 위치를 검은 사각형으로 덮어 OCR 오인식 방지
    private fun maskPanelArea(original: Bitmap): Bitmap {
        if (!::panelWMParams.isInitialized || !::panelView.isInitialized) return original
        if (panelView.visibility != View.VISIBLE) return original
        val pw = panelView.width; val ph = panelView.height
        if (pw <= 0 || ph <= 0) return original
        val masked = original.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(masked).drawRect(
            panelWMParams.x.toFloat(),
            panelWMParams.y.toFloat(),
            (panelWMParams.x + pw).toFloat(),
            (panelWMParams.y + ph).toFloat(),
            Paint().apply { color = Color.BLACK }
        )
        return masked
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun savePanelPosition(x: Int, y: Int) {
        getSharedPreferences("panel_prefs", MODE_PRIVATE).edit()
            .putInt("panel_x", x).putInt("panel_y", y).apply()
    }

    private fun loadPanelPosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences("panel_prefs", MODE_PRIVATE)
        return Pair(prefs.getInt("panel_x", 0), prefs.getInt("panel_y", 200))
    }

    private fun setupButtons() {
        panelView.findViewById<Button>(R.id.btn_seat).setOnClickListener { startMacro("seat") }
        panelView.findViewById<Button>(R.id.btn_standing).setOnClickListener { startMacro("standing") }
        panelView.findViewById<Button>(R.id.btn_stop).setOnClickListener {
            macroEngine?.stop()
            stopBlink()
            stopAlarm()
        }

        panelView.findViewById<Button>(R.id.btn_toggle).setOnClickListener { togglePanel() }

        panelView.findViewById<Button>(R.id.btn_capture_perm).setOnClickListener {
            startActivity(Intent(this, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        panelView.findViewById<Button>(R.id.btn_minimize).setOnClickListener {
            panelView.visibility = View.GONE
            updateNotification("패널 숨김 - 여기를 탭하면 다시 표시됩니다")
        }

        panelView.findViewById<Button>(R.id.btn_exit).setOnClickListener {
            macroEngine?.stop()
            stopSelf()
        }

        // b1: 이미지 캡처
        panelView.findViewById<View>(R.id.btn_b1)?.setOnClickListener { startRegionCapture("b1") }
        // b2~b12: 글자 인식 텍스트 설정
        listOf("b2","b3","b4","b5","b6","b7","b8","b9","b10","b11","b12").forEach { prefix ->
            val id = resources.getIdentifier("btn_$prefix", "id", packageName)
            panelView.findViewById<View>(id)?.setOnClickListener { showTextInputDialog(prefix) }
        }
    }

    var isRegionSelectOpen = false

    private fun startMacro(mode: String) {
        if (macroEngine == null) { setStatus("매크로 엔진 초기화 안됨"); return }
        if (!MacroAccessibilityService.isRunning() && !MacroAccessibilityService.isEnabledInSettings(this)) {
            setStatus("접근성 서비스를 먼저 활성화하세요"); return
        }
        if (isRegionSelectOpen) return
        if (screenBitmap == null && mediaProjection == null) {
            setStatus("화면 캡처 없음 → ▼ 열고 '📷 화면 캡처 허용' 누르세요")
            return
        }
        // 기존 매크로 정지 후 새로 시작
        macroEngine?.stop()
        stopBlink()
        tryLaunchMacro(mode, 30)
    }

    private fun tryLaunchMacro(mode: String, remaining: Int) {
        val snap = try { screenBitmap ?: captureScreen() } catch (_: Throwable) { null }
        if (snap != null) {
            captureSnapshot = snap
            isRegionSelectOpen = true
            try {
                startActivity(Intent(this, RegionSelectActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("mode", mode)
                })
            } catch (e: Throwable) {
                isRegionSelectOpen = false
                setStatus("범위 선택 오류: ${e.javaClass.simpleName}")
            }
        } else if (remaining > 0) {
            setStatus("화면 준비 중... ($remaining)")
            Handler(Looper.getMainLooper()).postDelayed({ tryLaunchMacro(mode, remaining - 1) }, 100)
        } else {
            setStatus("화면 캡처 없음 → ▼ 열고 '📷 화면 캡처 허용' 누르세요")
        }
    }

    fun onRegionSelected(mode: String, region: Rect) {
        isRegionSelectOpen = false
        setStatus("매크로 시작: $mode")
        macroEngine?.start(mode, region, templates, textTargets)
        val btnId = if (mode == "seat") R.id.btn_seat else R.id.btn_standing
        Handler(Looper.getMainLooper()).post {
            panelView.findViewById<Button>(btnId)?.let { startBlink(it) }
        }
    }

    private fun startRegionCapture(prefix: String) {
        val snap = captureScreen() ?: screenBitmap
        if (snap != null) {
            captureSnapshot = snap
            launchRegionSelect(prefix)
        } else {
            setStatus("[$prefix] 캡처 준비 중...")
            retryCapture(prefix, 30)
        }
    }

    private fun retryCapture(prefix: String, remaining: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            val snap = captureScreen() ?: screenBitmap
            when {
                snap != null -> { captureSnapshot = snap; launchRegionSelect(prefix) }
                remaining > 0 -> retryCapture(prefix, remaining - 1)
                else -> setStatus("화면 캡처 실패. 앱을 재시작해주세요")
            }
        }, 100)
    }

    private fun launchRegionSelect(prefix: String) {
        startActivity(Intent(this, RegionSelectActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("capture_prefix", prefix)
        })
    }

    fun onImageCaptured(prefix: String, bitmap: Bitmap) {
        templates[prefix] = bitmap
        val file = File(filesDir, "$prefix.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        updateBtnPreview(prefix, bitmap)
        setStatus("$prefix 이미지 저장됨")
    }

    private fun loadTemplates() {
        val file = File(filesDir, "b1.png")
        if (file.exists()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            templates["b1"] = bmp
            updateBtnPreview("b1", bmp)
        }
    }

    private fun updateBtnPreview(prefix: String, bitmap: Bitmap?) {
        val imgId = resources.getIdentifier("img_$prefix", "id", packageName)
        val lblId = resources.getIdentifier("lbl_$prefix", "id", packageName)
        if (imgId == 0 || lblId == 0) return
        Handler(Looper.getMainLooper()).post {
            val imgView = panelView.findViewById<ImageView>(imgId)
            val lblView = panelView.findViewById<TextView>(lblId)
            if (bitmap != null) {
                imgView?.setImageBitmap(bitmap)
                imgView?.visibility = View.VISIBLE
                lblView?.text = "✓$prefix"
                lblView?.setTextColor(Color.WHITE)
            } else {
                imgView?.visibility = View.GONE
                lblView?.text = prefix
                lblView?.setTextColor(Color.parseColor("#64ffda"))
            }
        }
    }

    private fun setupMacroEngine() {
        macroEngine = MacroEngine(
            captureScreen = { screenBitmap },
            findText = { text, region -> findTextOnScreen(text, region) },
            onStatus = { msg -> setStatus(msg) },
            onAlarm = { startAlarm() },
            onAlarmStop = { stopAlarm() }
        )
    }

    // ML Kit로 현재 화면에서 텍스트 위치 탐색 (region=null이면 전체화면)
    suspend fun findTextOnScreen(text: String, region: Rect? = null): Point? {
        if (text.isBlank()) return null
        val full = screenBitmap ?: return null

        // 오버레이 패널 영역을 검게 마스킹해서 패널 내 텍스트 오인식 방지
        val src = maskPanelArea(full)

        val bitmap: Bitmap
        val offsetX: Int; val offsetY: Int
        if (region != null) {
            val l = region.left.coerceIn(0, src.width - 1)
            val t = region.top.coerceIn(0, src.height - 1)
            val w = region.width().coerceAtMost(src.width - l).coerceAtLeast(1)
            val h = region.height().coerceAtMost(src.height - t).coerceAtLeast(1)
            bitmap = Bitmap.createBitmap(src, l, t, w, h)
            offsetX = l; offsetY = t
        } else {
            bitmap = src; offsetX = 0; offsetY = 0
        }

        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    var found: Point? = null
                    outer@ for (block in result.textBlocks) {
                        for (line in block.lines) {
                            if (line.text.contains(text)) {
                                found = line.boundingBox?.let {
                                    Point(it.centerX() + offsetX, it.centerY() + offsetY)
                                }
                                break@outer
                            }
                        }
                    }
                    cont.resume(found)
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    // ── 버튼 반짝임 ───────────────────────────────────────────────
    private fun startBlink(btn: Button) {
        stopBlink()
        blinkTarget = btn
        blinkOn = false
        val h = Handler(Looper.getMainLooper())
        blinkHandler = h
        val originalTint = "#1565c0"
        val highlightTint = "#ff8f00"
        blinkRunnable = object : Runnable {
            override fun run() {
                blinkOn = !blinkOn
                btn.backgroundTintList = ColorStateList.valueOf(
                    Color.parseColor(if (blinkOn) highlightTint else originalTint)
                )
                h.postDelayed(this, 350)
            }
        }
        h.post(blinkRunnable!!)
    }

    private fun stopBlink() {
        blinkHandler?.removeCallbacksAndMessages(null)
        blinkHandler = null; blinkRunnable = null
        blinkTarget?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565c0"))
        blinkTarget = null
    }

    // ── 알람 ──────────────────────────────────────────────────────
    private fun startAlarm() {
        stopAlarm()
        try {
            val resId = resources.getIdentifier("s1", "raw", packageName)
            if (resId != 0) {
                mediaPlayer = MediaPlayer.create(this, resId)?.apply {
                    isLooping = true
                    start()
                }
            } else {
                setStatus("⚠️ s1.mp3 없음 (res/raw/에 추가하세요)")
            }
        } catch (e: Exception) {
            setStatus("알람 오류: ${e.message}")
        }
    }

    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // 텍스트 인식 버튼(b2~b5) 탭 시 입력 다이얼로그
    private fun showTextInputDialog(prefix: String) {
        Handler(Looper.getMainLooper()).post {
            val editText = EditText(this).apply {
                setText(textTargets[prefix] ?: "")
                hint = "찾을 텍스트 입력 (예: 예약하기)"
                setSingleLine(true)
                setPadding(40, 24, 40, 24)
            }
            val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("[$prefix] 인식할 텍스트 설정")
                .setMessage("화면에서 찾을 텍스트를 입력하세요.")
                .setView(editText)
                .setPositiveButton("저장") { _, _ ->
                    val text = editText.text.toString().trim()
                    textTargets[prefix] = text
                    saveTextTargets()
                    updateTextLabel(prefix, text)
                    setStatus("$prefix: \"$text\" 설정됨")
                }
                .setNegativeButton("취소", null)
                .also { builder ->
                    if (textTargets.containsKey(prefix)) {
                        builder.setNeutralButton("초기화") { _, _ ->
                            textTargets.remove(prefix)
                            saveTextTargets()
                            updateTextLabel(prefix, null)
                            setStatus("$prefix 초기화됨")
                        }
                    }
                }
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    private fun loadTextTargets() {
        val prefs = getSharedPreferences("text_targets", MODE_PRIVATE)
        listOf("b2","b3","b4","b5","b6","b7","b8","b9","b10","b11","b12").forEach { prefix ->
            val text = prefs.getString(prefix, null)
            if (!text.isNullOrBlank()) {
                textTargets[prefix] = text
                updateTextLabel(prefix, text)
            }
        }
    }

    private fun saveTextTargets() {
        val prefs = getSharedPreferences("text_targets", MODE_PRIVATE).edit()
        listOf("b2","b3","b4","b5","b6","b7","b8","b9","b10","b11","b12").forEach { prefix ->
            prefs.putString(prefix, textTargets[prefix] ?: "")
        }
        prefs.apply()
    }

    private fun updateTextLabel(prefix: String, text: String?) {
        val lblId = resources.getIdentifier("lbl_$prefix", "id", packageName)
        if (lblId == 0) return
        Handler(Looper.getMainLooper()).post {
            val lbl = panelView.findViewById<TextView>(lblId) ?: return@post
            if (text.isNullOrBlank()) {
                lbl.text = "$prefix\n미설정"
                lbl.setTextColor(Color.parseColor("#3a7a5a"))
            } else {
                lbl.text = "$prefix\n\"$text\""
                lbl.setTextColor(Color.parseColor("#64ffda"))
            }
        }
    }

    fun setStatus(msg: String) {
        logBuffer.addLast(msg)
        if (logBuffer.size > 5) logBuffer.removeFirst()
        val logText = logBuffer.joinToString("\n")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvStatus.text = msg
            tvLog?.text = logText
        } else {
            Handler(Looper.getMainLooper()).post {
                tvStatus.text = msg
                tvLog?.text = logText
            }
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "KTX 매크로", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String = "패널을 표시하려면 탭하세요"): Notification {
        val intent = Intent(this, FloatingPanelService::class.java).apply { action = ACTION_SHOW_PANEL }
        val pending = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KTX 자동예매 실행 중")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }
}
