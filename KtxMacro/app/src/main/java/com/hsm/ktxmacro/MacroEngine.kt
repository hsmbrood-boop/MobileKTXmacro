package com.hsm.ktxmacro

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import kotlinx.coroutines.*

class MacroEngine(
    private val captureScreen: () -> Bitmap?,
    private val findText: suspend (String, Rect?) -> Point?,
    private val findAllText: suspend (String, Rect?) -> List<Point>,
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
                    "seat"         -> searchMode(templates, region, "b",  standing = false)
                    "standing"     -> searchMode(templates, region, "b",  standing = true)
                    "srt_seat"     -> srtSeatMode(templates, region, standing = false)
                    "srt_standing" -> srtSeatMode(templates, region, standing = true)
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

    // ── SRT 좌석예매 전용 흐름 ──────────────────────────────────────
    private suspend fun srtSeatMode(t: Map<String, Bitmap>, region: Rect, standing: Boolean = false) {
        val sb1 = t["sb1"] ?: return status("sb1 이미지를 먼저 캡처하세요")
        val sb2 = t["sb2"] ?: return status("sb2 이미지를 먼저 캡처하세요")

        while (true) {
            // 1. sb1 찾아서 클릭, sb7이 나타나면 사라질 때까지 대기
            status("sb1 탐색 중...")
            val pos1 = matchFull(sb1)
            if (pos1 == null) { delay(500); continue }
            status("sb1 발견 → 클릭")
            tap(pos1); delay(600)
            waitForSb7Dismiss()

            // 2. sb1 확인 (클릭 안함), 없으면 나올 때까지 대기
            status("sb1 확인 대기 중...")
            while (matchFull(sb1) == null) { delay(500) }
            status("sb1 확인됨")

            // 3. sb2 이미지 대기 (나올 때까지)
            status("sb2 대기 중...")
            while (matchFull(sb2) == null) { delay(500) }
            status("sb2 확인됨")

            // 4. sb3/sb4 탐색 및 클릭
            if (standing) {
                // 입석포함: sb3 있으면 클릭, 없으면 sb4 시도, 없으면 진행
                status("sb3 탐색 중... (지정범위)")
                val pos3 = matchText("sb3", region)
                if (pos3 != null) {
                    status("sb3 발견 → 클릭"); tap(pos3); delay(500)
                } else {
                    status("sb3 없음 → sb4 탐색 중...")
                    val pos4 = matchText("sb4", region)
                    if (pos4 != null) { status("sb4 발견 → 클릭"); tap(pos4); delay(500) }
                    else { status("sb3/sb4 없음 → 처음으로"); delay(300); continue }
                }
            } else {
                // 좌석예매: sb3 없으면 처음으로
                status("sb3 탐색 중... (지정범위)")
                val pos3 = matchText("sb3", region)
                if (pos3 == null) { status("sb3 없음 → 처음으로"); delay(300); continue }
                status("sb3 발견 → 클릭"); tap(pos3); delay(500)
            }

            // 5. sb5 있으면 모두 클릭, 없으면 진행
            clickAllVisible("sb5")

            // 6. sb6 찾아서 클릭
            status("sb6 탐색 중...")
            val pos6 = matchText("sb6")
            if (pos6 != null) { status("sb6 발견 → 클릭"); tap(pos6); delay(500) }
            else status("sb6 없음 → 진행")

            // 7. sb7 있으면 사라질 때까지 대기, 없으면 진행
            waitForSb7Dismiss()

            // 8. sb5 있으면 모두 클릭, 없으면 진행
            clickAllVisible("sb5")

            // 9. sb8을 3초간 탐색
            status("sb8 탐색 중... (3초)")
            val sb8Deadline = System.currentTimeMillis() + 3_000L
            var sb8Found = false
            while (System.currentTimeMillis() < sb8Deadline) {
                if (matchText("sb8") != null) { sb8Found = true; break }
                delay(200)
            }
            if (sb8Found) {
                status("✅ sb8 발견! 알람 시작 (5분)")
                onAlarm()
                delay(300_000L)
                onAlarmStop(); stop(); return
            }
            // sb8 없으면 sb5 있으면 클릭, 처음으로 반복
            clickAllVisible("sb5")
            status("처음으로 반복")
            delay(300)
        }
    }

    private suspend fun clickAllVisible(key: String) {
        var targets = matchAllText(key)
        if (targets.isEmpty()) { status("$key 없음 → 진행"); return }
        status("$key 발견 → 가장 낮은 위치 클릭 (사라질 때까지)")
        val deadline = System.currentTimeMillis() + 30_000L
        while (targets.isNotEmpty() && System.currentTimeMillis() < deadline) {
            val lowest = targets.maxByOrNull { it.y } ?: break
            tap(lowest)
            delay(300)
            targets = matchAllText(key)
        }
        if (targets.isEmpty()) status("$key 사라짐 → 진행")
        else status("$key 타임아웃 → 진행")
    }

    private suspend fun waitForSb7Dismiss() {
        if (textTargets["sb7"].isNullOrBlank()) return
        if (matchText("sb7") == null) { status("sb7 없음 → 진행"); return }
        status("sb7 감지 → 사라질 때까지 대기")
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (matchText("sb7") == null) { status("sb7 사라짐 → 진행"); return }
        }
        status("sb7 대기 타임아웃 → 진행")
    }

    // ── 공통 매크로 흐름 (p="b" → KTX, p="sb" → SRT) ──────────────
    private suspend fun searchMode(t: Map<String, Bitmap>, region: Rect, p: String, standing: Boolean) {
        val t1 = t["${p}1"] ?: return status("${p}1 이미지를 먼저 캡처하세요 (▼ → ${p}1)")

        while (true) {
            status("${p}1 탐색 중... (전체화면)")
            val pos1 = matchFull(t1)
            if (pos1 == null) { status("${p}1 없음 → 재시도 중"); delay(1000); continue }
            status("${p}1 발견 → 클릭")
            tap(pos1); delay(500)
            waitForB6Dismiss(p)

            if (!textTargets["${p}8"].isNullOrBlank()) {
                status("${p}8 대기 중... (최대 1초)")
                val b8Deadline = System.currentTimeMillis() + 1_000L
                var b8Detected = false
                while (System.currentTimeMillis() < b8Deadline) {
                    if (matchText("${p}8") != null) { status("${p}8 감지 → 다음 단계"); b8Detected = true; break }
                    delay(300)
                }
                if (!b8Detected) { status("${p}8 타임아웃 → 처음으로"); delay(500); continue }
            }

            if (standing) {
                status("${p}2/${p}3 탐색 중... (지정범위 이미지/텍스트)")
                val pos2 = t["${p}2"]?.let { matchRegion(it, region) } ?: matchText("${p}3", region)
                if (pos2 == null) { status("${p}2/${p}3 없음 → 처음으로"); delay(300); continue }
                status("${p}2/${p}3 발견 → 클릭")
                tap(pos2); delay(600)
            } else {
                status("${p}2 탐색 중... (지정범위 이미지)")
                val pos2 = t["${p}2"]?.let { matchRegion(it, region) }
                if (pos2 == null) { status("${p}2 없음 → 처음으로"); delay(300); continue }
                status("${p}2 발견 → 클릭")
                tap(pos2); delay(600)
            }

            clickB5For200ms(p)

            status("${p}4 탐색 중... (전체화면)")
            if (!clickEachB4Keyword(p)) status("${p}4 없음")
            if (!standing) waitForB6Dismiss(p)

            clickB5For200ms(p)

            status("${p}7 탐색 중... (5초)")
            val b7Deadline = System.currentTimeMillis() + 5_000L
            var b7Found = false
            while (System.currentTimeMillis() < b7Deadline) {
                if (matchText("${p}7") != null) { b7Found = true; break }
                delay(200)
            }
            if (b7Found) {
                status("✅ ${p}7 발견! 알람 시작")
                onAlarm()
                val end = System.currentTimeMillis() + 300_000L
                while (System.currentTimeMillis() < end) delay(500)
                onAlarmStop(); stop(); return
            }
            clickB5OneByOne(p)
            status("${p}7 없음 → 처음으로"); delay(300)
        }
    }

    // b5/sb5: 전체화면에서 모두 찾아 각각 클릭, 0.2초 동안 사라질 때까지 0.1초마다 반복
    private suspend fun clickB5For200ms(p: String = "b") {
        if (textTargets["${p}5"].isNullOrBlank()) return
        val first = matchAllText("${p}5")
        if (first.isEmpty()) { status("${p}5 없음 → 진행"); return }
        status("${p}5 ${first.size}개 발견 → 클릭 중...")
        first.forEach { tap(it) }
        val deadline = System.currentTimeMillis() + 200L
        while (System.currentTimeMillis() < deadline) {
            delay(100)
            val list = matchAllText("${p}5")
            if (list.isEmpty()) { status("${p}5 사라짐 → 진행"); return }
            list.forEach { tap(it) }
        }
        status("${p}5 클릭 완료 → 진행")
    }

    // b5: 0.5초간 재탐색 → 있으면 하나씩 클릭, 사라질 때까지 반복
    private suspend fun clickB5OneByOne(p: String) {
        val searchDeadline = System.currentTimeMillis() + 500L
        var list = emptyList<Point>()
        while (System.currentTimeMillis() < searchDeadline) {
            list = matchAllText("${p}5")
            if (list.isNotEmpty()) break
            delay(100)
        }
        if (list.isEmpty()) { status("${p}5 없음 → 진행"); return }
        status("${p}5 ${list.size}개 발견 → 하나씩 클릭 (사라질 때까지)")
        val deadline = System.currentTimeMillis() + 30_000L
        while (list.isNotEmpty() && System.currentTimeMillis() < deadline) {
            list.forEach { tap(it); delay(200) }
            list = matchAllText("${p}5")
        }
        if (list.isEmpty()) status("${p}5 사라짐 → 진행")
        else status("${p}5 타임아웃 → 진행")
    }

    // b4/sb4: 쉼표 구분 모든 키워드 순서대로 각각 검색 후 클릭
    private suspend fun clickEachB4Keyword(p: String = "b"): Boolean {
        val text = textTargets["${p}4"]?.takeIf { it.isNotBlank() } ?: return false
        val keywords = text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (keywords.isEmpty()) return false
        var anyFound = false
        for (keyword in keywords) {
            val pos = findText(keyword, null)
            if (pos != null) {
                status("${p}4[$keyword] 클릭"); tap(pos); delay(300)
                anyFound = true
            }
        }
        return anyFound
    }

    private suspend fun matchAllText(key: String, region: Rect? = null): List<Point> {
        val text = textTargets[key]?.takeIf { it.isNotBlank() } ?: return emptyList()
        return findAllText(text, region)
    }

    // b6/sb6 팝업이 나타나면 사라질 때까지 대기 (최대 30초)
    private suspend fun waitForB6Dismiss(p: String = "b") {
        if (textTargets["${p}6"].isNullOrBlank()) return
        if (matchText("${p}6") == null) return
        status("${p}6 감지 → 사라질 때까지 대기")
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            if (matchText("${p}6") == null) { status("${p}6 사라짐 → 계속 진행"); return }
        }
        status("${p}6 대기 타임아웃 → 계속 진행")
    }

    private fun matchFull(template: Bitmap): Point? {
        val screen = captureScreen() ?: return null
        return try { matcher.match(screen, template) } catch (e: Exception) { null }
    }

    private fun matchRegion(template: Bitmap, region: Rect): Point? {
        val screen = captureScreen() ?: return null
        return try {
            matcher.matchInRegion(screen, template, region.left, region.top, region.width(), region.height())
        } catch (e: Exception) { null }
    }

    private suspend fun matchText(key: String, region: Rect? = null): Point? {
        val text = textTargets[key]?.takeIf { it.isNotBlank() } ?: return null
        return findText(text, region) ?: run { delay(150); findText(text, region) }
    }

    private fun tap(p: Point) = MacroAccessibilityService.instance?.tap(p.x, p.y)
    private fun status(msg: String) = onStatus(msg)
}
