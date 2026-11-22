package com.yeop.seatcatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

object Notifier {
    private const val CH = "seat_alert_v1"
    private var nid = 100

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "좌석 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "좌석 예매 성공 / 중지 알림"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 1000)
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun notify(ctx: Context, title: String, text: String, korailPkg: String, success: Boolean) {
        ensureChannel(ctx)
        // 알림 누르면 코레일 앱으로 → 바로 결제
        val launch = ctx.packageManager.getLaunchIntentForPackage(korailPkg)
            ?: Intent(ctx, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, nid, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nid++, n)
        if (success) playAlarm(ctx)
    }

    private fun playAlarm(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx, uri) ?: return
            r.play()
            Handler(Looper.getMainLooper()).postDelayed({ r.stop() }, 8000)
        } catch (_: Exception) {
        }
    }
}
