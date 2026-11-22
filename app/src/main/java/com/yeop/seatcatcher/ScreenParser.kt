package com.yeop.seatcatcher

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 코레일 조회 결과 화면의 접근성 트리를 읽어서
 * "열차(출발시각) → 일반실/특실 상태(가격이면 좌석 있음)"로 해석한다.
 */
object ScreenParser {

    data class Item(
        val text: String,
        val rect: Rect,
        val node: AccessibilityNodeInfo?,   // OCR로 읽은 글자는 null
        val clickable: Boolean,
    )

    data class Seat(
        val cls: String,          // "일반실" / "특실"
        val available: Boolean,   // 가격(…원)이 떠 있으면 true. 입석+좌석, 매진은 false
        val status: String,
        val node: AccessibilityNodeInfo?,
        val rect: Rect,
        val y: Int,
    )

    data class Row(val depMin: Int, val depText: String, val seats: List<Seat>)

    private val TIME = Regex("^(\\d{1,2}):(\\d{2})$")
    private val TIME_ANY = Regex("(\\d{1,2}):(\\d{2})")
    private val PRICE = Regex("^\\d{1,3}(,\\d{3})+원?$")
    private val MERGED_SEAT = Regex("(일반실|특실)\\s*([\\d,]+\\s*원|매진|입석\\s*\\+\\s*좌석|입석|예약대기)")
    private val REFRESH_WORDS = Regex("새로\\s*고침|refresh|reload|갱신", RegexOption.IGNORE_CASE)
    private val CLASSES = setOf("일반실", "특실")
    private val WS = Regex("\\s")

    fun norm(s: String): String = s.replace(WS, "")

    private fun toMin(h: String, m: String) = h.toInt() * 60 + m.toInt()

    /** 화면에 보이는 노드 전부 수집 */
    fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && out.size < 4000) {
            val n = stack.removeLast()
            if (n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val t = n.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: n.contentDescription?.toString()
                    ?: ""
                if (r.width() > 0 && r.height() > 0) out.add(Item(t, r, n, n.isClickable))
            }
            for (i in n.childCount - 1 downTo 0) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    fun parseRows(items: List<Item>, w: Int, h: Int): List<Row> {
        val structured = parseStructured(items, w, h)
        if (structured.any { it.seats.isNotEmpty() }) return structured
        return parseMerged(items)
    }

    /** 시간/일반실/가격이 각각 따로 된 노드일 때 (스크린샷 형태) */
    private fun parseStructured(items: List<Item>, w: Int, h: Int): List<Row> {
        val times = items.filter { TIME.matches(norm(it.text)) }
        if (times.isEmpty()) return emptyList()

        // 같은 줄(출발시각, 도착시각)끼리 묶고 왼쪽 것을 출발시각으로
        val lines = mutableListOf<MutableList<Item>>()
        for (t in times.sortedBy { it.rect.centerY() }) {
            val last = lines.lastOrNull()
            val tol = maxOf(t.rect.height() / 2, 15)
            if (last != null && abs(last[0].rect.centerY() - t.rect.centerY()) <= tol) last.add(t)
            else lines.add(mutableListOf(t))
        }
        val deps = lines.mapNotNull { line -> line.minByOrNull { it.rect.left } }
            .filter { it.rect.centerX() < w * 0.3 }
        if (deps.isEmpty()) return emptyList()

        val gaps = deps.map { it.rect.centerY() }.zipWithNext { a, b -> b - a }.sorted()
        val gap = if (gaps.isEmpty()) (h * 0.15).toInt() else gaps[gaps.size / 2]
        val right = items.filter { it.rect.centerX() > w * 0.45 && it.text.isNotBlank() }

        return deps.map { dep ->
            val cy = dep.rect.centerY()
            val band = right.filter { it.rect.centerY() >= cy - gap / 2 && it.rect.centerY() < cy + gap / 2 }
            val seats = mutableListOf<Seat>()
            for (lab in band) {
                val t = norm(lab.text)
                if (t in CLASSES) {
                    val lh = maxOf(lab.rect.height(), 30)
                    val st = band.filter { o ->
                        o !== lab && norm(o.text) !in CLASSES &&
                            abs(o.rect.centerY() - lab.rect.centerY()) < lh &&
                            o.rect.left >= lab.rect.right - 10
                    }.minByOrNull { it.rect.left }
                    val stText = st?.let { norm(it.text) } ?: ""
                    seats.add(Seat(t, PRICE.matches(stText), stText, st?.node ?: lab.node, st?.rect ?: lab.rect, lab.rect.centerY()))
                } else {
                    MERGED_SEAT.findAll(lab.text).forEach { m ->
                        seats.add(Seat(m.groupValues[1], PRICE.matches(norm(m.groupValues[2])), m.groupValues[2], lab.node, lab.rect, lab.rect.centerY()))
                    }
                }
            }
            val m = TIME.find(norm(dep.text))!!
            Row(
                toMin(m.groupValues[1], m.groupValues[2]),
                norm(dep.text),
                seats.sortedBy { abs(it.y - cy) }.distinctBy { it.cls },
            )
        }
    }

    /** 한 줄 전체가 노드 하나로 합쳐져 있을 때 (Compose 등) */
    private fun parseMerged(items: List<Item>): List<Row> = items.mapNotNull { it ->
        val tm = TIME_ANY.find(it.text) ?: return@mapNotNull null
        val seats = MERGED_SEAT.findAll(it.text).map { m ->
            Seat(m.groupValues[1], PRICE.matches(norm(m.groupValues[2])), m.groupValues[2], it.node, it.rect, it.rect.centerY())
        }.toList()
        if (seats.isEmpty()) null
        else Row(toMin(tm.groupValues[1], tm.groupValues[2]), tm.value, seats.distinctBy { s -> s.cls })
    }.distinctBy { it.depMin }

    /** 상단 새로고침 버튼 찾기 */
    fun findRefresh(items: List<Item>, w: Int, h: Int): AccessibilityNodeInfo? {
        val top = items.filter { it.rect.centerY() < h * 0.1 && it.node != null }
        top.firstOrNull { REFRESH_WORDS.containsMatchIn(it.text) }?.let { return it.node }
        // 라벨이 없으면: 오른쪽 위 아이콘들 중 장바구니(맨 오른쪽) 바로 왼쪽
        val icons = top.filter {
            it.clickable && it.rect.centerX() > w * 0.6 &&
                !norm(it.text).contains("수정") && it.rect.width() < w * 0.25
        }.distinctBy { it.rect.centerX() / 20 }
            .sortedByDescending { it.rect.right }
        return icons.getOrNull(1)?.node
    }

    fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count++ < 4000) {
            val n = stack.removeLast()
            if (n.isScrollable) {
                val r = Rect(); n.getBoundsInScreen(r)
                val a = r.width() * r.height()
                if (a > bestArea) { bestArea = a; best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    /** 디버그용: 화면 구조를 텍스트로 */
    fun dump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40 || sb.length > 400_000) return
            val r = Rect(); n.getBoundsInScreen(r)
            sb.append("  ".repeat(d))
                .append('[').append(n.className?.toString()?.substringAfterLast('.')).append("] ")
                .append("t='").append(n.text ?: "").append("' ")
                .append("d='").append(n.contentDescription ?: "").append("' ")
                .append("id=").append(n.viewIdResourceName ?: "-").append(' ')
                .append(if (n.isClickable) "C " else "")
                .append(if (n.isScrollable) "S " else "")
                .append(if (n.isVisibleToUser) "" else "(hidden) ")
                .append(r.toShortString()).append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }
}
