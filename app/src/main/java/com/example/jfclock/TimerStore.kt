package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences

/**
 * 计时器（倒计时）的跨进程状态，界面 / 广播 / 响铃页共享。
 *
 * 与闹钟一样按墙钟结束时刻保存：切到别的 App、锁屏、进程被杀后回来，
 * 剩余时间仍然准确，到点也照样响。
 */
object TimerStore {

    const val STATE_IDLE = 0
    const val STATE_RUNNING = 1
    /** 手动暂停，剩余时间存在 KEY_REMAIN */
    const val STATE_PAUSED = 2

    const val EXTRA_LABEL = "timerLabel"
    const val EXTRA_TOTAL_MS = "timerTotalMs"

    private const val NAME = "timer_prefs"
    private const val KEY_STATE = "timer_state"
    private const val KEY_END_AT = "timer_end_at"
    private const val KEY_TOTAL = "timer_total_ms"
    private const val KEY_REMAIN = "timer_remain_ms"
    private const val KEY_LABEL = "timer_label"
    /** 定时开始的时刻（毫秒）；0 或已过去表示不是定时启动。 */
    private const val KEY_START_AT = "timer_start_at"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun state(context: Context) = prefs(context).getInt(KEY_STATE, STATE_IDLE)
    fun endAt(context: Context) = prefs(context).getLong(KEY_END_AT, 0L)
    fun totalMs(context: Context) = prefs(context).getLong(KEY_TOTAL, 0L)
    fun remainMs(context: Context) = prefs(context).getLong(KEY_REMAIN, 0L)
    fun label(context: Context) = prefs(context).getString(KEY_LABEL, "").orEmpty()
    fun startAt(context: Context) = prefs(context).getLong(KEY_START_AT, 0L)

    /** 剩余时间（毫秒）：按当前状态从墙钟推算，永不为负。 */
    fun remaining(context: Context, now: Long = System.currentTimeMillis()): Long =
        when (state(context)) {
            STATE_RUNNING -> (endAt(context) - now).coerceAtLeast(0L)
            STATE_PAUSED -> remainMs(context).coerceAtLeast(0L)
            else -> 0L
        }

    /** 是否处于「定时开始还没到」的等待阶段。 */
    fun pendingStart(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        state(context) == STATE_RUNNING && startAt(context) > now

    fun start(context: Context, totalMs: Long, endAt: Long, label: String, startAt: Long = 0L) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_RUNNING)
            .putLong(KEY_TOTAL, totalMs)
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_REMAIN, 0L)
            .putString(KEY_LABEL, label)
            .putLong(KEY_START_AT, startAt)
            .apply()
    }

    /** 继续：用记下的剩余时间重新算出结束时刻。 */
    fun resume(context: Context, now: Long = System.currentTimeMillis()): Long {
        val remaining = remainMs(context).coerceAtLeast(1_000L)
        val endAt = now + remaining
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_RUNNING)
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_REMAIN, 0L)
            .putLong(KEY_START_AT, 0L)
            .apply()
        return endAt
    }

    fun pause(context: Context, remainingMs: Long) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_PAUSED)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_REMAIN, remainingMs.coerceAtLeast(0L))
            .putLong(KEY_START_AT, 0L)
            .apply()
    }

    /** 清零后保留名称，方便下次直接改时长就用。 */
    fun reset(context: Context) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_IDLE)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_TOTAL, 0L)
            .putLong(KEY_REMAIN, 0L)
            .putLong(KEY_START_AT, 0L)
            .apply()
    }

    /**
     * 定时开始：把「今天的 HH:mm」解析成绝对时刻。
     *
     * 开始时刻已过时看结束时刻（开始 + [totalMs]）是否也过去了：
     * - 结束还在未来 → 这轮今天照样能触发，按当天算（不定到明天）；
     * - 结束也过了（或没给时长）→ 顺延到明天的同一时刻。
     *
     * 纯时间计算，不依赖 Android，可单元测试。
     */
    fun nextOccurrence(now: Long, hour: Int, minute: Int, totalMs: Long = 0L): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = now
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        c.set(java.util.Calendar.HOUR_OF_DAY, hour)
        c.set(java.util.Calendar.MINUTE, minute)
        if (c.timeInMillis <= now && c.timeInMillis + totalMs <= now) {
            c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    /**
     * 到点：状态归零，但把时长与名称留给响铃页的「再来一次」。
     */
    fun fired(context: Context) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_IDLE)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_REMAIN, 0L)
            .apply()
    }
}
