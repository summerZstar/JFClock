package com.example.jfclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * 闹钟调度器。
 *
 * 使用 AlarmManager.setAlarmClock()，它是系统为「闹钟类」应用提供的
 * 最可靠方式：即使处于 Doze 模式也会精准触发，并在状态栏显示闹钟信息，
 * 且无需申请 SCHEDULE_EXACT_ALARM 权限。
 *
 * 每次只安排「下一次」触发；触发后由 AlarmReceiver 再次安排后续触发，
 * 因此无需每天重新排程。
 */
object AlarmScheduler {

    private const val DAY = 24L * 60 * 60 * 1000

    /**
     * 计算下一次触发时间（毫秒）。返回 -1 表示已过期（仅一次闹钟且时间已过）。
     */
    fun computeNextTrigger(alarm: Alarm, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = alarm.anchorTime
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var trigger = cal.timeInMillis

        return when {
            alarm.repeatType == -1 -> if (trigger > now) trigger else -1
            alarm.repeatType == 0 -> {
                while (trigger <= now) trigger += DAY
                trigger
            }
            else -> {
                val interval = alarm.repeatType.toLong() * DAY
                while (trigger <= now) trigger += interval
                trigger
            }
        }
    }

    /** 为单个闹钟安排下一次触发；若关闭则取消。 */
    fun schedule(context: Context, alarm: Alarm) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarm.enabled) {
            cancel(context, alarm)
            return
        }
        val next = computeNextTrigger(alarm)
        if (next == -1L) return

        val operation = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            Intent(context, AlarmReceiver::class.java).putExtra("alarmId", alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context,
            alarm.id.toInt() + 100000,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(next, showIntent), operation)
    }

    /** 取消单个闹钟。 */
    fun cancel(context: Context, alarm: Alarm) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            Intent(context, AlarmReceiver::class.java).putExtra("alarmId", alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(operation)
    }

    /** 重新安排所有启用的闹钟（用于开机、时间变更后）。 */
    fun rescheduleAll(context: Context, alarms: List<Alarm>) {
        alarms.filter { it.enabled }.forEach { schedule(context, it) }
    }
}
