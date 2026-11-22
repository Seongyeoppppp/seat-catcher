package com.yeop.seatcatcher

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("catcher", Context.MODE_PRIVATE)

    var fromStation: String
        get() = sp.getString("from", "") ?: ""
        set(v) = sp.edit().putString("from", v).apply()

    var toStation: String
        get() = sp.getString("to", "") ?: ""
        set(v) = sp.edit().putString("to", v).apply()

    /** 출발 시각 범위 (자정 기준 분) */
    var startMin: Int
        get() = sp.getInt("start", 9 * 60)
        set(v) = sp.edit().putInt("start", v).apply()

    var endMin: Int
        get() = sp.getInt("end", 11 * 60)
        set(v) = sp.edit().putInt("end", v).apply()

    /** 0 = 일반실, 1 = 특실, 2 = 둘 다(일반실 우선) */
    var seatMode: Int
        get() = sp.getInt("seat", 0)
        set(v) = sp.edit().putInt("seat", v).apply()

    var intervalSec: Int
        get() = sp.getInt("interval", 1)
        set(v) = sp.edit().putInt("interval", v).apply()

    var maxMinutes: Int
        get() = sp.getInt("maxmin", 180)
        set(v) = sp.edit().putInt("maxmin", v).apply()

    /** 실제 화면에서 감지되면 자동 갱신됨 */
    var korailPkg: String
        get() = sp.getString("pkg", "com.korail.talk") ?: "com.korail.talk"
        set(v) = sp.edit().putString("pkg", v).apply()
}
