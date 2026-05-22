package com.hsm.ktxmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// 권한 요청 화면 - 앱 시작점
class MainActivity : AppCompatActivity() {

    private val REQ_OVERLAY = 1001
    private val REQ_CAPTURE = 1002
    private val REQ_NOTIFICATION = 1003

    private lateinit var tvOverlay: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvCapture: TextView
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlay      = findViewById(R.id.tv_perm_overlay)
        tvAccessibility= findViewById(R.id.tv_perm_accessibility)
        tvCapture      = findViewById(R.id.tv_perm_capture)
        btnStart       = findViewById(R.id.btn_start)

        // Android 13+ 알림 권한 요청
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
        }

        findViewById<Button>(R.id.btn_grant_overlay).setOnClickListener {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
        }

        findViewById<Button>(R.id.btn_grant_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 3. 화면 캡처 허용 및 시작 (새 버튼)
        findViewById<Button>(R.id.btn_grant_capture).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("화면 공유 선택 안내")
                .setMessage("다음 화면에서 반드시\n'전체화면 공유'를 선택해주세요.\n\n(앱 하나 공유 선택 시 매크로가 정상 동작하지 않습니다)")
                .setPositiveButton("확인") { _, _ -> requestScreenCapture() }
                .show()
        }

        // 기존 패널이 숨겨진 경우 다시 표시
        btnStart.setOnClickListener {
            val svc = FloatingPanelService.instance
            if (svc != null) {
                startService(Intent(this, FloatingPanelService::class.java).apply {
                    action = FloatingPanelService.ACTION_SHOW_PANEL
                })
                moveTaskToBack(true)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("화면 공유 선택 안내")
                    .setMessage("다음 화면에서 반드시\n'전체화면 공유'를 선택해주세요.\n\n(앱 하나 공유 선택 시 매크로가 정상 동작하지 않습니다)")
                    .setPositiveButton("확인") { _, _ -> requestScreenCapture() }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        // 패널이 이미 실행 중이면 화면 점유 없이 바로 뒤로 넘김
        if (FloatingPanelService.instance != null) {
            moveTaskToBack(true)
        }
    }

    private fun updatePermissionUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = MacroAccessibilityService.isRunning()
                || MacroAccessibilityService.isEnabledInSettings(this)
        val serviceRunning = FloatingPanelService.instance != null

        tvOverlay.text = if (hasOverlay) "✅ 다른 앱 위에 표시 권한" else "⬜ 다른 앱 위에 표시 권한"
        tvAccessibility.text = if (hasAccessibility) "✅ 접근성 서비스 권한" else "⬜ 접근성 서비스 권한"
        tvCapture.text = if (serviceRunning) "✅ 화면 캡처 권한" else "⬜ 화면 캡처 권한"

        val ready = hasOverlay && hasAccessibility
        findViewById<Button>(R.id.btn_grant_capture).isEnabled = ready
        btnStart.isEnabled = ready
        btnStart.text = if (serviceRunning) "패널 다시 표시" else "매크로 패널 시작"
    }

    // 화면 캡처 권한 요청
    private fun requestScreenCapture() {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> updatePermissionUI()
            REQ_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    tvCapture.text = "✅ 화면 캡처 권한"
                    // FloatingPanelService 하나로 패널 + 화면 캡처 모두 처리
                    startForegroundService(Intent(this, FloatingPanelService::class.java).apply {
                        putExtra(FloatingPanelService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(FloatingPanelService.EXTRA_DATA, data)
                    })
                    moveTaskToBack(true)
                }
            }
        }
    }
}
