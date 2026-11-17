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
     * 计算下一次触发时间（毫秒）。返回 -1 表示不再触发（仅一次且已过、或被跳过）。
     */
    fun computeNextTrigger(alarm: Alarm, now: Long = System.currentTimeMillis()): Long {
        val skip = alarm.skipTime
        return when {
            alarm.repeatType == Alarm.REPEAT_ONCE -> {
                val t = timeOn(alarm, baseDay(alarm, now))
                if (t > now && t > skip) t else -1L
            }

            alarm.repeatType == Alarm.REPEAT_DAILY || alarm.repeatType > 0 -> {
                val step = (if (alarm.repeatType == Alarm.REPEAT_DAILY) 1 else alarm.repeatType)
                    .toLong() * DAY
                var t = timeOn(alarm, baseDay(alarm, now))
                var guard = 0
                // 起始日期在过去时，向前推进到 now 之后的第一个触发点
                while ((t <= now || t <= skip) && guard++ < 20000) t += step
                if (t > now && t > skip) t else -1L
            }

            alarm.repeatType == Alarm.REPEAT_MONTHLY ->
                nextByCalendarUnit(alarm, now, skip, Calendar.MONTH, 26)

            alarm.repeatType == Alarm.REPEAT_YEARLY ->
                nextByCalendarUnit(alarm, now, skip, Calendar.YEAR, 8)

            alarm.repeatType == Alarm.REPEAT_WORKDAY -> nextWorkday(alarm, now, skip)

            alarm.repeatType == Alarm.REPEAT_CUSTOM -> nextCustom(alarm, now, skip)

            else -> -1L
        }
    }

    /** 起始基准日：间隔/每月/每年/仅一次都以它为数学位。 */
    private fun baseDay(alarm: Alarm, now: Long): Long =
        if (alarm.anchorTime > 0) alarm.anchorTime else now

    /**
     * 每月 / 每年：从当前月（年）对应号数开始，按单位逐个向后推进。
     * 小月没有该号数（如 31 号）时落到当月最后一天；2/29 在平年落到 2/28。
     */
    private fun nextByCalendarUnit(
        alarm: Alarm,
        now: Long,
        skip: Long,
        unit: Int,
        maxSteps: Int
    ): Long {
        val anchor = Calendar.getInstance().apply { timeInMillis = baseDay(alarm, now) }
        val day = anchor.get(Calendar.DAY_OF_MONTH)
        val month = anchor.get(Calendar.MONTH)
        val c = Calendar.getInstance().apply { timeInMillis = now }
        repeat(maxSteps) {
            if (unit == Calendar.YEAR) c.set(Calendar.MONTH, month)
            c.set(
                Calendar.DAY_OF_MONTH,
                day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH))
            )
            c.set(Calendar.HOUR_OF_DAY, alarm.hour)
            c.set(Calendar.MINUTE, alarm.minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val t = c.timeInMillis
            if (t > now && t > skip) return t
            c.add(unit, 1)
        }
        return -1L
    }

    /** 法定工作日：跳过周末与节假日，调休补班日照常响。 */
    private fun nextWorkday(alarm: Alarm, now: Long, skip: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = dayStartOf(now)
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 400 天足够覆盖连续休假（春节 + 国庆，或日历数据缺失年份）
        repeat(400) {
            val t = c.timeInMillis
            if (t > now && t > skip && WorkdayCalendar.isWorkDay(dayStartOf(t))) return t
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return -1L
    }

    /**
     * 自定义：命中选中星期的第一个未来触发点。
     * 最多看两周（15 天）：若跳过了唯一的那一天（如只选周日），
     * 下一次在 7 天后，窗口必须盖得住。
     */
    private fun nextCustom(alarm: Alarm, now: Long, skip: Long): Long {
        val mask = alarm.repeatDays and Alarm.WEEKDAY_MASK_ALL
        if (mask == 0) return -1L
        val c = Calendar.getInstance().apply {
            timeInMillis = dayStartOf(now)
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(15) {
            val t = c.timeInMillis
            if (t > now && t > skip &&
                mask and Alarm.weekdayBit(c.get(Calendar.DAY_OF_WEEK)) != 0
            ) return t
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return -1L
    }

    /** 指定日期那一天的 HH:mm 毫秒值。 */
    private fun timeOn(alarm: Alarm, dayMs: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = dayMs
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun dayStartOf(ms: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
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
            Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarm.id),
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

    // ---- 稍后提醒（延后再响）----

    private fun snoozePendingIntent(context: Context, alarmId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            SNOOZE_REQ_BASE + alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_FROM_SNOOZE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 安排一次「稍后提醒」：与正常排程用不同的 PendingIntent，
     * 所以延后再响不会顶掉明天照常响的那一条。
     */
    fun scheduleSnooze(context: Context, alarmId: Long, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = snoozePendingIntent(context, alarmId)
        val show = PendingIntent.getActivity(
            context,
            SNOOZE_REQ_BASE + alarmId.toInt() + 100000,
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

    fun cancelSnooze(context: Context, alarmId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(snoozePendingIntent(context, alarmId))
    }

    /** 取消单个闹钟（连同它的延后再响）。 */
    fun cancel(context: Context, alarm: Alarm) {
        cancelSnooze(context, alarm.id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(operation)
    }

    /** 重新安排所有启用的闹钟（用于开机、时间变更后）。 */
    fun rescheduleAll(context: Context, alarms: List<Alarm>) {
        alarms.filter { it.enabled }.forEach { schedule(context, it) }
    }

    const val EXTRA_ALARM_ID = "alarmId"

    /** 广播里的标记：true 表示这是「稍后提醒」的那一次，不再顺延正常排程。 */
    const val EXTRA_FROM_SNOOZE = "fromSnooze"

    private const val SNOOZE_REQ_BASE = 9000
}
