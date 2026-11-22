package com.yeop.seatcatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import kotlin.random.Random

/**
 * 동작 순서
 *  SCAN       : 조회 결과에서 [시간대 안 + 원하는 등급 + 가격 표시] 열차 찾기 → 있으면 탭
 *               없으면 간격마다 새로고침
 *  WAIT_SHEET : 아래에서 올라온 창에서 등급 확인 → "바로 예매" 탭
 *  WAIT_RESULT: 결제 화면이 뜨면 성공 → 알림 + 종료 / 실패 팝업이면 닫고 다시 SCAN
 *
 * 화면 읽기
 *  - 코레일 새 앱은 열차 줄이 글자 없는 버튼이라 접근성으로 못 읽음
 *    → 화면 캡처 + 한국어 OCR(기기 안에서 처리)로 시간/가격을 읽고,
 *      클릭은 그 위치의 버튼 노드에 직접 보냄
 *  - 알림 배너, 상태바처럼 코레일 위에 떠 있는 창 영역의 글자는 OCR 결과에서 제외
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

        private const val TICK_MS = 150L
        private const val OCR_SCALE = 0.6f
        private const val MAX_FAILS = 15

        private val SUCCESS_KEYS = listOf("결제하기", "결제기한", "결제금액", "결제수단", "예약완료", "예매완료", "발권하기")
        private val RESERVED_KEYS = listOf("예약이완료", "예매가완료", "예약완료", "예매완료", "완료되었습니다", "결제하지않으면")
        private val FAIL_KEYS = listOf("잔여석", "좌석이없", "매진되었", "판매가완료", "판매완료", "실패", "오류", "다시시도")
        private val CONFIRM_BUTTONS = setOf("확인", "예", "네", "예매하기", "예약하기")
        private val CLOSE_BUTTONS = setOf("확인", "닫기")
    }

    private enum class State { IDLE, SCAN, WAIT_LOAD, LOAD_APPEAR, LOAD_GONE, WAIT_SHEET, WAIT_RESULT }

    private var scanDoneAt = 0L      // 화면 확인(전부 매진)이 끝난 시각
    private var spinnerSeen = false

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

    // OCR
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private var ocrBusy = false
    private var ocrItems: List<ScreenParser.Item> = emptyList()
    private var ocrShotAt = 0L
    private var lastShotReq = 0L
    private var lastActionAt = 0L
    private var ocrError = ""
    private var a11yItems: List<ScreenParser.Item> = emptyList()

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
        if (s == State.SCAN || s == State.WAIT_LOAD) scanDoneAt = 0L
        state = s
        stateSince = now()
        waitUntil = now() + waitMs
    }

    private fun sizeFrom(win: AccessibilityWindowInfo) {
        val r = Rect()
        win.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            screenW = r.width()
            screenH = r.height()
        } else {
            screenW = resources.displayMetrics.widthPixels
            screenH = resources.displayMetrics.heightPixels
        }
    }

    private fun ocrValid() = ocrShotAt > lastActionAt + 120

    private fun step() {
        val now = now()

        if (dumpAt in 1..now) {
            val w = topKorailWindow()
            if (w != null) {
                sizeFrom(w)
                requestOcr(w)
                val root = w.root
                if (root != null && (ocrShotAt >= dumpAt || now - dumpAt > 8000)) {
                    writeDump(root)
                    dumpAt = 0L
                }
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
        sizeFrom(win)
        requestOcr(win)
        a11yItems = ScreenParser.collect(root)
        val items = if (ocrValid()) a11yItems + ocrItems else a11yItems

        when (state) {
            State.IDLE -> Unit
            State.WAIT_LOAD -> if (now >= waitUntil) go(State.SCAN)
            // 새로고침 후: 빙글빙글이 나타나길 기다림 (안 나타나면 금방 끝난 것)
            State.LOAD_APPEAR -> when {
                isLoading() -> { spinnerSeen = true; go(State.LOAD_GONE); setStatus("로딩 중…") }
                now - stateSince > 1500 -> { lastActionAt = now; go(State.SCAN) }
                else -> setStatus("새로고침 · 로딩 확인 중")
            }
            // 빙글빙글이 사라지면 그 뒤에 찍은 화면으로 판단
            State.LOAD_GONE -> when {
                !isLoading() -> { lastActionAt = now; go(State.SCAN) }
                now - stateSince > 15_000 -> { setStatus("응답이 없어서 다시 새로고침"); refresh(root, items, win) }
                else -> setStatus("로딩 중… ${(now - stateSince) / 1000}초")
            }
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
            lastActionAt = now()
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
        if (rows.isEmpty() && !ocrValid()) {
            setStatus(if (ocrError.isNotEmpty()) ocrError else "화면 읽는 중…")
            return
        }
        val pick = choose(rows)
        if (pick != null) {
            val (row, seat) = pick
            targetCls = seat.cls
            target = "${row.depText} ${seat.cls} ${seat.status}"
            if (clickRect(seat.node, seat.rect, win)) {
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
                lastActionAt = now()
                forwardScrolls++
                go(State.WAIT_LOAD, 300)
                return
            }
        }

        // 시간대 안 열차의 원하는 등급 상태(매진/가격)를 전부 읽었는지 확인
        val wanted = when (prefs.seatMode) { 0 -> listOf("일반실"); 1 -> listOf("특실"); else -> listOf("일반실", "특실") }
        val inRange = rows.filter { it.depMin in prefs.startMin..prefs.endMin }
        val bottomRow = rows.maxByOrNull { it.depMin }   // 맨 아래 줄은 잘려 있을 수 있어 제외
        val complete = rows.isNotEmpty() && inRange.filter { it !== bottomRow || inRange.size == 1 }.all { r ->
            wanted.all { c -> r.seats.any { it.cls == c && it.status.isNotEmpty() } }
        }
        if (!complete && now - stateSince < 4000) {
            setStatus("결과 읽는 중… (${rows.size}개 열차)")
            return
        }
        if (scanDoneAt == 0L) {
            scanDoneAt = now
            val rest = prefs.intervalSec.coerceAtLeast(0) * 1000L
            nextRefreshAt = now + if (rest == 0L) 0L else (rest * Random.nextDouble(0.8, 1.3)).toLong()
        }
        if (now >= nextRefreshAt) {
            refresh(root, items, win)
            return
        }
        val visible = if (inRange.isEmpty()) "시간대 열차 안 보임" else "${inRange.size}개 열차 매진 확인"
        setStatus("$visible · ${(nextRefreshAt - now) / 100 / 10.0}초 뒤 새로고침")
    }

    private fun doSheet(items: List<ScreenParser.Item>, win: AccessibilityWindowInfo) {
        val now = now()
        val btn = items.firstOrNull { ScreenParser.norm(it.text) == "바로예매" }
        if (btn == null) {
            if (now - stateSince > 8000) {
                setStatus("예매 창이 안 열려서 다시 감시")
                go(State.SCAN)
            }
            return
        }
        // 창 안의 [일반실 | 특실] 토글을 원하는 등급으로
        if (!sheetToggled && targetCls != "일반실") {   // 예매 창은 일반실이 기본 선택
            sheetToggled = true
            val toggle = items.filter {
                ScreenParser.norm(it.text) == targetCls &&
                    it.rect.centerY() < btn.rect.top &&
                    it.rect.centerY() > btn.rect.top - screenH * 0.3
            }.maxByOrNull { it.rect.centerY() }
            if (toggle != null) {
                clickRect(toggle.node, toggle.rect, win)
                waitUntil = now + 200
                return
            }
        }
        if (now < waitUntil) return
        if (clickRect(btn.node, btn.rect, win)) {
            go(State.WAIT_RESULT)
            setStatus("바로 예매 누름 · 결과 확인 중")
        }
    }

    private fun doResult(items: List<ScreenParser.Item>, win: AccessibilityWindowInfo) {
        val now = now()
        if (!ocrValid() && Build.VERSION.SDK_INT >= 30 && now - stateSince < 20_000) return
        val texts = items.map { ScreenParser.norm(it.text) }.filter { it.isNotEmpty() }
        val joined = texts.joinToString("|")
        val sheetStillOpen = texts.any { it == "바로예매" }

        val confirmBtn = items.lastOrNull { ScreenParser.norm(it.text) in CONFIRM_BUTTONS }
        val closeBtn = items.lastOrNull { ScreenParser.norm(it.text) in CLOSE_BUTTONS }
        val failed = FAIL_KEYS.any { joined.contains(it) }

        // "승차권 예약이 완료되었습니다 … [장바구니 담기] [결제]" 팝업
        val reserved = RESERVED_KEYS.any { joined.contains(it) }
        if (reserved) {
            val pay = items.filter { ScreenParser.norm(it.text) == "결제" }.maxByOrNull { it.rect.centerY() }
            val paid = pay != null && clickRect(pay.node, pay.rect, win)
            finish(
                if (paid) "예약 완료! $target\n결제 화면을 열어뒀어요. 결제기한 안에 결제하세요"
                else "예약 완료! $target\n코레일 앱에서 [결제]를 눌러 결제하세요",
                success = true,
            )
            return
        }

        when {
            !sheetStillOpen && SUCCESS_KEYS.any { joined.contains(it) } -> {
                finish("예매 성공! $target\n코레일 앱에서 결제기한 안에 결제하세요", success = true)
            }
            failed && closeBtn != null -> {
                clickRect(closeBtn.node, closeBtn.rect, win)
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
                clickRect(confirmBtn.node, confirmBtn.rect, win)
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
        lastActionAt = now()
        mustRefresh = false
        spinnerSeen = false
        setStatus(if (ok) "새로고침" else "새로고침 버튼을 못 찾음 (디버그 덤프 보내주세요)")
        go(State.LOAD_APPEAR)
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

    /** OCR로 찾은 위치면 그 자리의 가장 작은 클릭 가능한 노드(열차 줄 버튼 등)를 직접 클릭 */
    private fun clickRect(node: AccessibilityNodeInfo?, r: Rect, win: AccessibilityWindowInfo): Boolean {
        if (node != null) return clickNode(node, win)
        val x = r.centerX()
        val y = r.centerY()
        val target = a11yItems.filter { it.clickable && it.node != null && it.rect.contains(x, y) }
            .minByOrNull { it.rect.width().toLong() * it.rect.height() }
        val tn = target?.node
        if (tn != null) return clickNode(tn, win)
        return tap(x, y, win)
    }

    /** 노드 직접 클릭(알림 배너에 안 눌림). 안 되면 덮인 곳이 아닐 때만 좌표 탭 */
    private fun clickNode(node: AccessibilityNodeInfo, win: AccessibilityWindowInfo): Boolean {
        lastActionAt = now()
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
        lastActionAt = now()
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

    // ───────────── 로딩(빙글빙글) 감지 ─────────────

    /** 코레일 창 어딘가에 진행 표시(ProgressBar, Compose 로딩 인디케이터)가 보이면 true */
    private fun isLoading(): Boolean = try {
        windows.filter { it.root?.packageName?.toString()?.contains("korail", ignoreCase = true) == true }
            .any { w -> w.root?.let { hasProgress(it) } == true }
    } catch (e: Exception) { false }

    private fun hasProgress(root: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var n = 0
        while (stack.isNotEmpty() && n++ < 4000) {
            val x = stack.removeLast()
            if (x.isVisibleToUser) {
                val cls = x.className?.toString() ?: ""
                val desc = (x.contentDescription ?: x.text ?: "").toString()
                if (cls.contains("ProgressBar") || (x.rangeInfo != null && !cls.contains("SeekBar")) ||
                    desc.contains("로딩") || desc.contains("loading", ignoreCase = true) || desc.contains("잠시만")
                ) return true
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    // ───────────── OCR ─────────────

    /** 코레일 위에 떠 있는 다른 앱/시스템 창(알림 배너, 상태바, 우리 상태바) 영역 */
    private fun coveringRects(win: AccessibilityWindowInfo): List<Rect> = try {
        windows.filter { w ->
            w.layer > win.layer &&
                w.root?.packageName?.toString()?.contains("korail", ignoreCase = true) != true
        }.map { w -> Rect().also { w.getBoundsInScreen(it) } }
    } catch (e: Exception) { emptyList() }

    private fun requestOcr(win: AccessibilityWindowInfo) {
        if (Build.VERSION.SDK_INT < 30) {
            ocrError = "안드로이드 11 이상에서만 화면 인식이 돼요"
            return
        }
        val now = now()
        if (ocrBusy || now - lastShotReq < 340) return  // 안드로이드 캡처 최소 간격 ≈333ms
        ocrBusy = true
        lastShotReq = now
        val shotAt = now
        val covers = coveringRects(win)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        hw.close()
                    }
                    if (bmp == null) {
                        ocrError = "화면 캡처 변환 실패"
                        ocrBusy = false
                        return
                    }
                    val small = Bitmap.createScaledBitmap(
                        bmp, (bmp.width * OCR_SCALE).toInt(), (bmp.height * OCR_SCALE).toInt(), true,
                    )
                    if (small !== bmp) bmp.recycle()
                    fun up(r: Rect) = Rect(
                        (r.left / OCR_SCALE).toInt(), (r.top / OCR_SCALE).toInt(),
                        (r.right / OCR_SCALE).toInt(), (r.bottom / OCR_SCALE).toInt(),
                    )
                    recognizer.process(InputImage.fromBitmap(small, 0))
                        .addOnSuccessListener { text ->
                            val out = ArrayList<ScreenParser.Item>()
                            for (b in text.textBlocks) for (l in b.lines) {
                                l.boundingBox?.let { up(it) }?.let { r ->
                                    if (covers.none { it.contains(r.centerX(), r.centerY()) }) out.add(ScreenParser.Item(l.text, r, null, false))
                                }
                                for (e in l.elements) e.boundingBox?.let { up(it) }?.let { r ->
                                    if (covers.none { it.contains(r.centerX(), r.centerY()) }) out.add(ScreenParser.Item(e.text, r, null, false))
                                }
                            }
                            ocrItems = out
                            ocrShotAt = shotAt
                            ocrError = if (out.isEmpty()) "화면에서 글자를 못 읽음 (캡처가 막혔을 수 있음)" else ""
                            ocrBusy = false
                            small.recycle()
                            // 결과 나오자마자 바로 판단
                            handler.removeCallbacks(tick)
                            handler.post(tick)
                        }
                        .addOnFailureListener { e ->
                            ocrError = "글자 인식 실패: ${e.message}"
                            ocrBusy = false
                            small.recycle()
                        }
                }

                override fun onFailure(errorCode: Int) {
                    ocrError = "화면 캡처 실패 (코드 $errorCode)"
                    ocrBusy = false
                }
            })
        } catch (e: Exception) {
            ocrError = "화면 캡처 실패: ${e.message}"
            ocrBusy = false
        }
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
        sb.append("--- loading=${isLoading()}\n")
        sb.append("--- OCR (${ocrItems.size}개, 오류='$ocrError')\n")
        ocrItems.forEach { sb.append("'${it.text}' ${it.rect.toShortString()}\n") }
        val rows = ScreenParser.parseRows(ScreenParser.collect(root) + ocrItems, screenW, screenH)
        sb.append("--- 인식 결과\n")
        rows.forEach { r ->
            sb.append("${r.depText}: ").append(r.seats.joinToString { "${it.cls}=${it.status}(${if (it.available) "좌석" else "X"})" }).append('\n')
        }
        File(filesDir, "dump.txt").writeText(sb.toString())
        statusText = "화면 구조 저장 완료 (좌석잡이 앱에서 복사)"
    }
}
