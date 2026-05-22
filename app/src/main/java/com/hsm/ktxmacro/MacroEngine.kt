package com.hsm.ktxmacro

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import kotlinx.coroutines.*

class MacroEngine(
    private val captureScreen: () -> Bitmap?,
    private val findText: suspend (String, Rect?) -> Point?,
    private val onStatus: (String) -> Unit,
    private val onAlarm: () -> Unit,
    private val onAlarmStop: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var searchJob: Job? = null
    private val matcher = ImageMatcher()
    private var textTargets: Map<String, String> = emptyMap()

    fun start(mode: String, region: Rect, templates: Map<String, Bitmap>, textTargets: Map<String, String>) {
        this.textTargets = textTargets
        onStatus("▶ 매크로 시작 중...")
        searchJob?.cancel()
        searchJob = scope.launch {
            try {
                when (mode) {
                    "seat"     -> searchSeat(templates, region)
                    "standing" -> searchStanding(templates, region)
                }
            } catch (e: CancellationException) {
                // 정상 취소
            } catch (e: Throwable) {
                onStatus("오류: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun stop() {
        searchJob?.cancel()
        searchJob = null
        onAlarmStop()
        onStatus("매크로 종료됨")
    }

    // ── 좌석예매: b1(전체) → b8(전체) 대기 → b2(범위) → b5(전체)? → b4(전체) → b5(전체)? → b7(전체)=알람
    private suspend fun searchSeat(t: Map<String, Bitmap>, region: Rect) {
        val t1 = t["b1"] ?: return status("b1 이미지를 먼저 캡처하세요 (▼ → b1)")

        while (true) {
            status("b1 탐색 중... (전체화면)")
            val pos1 = matchFull(t1)
            if (pos1 == null) { status("b1 없음 → 재시도 중"); delay(1000); continue }
            status("b1 발견 → 클릭")
            tap(pos1); delay(500)

            if (!textTargets["b8"].isNullOrBlank()) {
                status("b8 대기 중... (최대 30초)")
                val b8Deadline = System.currentTimeMillis() + 30_000L
                var b8Detected = false
                while (System.currentTimeMillis() < b8Deadline) {
                    if (matchText("b8") != null) { status("b8 감지 → 다음 단계"); b8Detected = true; break }
                    delay(300)
                }
                if (!b8Detected) { status("b8 30초 타임아웃 → 처음으로"); delay(500); continue }
            }

            status("b2 탐색 중... (지정범위)")
            val pos2 = matchText("b2", region)
            if (pos2 == null) { status("b2 없음 → 처음으로"); delay(300); continue }
            status("b2 발견 → 클릭")
            tap(pos2); delay(600)

            matchText("b5")?.also { status("b5 클릭"); tap(it); delay(500) }

            status("b4 탐색 중... (전체화면)")
            matchText("b4")?.also {
                status("b4 클릭"); tap(it); delay(600)
                waitForB6Dismiss()
            } ?: status("b4 없음")

            matchText("b5")?.also { status("b5 재클릭"); tap(it); delay(500) }

            status("b7 탐색 중... (전체화면)")
            if (matchText("b7") != null) {
                status("✅ b7 발견! 알람 시작")
                onAlarm()
                val end = System.currentTimeMillis() + 300_000L
                while (System.currentTimeMillis() < end) delay(500)
                onAlarmStop(); stop(); return
            }
            status("b7 없음 → 처음으로"); delay(300)
        }
    }

    // ── 입석포함예매: b1(전체) → b8(전체) 대기 → b2/b3(범위) → b5(전체)? → b4(전체) → b5(전체)? → b7(전체)=알람
    private suspend fun searchStanding(t: Map<String, Bitmap>, region: Rect) {
        val t1 = t["b1"] ?: return status("b1 이미지를 먼저 캡처하세요 (▼ → b1)")

        while (true) {
            status("b1 탐색 중... (전체화면)")
            val pos1 = matchFull(t1)
            if (pos1 == null) { status("b1 없음 → 재시도 중"); delay(1000); continue }
            status("b1 발견 → 클릭")
            tap(pos1); delay(500)

            if (!textTargets["b8"].isNullOrBlank()) {
                status("b8 대기 중... (최대 30초)")
                val b8Deadline2 = System.currentTimeMillis() + 30_000L
                var b8Detected2 = false
                while (System.currentTimeMillis() < b8Deadline2) {
                    if (matchText("b8") != null) { status("b8 감지 → 다음 단계"); b8Detected2 = true; break }
                    delay(300)
                }
                if (!b8Detected2) { status("b8 30초 타임아웃 → 처음으로"); delay(500); continue }
            }

            status("b2/b3 탐색 중... (지정범위)")
            val pos2 = matchText("b2", region) ?: matchText("b3", region)
            if (pos2 == null) { status("b2/b3 없음 → 처음으로"); delay(300); continue }
            status("b2/b3 발견 → 클릭")
            tap(pos2); delay(600)

            matchText("b5")?.also { status("b5 클릭"); tap(it); delay(500) }

            status("b4 탐색 중... (전체화면)")
            matchText("b4")?.also { status("b4 클릭"); tap(it); delay(600) } ?: status("b4 없음")

            matchText("b5")?.also { status("b5 재클릭"); tap(it); delay(500) }

            status("b7 탐색 중... (전체화면)")
            if (matchText("b7") != null) {
                status("✅ b7 발견! 알람 시작")
                onAlarm()
                val end = System.currentTimeMillis() + 300_000L
                while (System.currentTimeMillis() < end) delay(500)
                onAlarmStop(); stop(); return
            }
            status("b7 없음 → 처음으로"); delay(300)
        }
    }

    // b4 클릭 후 b6 팝업이 나타나면 사라질 때까지 대기 (최대 30초)
    private suspend fun waitForB6Dismiss() {
        if (textTargets["b6"].isNullOrBlank()) return
        if (matchText("b6") == null) return   // b6 없으면 바로 통과
        status("b6 감지 → 사라질 때까지 대기")
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (matchText("b6") == null) { status("b6 사라짐 → 계속 진행"); return }
        }
        status("b6 대기 타임아웃 → 계속 진행")
    }

    // b1: 전체화면 이미지 매칭
    private fun matchFull(template: Bitmap): Point? {
        val screen = captureScreen() ?: return null
        return try {
            matcher.match(screen, template)
        } catch (e: Exception) { null }
    }

    // 지정 범위 내 이미지 매칭 (현재 미사용, 향후 확장용)
    private fun matchRegion(template: Bitmap, region: Rect): Point? {
        val screen = captureScreen() ?: return null
        return try {
            matcher.matchInRegion(screen, template, region.left, region.top, region.width(), region.height())
        } catch (e: Exception) { null }
    }

    private suspend fun matchText(key: String, region: Rect? = null): Point? {
        val text = textTargets[key]?.takeIf { it.isNotBlank() } ?: return null
        return findText(text, region)
    }

    private fun tap(p: Point) = MacroAccessibilityService.instance?.tap(p.x, p.y)
    private fun status(msg: String) = onStatus(msg)
}
