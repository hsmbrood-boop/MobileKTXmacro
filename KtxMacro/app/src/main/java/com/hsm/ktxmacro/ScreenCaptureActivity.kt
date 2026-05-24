package com.hsm.ktxmacro

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureActivity : Activity() {

    private val REQ_CAPTURE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, FloatingPanelService::class.java).apply {
                putExtra(FloatingPanelService.EXTRA_RESULT_CODE, resultCode)
                putExtra(FloatingPanelService.EXTRA_DATA, data)
            })
            FloatingPanelService.instance?.setStatus("화면 캡처 권한 허용됨")
        } else {
            FloatingPanelService.instance?.setStatus("화면 캡처 권한 거부됨")
        }
        finish()
    }
}
