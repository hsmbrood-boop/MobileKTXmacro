package com.hsm.ktxmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// PC의 pyautogui.click() / pyautogui.press("f5") 대체
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MacroAccessibilityService? = null
        fun isRunning() = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val pkg = context.packageName.lowercase()
            // 시스템은 "패키지명/풀클래스명" 또는 "패키지명/.클래스명" 형태로 저장
            return enabled.split(":").any { component ->
                val lower = component.lowercase()
                lower.startsWith(pkg) && lower.contains("macroaccessibilityservice")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenShareDialogHandled = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    // pyautogui.click(x, y) 대체
    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 120)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // 당겨서 새로고침
    fun swipeRefresh(screenWidth: Int, screenHeight: Int) {
        val path = Path().apply {
            moveTo(screenWidth / 2f, screenHeight * 0.25f)
            lineTo(screenWidth / 2f, screenHeight * 0.55f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            autoSelectFullScreen()
        }
    }

    // 화면 공유 다이얼로그에서 "전체 화면 공유" 자동 선택 후 "다음" 클릭
    private fun autoSelectFullScreen() {
        val root = rootInActiveWindow ?: return

        val appShareNodes = root.findAccessibilityNodeInfosByText("앱 하나 공유")
        if (appShareNodes.isNullOrEmpty()) {
            screenShareDialogHandled = false
            return
        }
        if (screenShareDialogHandled) return
        screenShareDialogHandled = true

        handler.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed
            val fullScreenNode = r.findAccessibilityNodeInfosByText("전체 화면 공유")?.firstOrNull()
            if (fullScreenNode != null) {
                // 바로 클릭 가능한 경우
                clickNode(fullScreenNode)
                handler.postDelayed({ clickButtonByText("다음") }, 600)
            } else {
                // 드롭다운을 먼저 펼쳐야 하는 경우
                val dropdown = r.findAccessibilityNodeInfosByText("앱 하나 공유")?.firstOrNull()
                clickNode(dropdown)
                handler.postDelayed({
                    val r2 = rootInActiveWindow ?: return@postDelayed
                    clickNode(r2.findAccessibilityNodeInfosByText("전체 화면 공유")?.firstOrNull())
                    handler.postDelayed({ clickButtonByText("다음") }, 600)
                }, 600)
            }
        }, 400)
    }

    private fun clickNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // 클릭 가능한 부모 탐색
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
                parent = parent.parent
            }
        }
    }

    private fun clickButtonByText(text: String) {
        val root = rootInActiveWindow ?: return
        clickNode(root.findAccessibilityNodeInfosByText(text)?.firstOrNull())
    }

    override fun onInterrupt() {}
}
