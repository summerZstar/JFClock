package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences

/**
 * 冰浴计时的状态：时长设置 + 是否接在煮蛋后面自动开始 + 运行中的结束时刻。
 *
 * 与煮蛋各用一个偏好文件，互不覆盖：煮蛋还在倒数时也能单独开一个冰浴。
 */
object IceStore {

    const val DEFAULT_SECONDS = 240      // 默认 4 分钟
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 15 * 60
    const val STEP_SECONDS = 30

    private const val NAME = "ice_prefs"
    private const val KEY_SECONDS = "ice_seconds"
    private const val KEY_AUTO = "ice_auto_after_egg"
    private const val KEY_END_AT = "ice_end_at"
    private const val KEY_TOTAL = "ice_total_ms"
    private const val KEY_LABEL = "ice_label"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun seconds(context: Context): Int =
        prefs(context).getInt(KEY_SECONDS, DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun setSeconds(context: Context, sec: Int) {
        prefs(context).edit()
            .putInt(KEY_SECONDS, sec.coerceIn(MIN_SECONDS, MAX_SECONDS))
            .apply()
    }

    /** 煮蛋到点自动开始冰浴（默认开：煮蛋捞出来就是要过冷水）。 */
    fun autoAfterEgg(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoAfterEgg(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun save(context: Context, endAt: Long, totalMs: Long, label: String) {
        prefs(context).edit()
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_TOTAL, totalMs)
            .putString(KEY_LABEL, label)
            .apply()
    }

    /** 只清运行状态，时长与开关设置保留。 */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_END_AT).remove(KEY_TOTAL).remove(KEY_LABEL)
            .apply()
    }

    /** 进行中的结束时刻；未运行或已结束时为 0。 */
    fun runningEndAt(context: Context): Long {
        val end = prefs(context).getLong(KEY_END_AT, 0L)
        return if (end > System.currentTimeMillis()) end else 0L
    }

    fun totalMs(context: Context): Long = prefs(context).getLong(KEY_TOTAL, 0L)
    fun label(context: Context): String = prefs(context).getString(KEY_LABEL, "") ?: ""

    /** 通知与响铃页上的一行说明，如「冰浴 4分钟」。 */
    fun labelFor(context: Context, sec: Int): String =
        "${context.getString(R.string.ice_title)} ${formatEggDuration(sec)}"

    /** 立即开始一轮冰浴：排程 + 记状态，返回结束时刻。 */
    fun startNow(context: Context, sec: Int = seconds(context)): Long {
        val endAt = System.currentTimeMillis() + sec * 1000L
        val label = labelFor(context, sec)
        save(context, endAt, sec * 1000L, label)
        IceScheduler.schedule(context, endAt, label)
        return endAt
    }
}
