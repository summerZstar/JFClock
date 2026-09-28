package com.example.jfclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 闹钟调度器。
 *
 * 首选 AlarmManager.setAlarmClock()（闹钟类应用最可靠方式，Doze 下精准触发）。
 * 某些 OEM（如 ColorOS）会拒绝授予精确闹钟权限，此时逐级降级：
 * setAlarmClock -> setExactAndAllowWhileIdle -> setAndAllowWhileIdle，
 * 保证闹钟仍能触发（无权限时系统可能在 Doze 下有几分钟延迟）。
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
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next, showIntent), operation)
        } catch (e: SecurityException) {
            // OEM 未授予精确闹钟权限：降级为 setExactAndAllowWhileIdle
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
            } catch (e2: SecurityException) {
                // 仍无权限：降级为非精确闹钟，保证不崩溃、能响铃（可能略有延迟）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
            }
        } catch (t: Throwable) {
            // 其它系统级异常：记录堆栈，避免拖垮 App
            reportCrash(context, t)
        }
    }

    /**
     * 当前应用是否可调度精确闹钟。
     * API < 31 时无需该权限，视为可用。
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
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
