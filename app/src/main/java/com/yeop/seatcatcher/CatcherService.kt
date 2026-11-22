package com.yeop.seatcatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import java.io.File
import kotlin.random.Random

/**
 * 동작 순서
 *  SCAN       : 조회 결과에서 [시간대 안 + 원하는 등급 + 가격 표시] 열차 찾기 → 있으면 탭
 *               없으면 간격마다 새로고침
 *  WAIT_SHEET : 아래에서 올라온 창에서 등급 확인 → "바로 예매" 탭
 *  WAIT_RESULT: 결제 화면이 뜨면 성공 → 알림 + 종료 / 실패 팝업이면 닫고 다시 SCAN
 *
 * 다른 알림 오작동 방지
 *  - 화면 좌표 탭이 아니라 "코레일 앱 안의 버튼 노드"에 직접 클릭 액션을 보냄
 *    → 위에 알림 배너가 떠 있어도 배너가 눌리지 않음
 *  - 맨 앞 앱 창이 코레일이 아니면(알림 눌러서 다른 앱 열림, 전화 등) 아무것도 안 누르고 일시정지
 *  - 알림창(상단 내림)이 열려 있으면 일시정지
 *  - 부득이 좌표 탭을 할 때는 그 지점을 다른 창이 덮고 있으면 탭하지 않음
 */
class CatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "SeatCatcher"
        @Volatile var instance: CatcherService? = null
        @Volatile var statusText: String = "꺼져 있음"
        @Volatile var running: Boolean = false

        private const val TICK_MS = 700L
        private const val MAX_FAILS = 15

        private val SUCCESS_KEYS = listOf("결제하기", "결제기한", "결제금액", "결제수단", "예약완료", "예매완료", "발권하기")
        private val FAIL_KEYS = listOf("잔여석", "좌석이없", "매진되었", "판매가완료", "판매완료", "실패", "오류", "다시시도")
        private val CONFIRM_BUTTONS = setOf("확인", "예", "네", "예매하기", "예약하기")
        private val CLOSE_BUTTONS = setOf("확인", "닫기")
    }

    private enum class State { IDLE, SCAN, WAIT_LOAD, WAIT_SHEET, WAIT_RESULT }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var state = State.IDLE
    private var stateSince = 0L
    private var waitUntil = 0L
    private var nextRefreshAt = 0L
    private var startedAt = 0L
    private var forwardScrolls = 0
    private var fails = 0
    private var mustRefresh = false
    private var targetCls = "일반실"
    private var target = ""
    private var sheetToggled = false
    private var dumpAt = 0L
    private var overlay: TextView? = null
    private var screenW = 1080
    private var screenH = 2400

    private fun now() = SystemClock.elapsedRealtime()

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (t: Throwable) {
                Log.e(TAG, "step error", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ───────────── lifecycle ─────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        Notifier.ensureChannel(this)
        statusText = "준비됨 (시작 버튼을 누르세요)"
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 폴링 방식이라 사용 안 함 */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        instance = null
        running = false
        statusText = "꺼져 있음"
        super.onDestroy()
    }

    // ───────────── public API ─────────────

    fun startCatching() {
        startedAt = now()
        nextRefreshAt = now() + 3000
        forwardScrolls = 0
        fails = 0
        mustRefresh = false
        running = true
        go(State.WAIT_LOAD, 1500)
        showOverlay()
        setStatus("시작 · 코레일 조회 결과 화면을 띄워두세요")
    }

    fun stopCatching(reason: String = "중지됨") {
        state = State.IDLE
        running = false
        removeOverlay()
        setStatus(reason)
    }

    fun requestDump(delayMs: Long) {
        dumpAt = now() + delayMs
    }

    // ───────────── main loop ─────────────

    private fun go(s: State, waitMs: Long = 0) {
        state = s
        stateSince = now()
        waitUntil = now() + waitMs
    }

    private fun step() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val now = now()

        if (dumpAt in 1..now) {
            topKorailWindow()?.root?.let { root ->
                writeDump(root)
                dumpAt = 0L
            }
            if (dumpAt != 0L && now - dumpAt > 15_000) dumpAt = 0L
        }

        if (state == State.IDLE) return

        if (now - startedAt > prefs.maxMinutes * 60_000L) {
            finish("최대 실행 시간(${prefs.maxMinutes}분)이 지나서 자동 종료했어요", success = false)
            return
        }

        val win = topKorailWindow()
        if (win == null) {
            setStatus("코레일 앱이 맨 앞에 없음 → 일시정지")
            return
        }
        if (systemPanelOpen(win)) {
            setStatus("알림창/시스템 창이 열려 있음 → 일시정지")
            return
        }
        val root = win.root ?: return
        root.packageName?.toString()?.let { if (it != prefs.korailPkg) prefs.korailPkg = it }
        val items = ScreenParser.collect(root)

        when (state) {
            State.IDLE -> Unit
            State.WAIT_LOAD -> if (now >= waitUntil) go(State.SCAN)
            State.SCAN -> doScan(root, items, win)
            State.WAIT_SHEET -> doSheet(items, win)
            State.WAIT_RESULT -> doResult(items, win)
        }
    }

    private fun doScan(root: AccessibilityNodeInfo, items: List<ScreenParser.Item>, win: AccessibilityWindowInfo) {
        val now = now()

        // 예매 창이 열린 채로 남아 있으면 닫기
        if (items.any { ScreenParser.norm(it.text) == "바로예매" }) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            go(State.WAIT_LOAD, 1000)
            return
        }
        if (!stationOk(items)) {
            setStatus("조회 결과 화면이 아니거나 역이 다름 → 대기")
            return
        }
        // 직전에 놓쳤으면 같은(오래된) 화면을 다시 누르지 않도록 먼저 새로고침
        if (mustRefresh) {
            refresh(root, items, win)
            return
        }

        val rows = ScreenParser.parseRows(items, screenW, screenH)
        val pick = choose(rows)
        if (pick != null) {
            val (row, seat) = pick
            targetCls = seat.cls
            target = "${row.depText} ${seat.cls} ${seat.status}"
            if (clickNode(seat.node, win)) {
                sheetToggled = false
                go(State.WAIT_SHEET)
                setStatus("좌석 발견! $target 선택 중")
                return
            }
        }

        // 원하는 시간대가 화면 아래에 있으면 스크롤
        if (rows.isNotEmpty() && forwardScrolls < 3 && rows.maxOf { it.depMin } < prefs.endMin) {
            val list = ScreenParser.largestScrollable(root)
            if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                forwardScrolls++
                go(State.WAIT_LOAD, 900)
                return
            }
        }

        if (now >= nextRefreshAt) {
            refresh(root, items, win)
            return
        }
        val visible = if (rows.isEmpty()) "열차 인식 안 됨" else "열차 ${rows.size}개 인식"
        setStatus("감시 중 · $visible · 다음 새로고침 ${(nextRefreshAt - now) / 1000}초")
    }

    private fun doSheet(items: List<ScreenParser.Item>, win: AccessibilityWindowInfo) {
        val now = now()
        val btn = items.firstOrNull { ScreenParser.norm(it.text) == "바로예매" }
        if (btn == null) {
            if (now - stateSince > 6000) {
                setStatus("예매 창이 안 열려서 다시 감시")
                go(State.SCAN)
            }
            return
        }
        // 창 안의 [일반실 | 특실] 토글을 원하는 등급으로
        if (!sheetToggled) {
            sheetToggled = true
            val toggle = items.filter {
                ScreenParser.norm(it.text) == targetCls &&
                    it.rect.centerY() < btn.rect.top &&
                    it.rect.centerY() > btn.rect.top - screenH * 0.3
            }.maxByOrNull { it.rect.centerY() }
            if (toggle != null) {
                clickNode(toggle.node, win)
                waitUntil = now + 500
                return
            }
        }
        if (now < waitUntil) return
        if (clickNode(btn.node, win)) {
            go(State.WAIT_RESULT)
            setStatus("바로 예매 누름 · 결과 확인 중")
        }
    }

    private fun doResult(items: List<ScreenParser.Item>, win: AccessibilityWindowInfo) {
        val now = now()
        val texts = items.map { ScreenParser.norm(it.text) }.filter { it.isNotEmpty() }
        val joined = texts.joinToString("|")
        val sheetStillOpen = texts.any { it == "바로예매" }

        val confirmBtn = items.lastOrNull { ScreenParser.norm(it.text) in CONFIRM_BUTTONS }
        val closeBtn = items.lastOrNull { ScreenParser.norm(it.text) in CLOSE_BUTTONS }
        val failed = FAIL_KEYS.any { joined.contains(it) }

        when {
            !sheetStillOpen && SUCCESS_KEYS.any { joined.contains(it) } -> {
                finish("예매 성공! $target\n코레일 앱에서 결제기한 안에 결제하세요", success = true)
            }
            failed && closeBtn != null -> {
                clickNode(closeBtn.node, win)
                fails++
                if (fails >= MAX_FAILS) {
                    finish("${fails}번 연속 놓쳐서 멈췄어요", success = false)
                    return
                }
                mustRefresh = true
                setStatus("놓침($target) → 새로고침 후 다시 감시")
                go(State.WAIT_LOAD, 1200)
            }
            confirmBtn != null && joined.contains("하시겠습니까") &&
                (joined.contains("예매") || joined.contains("예약")) -> {
                // "예매하시겠습니까?" 같은 확인 팝업
                clickNode(confirmBtn.node, win)
                stateSince = now
                setStatus("예매 확인 팝업 → 확인")
            }
            !sheetStillOpen && texts.any { it == "로그인" } -> {
                finish("로그인이 필요해요. 로그인 후 다시 시작하세요", success = false)
            }
            now - stateSince > 20_000 -> {
                finish("예매 결과를 판단하지 못했어요. 코레일 앱을 직접 확인하세요 ($target)", success = false)
            }
        }
    }

    private fun choose(rows: List<ScreenParser.Row>): Pair<ScreenParser.Row, ScreenParser.Seat>? {
        val classes = when (prefs.seatMode) {
            0 -> listOf("일반실")
            1 -> listOf("특실")
            else -> listOf("일반실", "특실")
        }
        val range = prefs.startMin..prefs.endMin
        for (row in rows.filter { it.depMin in range }.sortedBy { it.depMin }) {
            for (c in classes) {
                row.seats.firstOrNull { it.cls == c && it.available }?.let { return row to it }
            }
        }
        return null
    }

    private fun refresh(root: AccessibilityNodeInfo, items: List<ScreenParser.Item>, win: AccessibilityWindowInfo) {
        if (forwardScrolls > 0) {
            val list = ScreenParser.largestScrollable(root)
            repeat(forwardScrolls) { list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            forwardScrolls = 0
        }
        val btn = ScreenParser.findRefresh(items, screenW, screenH)
        val ok = if (btn != null) clickNode(btn, win) else swipeDown(win)
        val base = prefs.intervalSec.coerceAtLeast(2) * 1000L
        nextRefreshAt = now() + (base * Random.nextDouble(0.8, 1.3)).toLong()
        mustRefresh = false
        setStatus(if (ok) "새로고침" else "새로고침 버튼을 못 찾음 (디버그 덤프 보내주세요)")
        go(State.WAIT_LOAD, 1200)
    }

    private fun finish(msg: String, success: Boolean) {
        stopCatching(msg)
        Notifier.notify(
            this,
            if (success) "🚄 KTX 좌석 잡았어요! 결제하세요" else "좌석잡이 멈춤",
            msg,
            prefs.korailPkg,
            success,
        )
    }

    // ───────────── 창 / 클릭 안전장치 ─────────────

    /** 맨 앞 앱 창이 코레일일 때만 반환 */
    private fun topKorailWindow(): AccessibilityWindowInfo? {
        val ws = try { windows } catch (e: Exception) { return null }
        val top = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer } ?: return null
        val pkg = top.root?.packageName?.toString() ?: return null
        return if (pkg.contains("korail", ignoreCase = true)) top else null
    }

    /** 알림창이 내려와 있는 등 큰 시스템 창이 코레일 위에 있으면 true */
    private fun systemPanelOpen(win: AccessibilityWindowInfo): Boolean = try {
        windows.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && w.layer > win.layer &&
                Rect().also { w.getBoundsInScreen(it) }.height() > screenH * 0.3
        }
    } catch (e: Exception) { true }

    /** 좌표 (x,y)를 코레일보다 위에 있는 다른 창(알림 배너 등)이 덮고 있는지 */
    private fun pointCovered(x: Int, y: Int, win: AccessibilityWindowInfo): Boolean = try {
        windows.any { w ->
            w.layer > win.layer && w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                Rect().also { w.getBoundsInScreen(it) }.contains(x, y)
        }
    } catch (e: Exception) { true }

    /** 노드 직접 클릭(알림 배너에 안 눌림). 안 되면 덮인 곳이 아닐 때만 좌표 탭 */
    private fun clickNode(node: AccessibilityNodeInfo, win: AccessibilityWindowInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY(), win)
    }

    private fun tap(x: Int, y: Int, win: AccessibilityWindowInfo): Boolean {
        if (pointCovered(x, y, win)) return false
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun swipeDown(win: AccessibilityWindowInfo): Boolean {
        val x = screenW / 2
        val y1 = (screenH * 0.35).toInt()
        val y2 = (screenH * 0.75).toInt()
        if (pointCovered(x, y1, win) || pointCovered(x, y2, win)) return false
        val p = Path().apply {
            moveTo(x.toFloat(), y1.toFloat())
            lineTo(x.toFloat(), y2.toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 400))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun stationOk(items: List<ScreenParser.Item>): Boolean {
        val from = ScreenParser.norm(prefs.fromStation)
        val to = ScreenParser.norm(prefs.toStation)
        if (from.isEmpty() && to.isEmpty()) return true
        val top = items.filter { it.rect.centerY() < screenH * 0.15 }
            .joinToString("|") { ScreenParser.norm(it.text) }
        return (from.isEmpty() || top.contains(from)) && (to.isEmpty() || top.contains(to))
    }

    // ───────────── 상태 표시 오버레이 (화면 꺼짐 방지 겸) ─────────────

    private fun setStatus(s: String) {
        statusText = s
        overlay?.text = "🚄 좌석잡이 · $s  (탭=중지)"
    }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 10f
            setPadding(18, 4, 18, 4)
            maxLines = 2
            keepScreenOn = true
            setOnClickListener { stopCatching("사용자가 중지함") }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        try {
            wm.addView(tv, lp)
            overlay = tv
        } catch (e: Exception) {
            Log.e(TAG, "overlay", e)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    // ───────────── 디버그 ─────────────

    private fun writeDump(root: AccessibilityNodeInfo) {
        val sb = StringBuilder()
        sb.append("screen ${screenW}x$screenH pkg=${root.packageName}\n")
        try {
            windows.forEach { w ->
                val r = Rect(); w.getBoundsInScreen(r)
                sb.append("window type=${w.type} layer=${w.layer} ${r.toShortString()} pkg=${w.root?.packageName}\n")
            }
        } catch (_: Exception) {
        }
        sb.append("---\n").append(ScreenParser.dump(root))
        val rows = ScreenParser.parseRows(ScreenParser.collect(root), screenW, screenH)
        sb.append("--- 인식 결과\n")
        rows.forEach { r ->
            sb.append("${r.depText}: ").append(r.seats.joinToString { "${it.cls}=${it.status}(${if (it.available) "좌석" else "X"})" }).append('\n')
        }
        File(filesDir, "dump.txt").writeText(sb.toString())
        statusText = "화면 구조 저장 완료 (좌석잡이 앱에서 복사)"
    }
}
