package com.yeop.seatcatcher

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val svc = CatcherService.instance
            status.text = if (svc == null) "⚠️ 접근성 권한이 꺼져 있어요 (①번 먼저)"
            else (if (CatcherService.running) "▶ 실행 중\n" else "■ 정지\n") + CatcherService.statusText
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = col.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        })

        fun edit(hint: String, value: String, number: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
            col.addView(e)
            return e
        }

        fun button(t: String, on: () -> Unit) = col.addView(Button(this).apply {
            text = t
            setOnClickListener { on() }
        })

        col.addView(TextView(this).apply {
            text = "🚄 좌석잡이"
            textSize = 22f
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(status)

        label("날짜·역·시간은 코레일 앱에서 먼저 조회해두세요.\n아래 역 이름은 화면 확인용이에요(비워도 됨).")
        val from = edit("출발역 (예: 동대구)", prefs.fromStation)
        val to = edit("도착역 (예: 수서)", prefs.toStation)

        label("출발 시각 범위 (이 사이에 출발하는 열차만)")
        val start = edit("시작 (예: 09:00)", fmt(prefs.startMin))
        val end = edit("끝 (예: 11:00)", fmt(prefs.endMin))

        label("좌석 등급 (입석은 절대 안 누름)")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("일반실", "특실", "둘 다(일반실 우선)").forEachIndexed { i, n ->
            val rb = RadioButton(this)
            rb.text = n
            rb.id = 1000 + i
            rg.addView(rb)
        }
        rg.check(1000 + prefs.seatMode)
        col.addView(rg)

        label("매진 확인 후 쉬는 시간(초, 0 = 바로 새로고침)\n※ 새로고침은 로딩이 끝나고 화면을 다 읽은 뒤에만 해요")
        val interval = edit("1", prefs.intervalSec.toString(), number = true)
        label("최대 실행 시간(분)")
        val maxMin = edit("180", prefs.maxMinutes.toString(), number = true)

        fun save(): Boolean {
            val s = parseTime(start.text.toString())
            val e = parseTime(end.text.toString())
            if (s == null || e == null || e < s) {
                toast("시간은 09:00 형식, 끝 시각이 시작보다 같거나 늦어야 해요")
                return false
            }
            prefs.fromStation = from.text.toString().trim()
            prefs.toStation = to.text.toString().trim()
            prefs.startMin = s
            prefs.endMin = e
            prefs.seatMode = (rg.checkedRadioButtonId - 1000).coerceIn(0, 2)
            prefs.intervalSec = (interval.text.toString().toIntOrNull() ?: 1).coerceAtLeast(0)
            prefs.maxMinutes = (maxMin.text.toString().toIntOrNull() ?: 180).coerceIn(5, 720)
            return true
        }

        label("")
        button("① 접근성 권한 켜기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("설치된 앱 → 좌석잡이 → 사용")
        }
        button("② 알림 권한 허용") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else toast("이 기기는 따로 필요 없어요")
        }
        button("🔔 알림 테스트") {
            Notifier.notify(this, "알림 테스트", "성공하면 이렇게 알려드려요", prefs.korailPkg, true)
        }
        button("▶ 저장하고 시작 (코레일 앱으로 이동)") {
            if (!save()) return@button
            val svc = CatcherService.instance
            if (svc == null) {
                toast("접근성 권한부터 켜주세요")
                return@button
            }
            svc.startCatching()
            openKorail()
        }
        button("■ 중지") {
            CatcherService.instance?.stopCatching("사용자가 중지함")
        }

        label("\n— 인식이 안 될 때 (디버그) —")
        button("코레일 화면 구조 저장 (3초 뒤)") {
            val svc = CatcherService.instance ?: return@button toast("접근성 권한부터 켜주세요")
            svc.requestDump(3000)
            toast("코레일 조회 결과 화면을 띄워두세요")
            openKorail()
        }
        button("저장된 화면 구조 복사") {
            val f = File(filesDir, "dump.txt")
            if (!f.exists()) return@button toast("저장된 게 없어요")
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("dump", f.readText()))
            toast("복사됨 → Claude한테 붙여넣어 주세요")
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun openKorail() {
        val i = packageManager.getLaunchIntentForPackage(prefs.korailPkg)
        if (i != null) startActivity(i) else toast("코레일 앱을 직접 열어주세요")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun parseTime(s: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})\\s*[:시.]?\\s*(\\d{2})?\\s*분?\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mm = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || mm > 59) return null
        return h * 60 + mm
    }
}
