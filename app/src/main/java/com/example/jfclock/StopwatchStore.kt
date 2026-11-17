package com.example.jfclock

import android.content.Context
import android.os.SystemClock

/**
 * 秒表的跨页面状态：累计时长 + 计次列表。
 *
 * 走 SystemClock.elapsedRealtime()（开机以来的毫秒数）而不是墙钟，
 * 所以熄屏、切 App、改系统时间都不会让读数跳变；界面被销毁后回来仍能接上。
 * 唯一例外是重启手机——elapsedRealtime 归零，此时按「已清零」处理。
 */
object StopwatchStore {

    private const val NAME = "stopwatch_prefs"
    private const val KEY_BASE = "sw_base_ms"
    private const val KEY_START_AT = "sw_start_at"
    private const val KEY_LAPS = "sw_laps"

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isRunning(context: Context) = prefs(context).getLong(KEY_START_AT, 0L) > 0

    /** 已停表期间累计的毫秒数。 */
    private fun baseMs(context: Context) = prefs(context).getLong(KEY_BASE, 0L)

    /** 当前这次走时的起点（elapsedRealtime），0 表示没在走。 */
    private fun startAt(context: Context) = prefs(context).getLong(KEY_START_AT, 0L)

    /** 总时长（毫秒）；开机后 elapsedRealtime 变小则视为已清零。 */
    fun elapsed(context: Context, now: Long = SystemClock.elapsedRealtime()): Long {
        val start = startAt(context)
        if (start == 0L) return baseMs(context)
        if (now < start) return 0L
        return baseMs(context) + (now - start)
    }

    fun laps(context: Context): List<Long> {
        val raw = prefs(context).getString(KEY_LAPS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun start(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        if (isRunning(context)) return
        prefs(context).edit().putLong(KEY_START_AT, now).apply()
    }

    /** 暂停：把这段走时并入累计值。 */
    fun pause(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        val start = startAt(context)
        if (start == 0L) return
        val add = if (now < start) 0L else now - start
        prefs(context).edit()
            .putLong(KEY_BASE, baseMs(context) + add)
            .putLong(KEY_START_AT, 0L)
            .apply()
    }

    /** 计次：记下此刻的累计时长，单圈由相邻两次相减得到。 */
    fun lap(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        val total = elapsed(context, now)
        val list = laps(context).toMutableList()
        list.add(total)
        prefs(context).edit()
            .putString(KEY_LAPS, list.joinToString(","))
            .apply()
    }

    fun reset(context: Context) {
        prefs(context).edit()
            .putLong(KEY_BASE, 0L)
            .putLong(KEY_START_AT, 0L)
            .remove(KEY_LAPS)
            .apply()
    }
}
