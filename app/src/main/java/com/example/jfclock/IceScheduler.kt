package com.example.jfclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 冰浴计时调度：与煮蛋同样走 AlarmManager，切走/锁屏/杀进程都能准点响。
 *
 * requestCode 与煮蛋（5001/51001）错开，两个计时互不取消。
 */
object IceScheduler {

    private const val REQ_RING = 5021
    private const val REQ_SHOW = 51021

    /** triggerAt: 结束时刻（System.currentTimeMillis 基准）。 */
    fun schedule(context: Context, triggerAt: Long, label: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = PendingIntent.getBroadcast(
            context,
            REQ_RING,
            Intent(context, IceReceiver::class.java)
                .putExtra(IceReceiver.EXTRA_LABEL, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            context,
            REQ_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
        } catch (e: SecurityException) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (t: Throwable) {
            reportCrash(context, t)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(
            PendingIntent.getBroadcast(
                context,
                REQ_RING,
                Intent(context, IceReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
