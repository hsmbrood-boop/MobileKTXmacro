package com.hsm.ktxmacro

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.sqrt

class SrtRegionSelectActivity : Activity() {

    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    private val mode by lazy { intent.getStringExtra("mode") }
    private val capturePrefix by lazy { intent.getStringExtra("capture_prefix") }
    private lateinit var regionView: RegionSelectView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        regionView = RegionSelectView(this)

        val frame = FrameLayout(this)
        frame.addView(regionView)

        val btnConfirm = Button(this).apply {
            text = "✓  확인"
            setBackgroundColor(Color.parseColor("#6a0572"))
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(80, 24, 80, 24)
        }
        val confirmParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 100
        }
        frame.addView(btnConfirm, confirmParams)
        btnConfirm.setOnClickListener { regionView.confirmSelection() }

        setContentView(frame)
    }

    inner class RegionSelectView(ctx: Context) : View(ctx) {

        private var boxLeft = 0f; private var boxTop = 0f
        private var boxRight = 0f; private var boxBottom = 0f
        private var initialized = false

        private var dragMode = DragMode.NONE
        private var lastX = 0f; private var lastY = 0f

        private val HANDLE_HIT = 70f
        private val HANDLE_VIS = 22f
        private val MIN_SIZE = if (capturePrefix != null) 10f else 80f

        private val snapshot: Bitmap? = SrtFloatingPanelService.instance?.captureSnapshot

        private val overlayPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#c084e8"); style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val handleFill = Paint().apply { color = Color.WHITE }
        private val handleBorder = Paint().apply {
            color = Color.parseColor("#c084e8"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val headerBg = Paint().apply { color = Color.argb(190, 0, 0, 0) }
        private val headerText = Paint().apply {
            color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val sizeText = Paint().apply {
            color = Color.parseColor("#FFD740"); textSize = 34f; textAlign = Paint.Align.CENTER
        }
        private val zoomBgPaint = Paint().apply { color = Color.argb(220, 20, 0, 20) }
        private val zoomBorderPaint = Paint().apply {
            color = Color.parseColor("#FFD740"); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        private val zoomLabelPaint = Paint().apply {
            color = Color.parseColor("#FFD740"); textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val zoomImgPaint = Paint().apply { isFilterBitmap = true }
        private val crossPaint = Paint().apply {
            color = Color.parseColor("#c084e8"); strokeWidth = 2.5f; alpha = 200
        }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            if (!initialized && w > 0 && h > 0) {
                val bw = w * 0.5f; val bh = h * 0.28f
                boxLeft = (w - bw) / 2; boxTop = (h - bh) / 2
                boxRight = boxLeft + bw; boxBottom = boxTop + bh
                initialized = true
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (snapshot != null) {
                canvas.drawBitmap(snapshot, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
            } else {
                canvas.drawColor(Color.BLACK)
            }

            canvas.drawRect(0f, 0f, width.toFloat(), boxTop, overlayPaint)
            canvas.drawRect(0f, boxBottom, width.toFloat(), height.toFloat(), overlayPaint)
            canvas.drawRect(0f, boxTop, boxLeft, boxBottom, overlayPaint)
            canvas.drawRect(boxRight, boxTop, width.toFloat(), boxBottom, overlayPaint)
            canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, borderPaint)

            for ((cx, cy) in listOf(
                boxLeft to boxTop, boxRight to boxTop,
                boxLeft to boxBottom, boxRight to boxBottom
            )) {
                canvas.drawCircle(cx, cy, HANDLE_VIS, handleFill)
                canvas.drawCircle(cx, cy, HANDLE_VIS, handleBorder)
            }

            val msg = if (capturePrefix != null) "[$capturePrefix] 상자 이동·모서리 크기조절 후 ✓ 확인"
                      else "sb2/sb3 탐색 범위 지정 (좌석/입석 버튼 영역) → ✓ 확인"
            canvas.drawRect(0f, 28f, width.toFloat(), 96f, headerBg)
            canvas.drawText(msg, width / 2f, 78f, headerText)

            val sx = (snapshot?.width?.toFloat() ?: width.toFloat()) / width
            val sy = (snapshot?.height?.toFloat() ?: height.toFloat()) / height
            val pw = ((boxRight - boxLeft) * sx).toInt()
            val ph = ((boxBottom - boxTop) * sy).toInt()
            canvas.drawText("$pw × $ph", (boxLeft + boxRight) / 2, boxBottom + 52f, sizeText)

            if (capturePrefix != null && snapshot != null) drawZoomPreview(canvas, sx, sy)
        }

        private fun drawZoomPreview(canvas: Canvas, sx: Float, sy: Float) {
            val snap = snapshot ?: return
            val srcL = (boxLeft * sx).toInt().coerceIn(0, snap.width - 1)
            val srcT = (boxTop * sy).toInt().coerceIn(0, snap.height - 1)
            val srcR = (boxRight * sx).toInt().coerceAtMost(snap.width)
            val srcB = (boxBottom * sy).toInt().coerceAtMost(snap.height)
            val srcW = (srcR - srcL).coerceAtLeast(1)
            val srcH = (srcB - srcT).coerceAtLeast(1)

            val panelW = width * 0.82f; val panelH = height * 0.24f
            val panelL = (width - panelW) / 2f; val panelB = height - 230f
            val panelT = panelB - panelH; val panelR = panelL + panelW

            canvas.drawRect(panelL - 6, panelT - 50f, panelR + 6, panelB + 6, zoomBgPaint)
            canvas.drawBitmap(snap, Rect(srcL, srcT, srcR, srcB),
                RectF(panelL, panelT, panelR, panelB), zoomImgPaint)
            canvas.drawRect(panelL, panelT, panelR, panelB, zoomBorderPaint)
            val cx = (panelL + panelR) / 2f; val cy = (panelT + panelB) / 2f
            canvas.drawLine(cx - 24f, cy, cx + 24f, cy, crossPaint)
            canvas.drawLine(cx, cy - 24f, cx, cy + 24f, crossPaint)
            val zoom = minOf(panelW / srcW, panelH / srcH)
            canvas.drawText("확대 ${"%.1f".format(zoom)}x  (${srcW}px × ${srcH}px)",
                width / 2f, panelT - 14f, zoomLabelPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    dragMode = hitTest(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    applyDrag(dx, dy); invalidate()
                }
                MotionEvent.ACTION_UP -> dragMode = DragMode.NONE
            }
            return true
        }

        private fun hitTest(x: Float, y: Float): DragMode {
            if (dist(x, y, boxLeft, boxTop) < HANDLE_HIT) return DragMode.RESIZE_TL
            if (dist(x, y, boxRight, boxTop) < HANDLE_HIT) return DragMode.RESIZE_TR
            if (dist(x, y, boxLeft, boxBottom) < HANDLE_HIT) return DragMode.RESIZE_BL
            if (dist(x, y, boxRight, boxBottom) < HANDLE_HIT) return DragMode.RESIZE_BR
            if (x in boxLeft..boxRight && y in boxTop..boxBottom) return DragMode.MOVE
            return DragMode.NONE
        }

        private fun applyDrag(dx: Float, dy: Float) {
            when (dragMode) {
                DragMode.MOVE -> {
                    val w = boxRight - boxLeft; val h = boxBottom - boxTop
                    boxLeft = (boxLeft + dx).coerceIn(0f, width - w)
                    boxTop = (boxTop + dy).coerceIn(0f, height - h)
                    boxRight = boxLeft + w; boxBottom = boxTop + h
                }
                DragMode.RESIZE_TL -> {
                    boxLeft = (boxLeft + dx).coerceAtMost(boxRight - MIN_SIZE)
                    boxTop = (boxTop + dy).coerceAtMost(boxBottom - MIN_SIZE)
                }
                DragMode.RESIZE_TR -> {
                    boxRight = (boxRight + dx).coerceAtLeast(boxLeft + MIN_SIZE)
                    boxTop = (boxTop + dy).coerceAtMost(boxBottom - MIN_SIZE)
                }
                DragMode.RESIZE_BL -> {
                    boxLeft = (boxLeft + dx).coerceAtMost(boxRight - MIN_SIZE)
                    boxBottom = (boxBottom + dy).coerceAtLeast(boxTop + MIN_SIZE)
                }
                DragMode.RESIZE_BR -> {
                    boxRight = (boxRight + dx).coerceAtLeast(boxLeft + MIN_SIZE)
                    boxBottom = (boxBottom + dy).coerceAtLeast(boxTop + MIN_SIZE)
                }
                DragMode.NONE -> {}
            }
        }

        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
            sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

        fun confirmSelection() {
            onRegionConfirmed(RectF(boxLeft, boxTop, boxRight, boxBottom))
        }
    }

    private fun onRegionConfirmed(rect: RectF) {
        val svc = SrtFloatingPanelService.instance
        if (svc == null && capturePrefix == null) { finish(); return }
        val screen = svc?.captureSnapshot

        val vw = regionView.width.toFloat().takeIf { it > 0 } ?: (screen?.width?.toFloat() ?: 1f)
        val vh = regionView.height.toFloat().takeIf { it > 0 } ?: (screen?.height?.toFloat() ?: 1f)
        val sx = (screen?.width?.toFloat() ?: vw) / vw
        val sy = (screen?.height?.toFloat() ?: vh) / vh

        fun scaleRect(): Rect {
            val l = (rect.left * sx).toInt().coerceIn(0, (screen?.width ?: Int.MAX_VALUE) - 1)
            val t = (rect.top * sy).toInt().coerceIn(0, (screen?.height ?: Int.MAX_VALUE) - 1)
            val r = (rect.right * sx).toInt()
            val b = (rect.bottom * sy).toInt()
            return Rect(l, t, r, b)
        }

        if (capturePrefix != null) {
            if (screen == null) { finish(); return }
            val scaled = scaleRect()
            val w = (scaled.width()).coerceAtMost(screen.width - scaled.left).coerceAtLeast(1)
            val h = (scaled.height()).coerceAtMost(screen.height - scaled.top).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(screen, scaled.left, scaled.top, w, h)
            svc!!.onImageCaptured(capturePrefix!!, cropped)
        } else {
            mode?.let { svc?.onRegionSelected(it, scaleRect()) }
        }
        finish()
    }

    override fun onDestroy() {
        SrtFloatingPanelService.instance?.isRegionSelectOpen = false
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
