package com.example.jfclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 番茄钟调度：与闹钟/煮蛋同样走 AlarmManager，
 * 因此退出页面、切到别的 App、锁屏都能准点进入休息/工作阶段。
 */
object PomoScheduler {

    private const val REQ_RING = 5301
    private const val REQ_SHOW = 54001

    /** triggerAt：本阶段结束时刻（墙钟毫秒）；phase 为当前阶段。 */
    fun schedule(context: Context, triggerAt: Long, phase: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = PendingIntent.getBroadcast(
            context,
            REQ_RING,
            Intent(context, PomoReceiver::class.java).putExtra(PomoStore.EXTRA_PHASE, phase),
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
                Intent(context, PomoReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
