package com.hsm.ktxmacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
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

class MainActivity : AppCompatActivity() {

    private val REQ_OVERLAY      = 1001
    private val REQ_CAPTURE_KTX  = 1002
    private val REQ_CAPTURE_SRT  = 1004
    private val REQ_NOTIFICATION = 1003

    private lateinit var tvOverlay: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStartSrt: Button

    private var testPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlay       = findViewById(R.id.tv_perm_overlay)
        tvAccessibility = findViewById(R.id.tv_perm_accessibility)
        btnStart        = findViewById(R.id.btn_start)
        btnStartSrt     = findViewById(R.id.btn_start_srt)

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

        btnStart.setOnClickListener {
            if (FloatingPanelService.instance != null) {
                startService(Intent(this, FloatingPanelService::class.java).apply {
                    action = FloatingPanelService.ACTION_SHOW_PANEL
                })
                moveTaskToBack(true)
            } else {
                requestKtxCapture()
            }
        }

        btnStartSrt.setOnClickListener {
            if (SrtFloatingPanelService.instance != null) {
                startService(Intent(this, SrtFloatingPanelService::class.java).apply {
                    action = SrtFloatingPanelService.ACTION_SHOW_PANEL
                })
                moveTaskToBack(true)
            } else {
                requestSrtCapture()
            }
        }

        val btnAlarmTest = findViewById<Button>(R.id.btn_alarm_test)
        btnAlarmTest.setOnClickListener {
            if (testPlayer != null) {
                testPlayer?.stop(); testPlayer?.release(); testPlayer = null
                btnAlarmTest.text = "알림 테스트 ▶"
            } else {
                val resId = resources.getIdentifier("s1", "raw", packageName)
                if (resId != 0) {
                    testPlayer = MediaPlayer.create(this, resId)?.apply { isLooping = true; start() }
                    btnAlarmTest.text = "알림 테스트 ■"
                }
            }
        }
    }

    override fun onDestroy() {
        testPlayer?.stop(); testPlayer?.release(); testPlayer = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        if (FloatingPanelService.instance != null || SrtFloatingPanelService.instance != null) {
            moveTaskToBack(true)
        }
    }

    private fun updatePermissionUI() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = MacroAccessibilityService.isRunning()
                || MacroAccessibilityService.isEnabledInSettings(this)
        val ktxRunning = FloatingPanelService.instance != null
        val srtRunning = SrtFloatingPanelService.instance != null

        tvOverlay.text = if (hasOverlay) "✅ 다른 앱 위에 표시 권한" else "⬜ 다른 앱 위에 표시 권한"
        tvAccessibility.text = if (hasAccessibility) "✅ 접근성 서비스 권한" else "⬜ 접근성 서비스 권한"

        val ready = hasOverlay && hasAccessibility
        btnStart.isEnabled = ready
        btnStart.text = if (ktxRunning) "KTX 패널 다시 표시" else "KTX 매크로 시작"
        btnStartSrt.isEnabled = ready
        btnStartSrt.text = if (srtRunning) "SRT 패널 다시 표시" else "SRT 매크로 시작"
    }

    private fun requestKtxCapture() {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_CAPTURE_KTX)
    }

    private fun requestSrtCapture() {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_CAPTURE_SRT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> updatePermissionUI()
            REQ_CAPTURE_KTX -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundService(Intent(this, FloatingPanelService::class.java).apply {
                        putExtra(FloatingPanelService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(FloatingPanelService.EXTRA_DATA, data)
                    })
                    moveTaskToBack(true)
                }
            }
            REQ_CAPTURE_SRT -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundService(Intent(this, SrtFloatingPanelService::class.java).apply {
                        putExtra(SrtFloatingPanelService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(SrtFloatingPanelService.EXTRA_DATA, data)
                    })
                    moveTaskToBack(true)
                }
            }
        }
    }
}
