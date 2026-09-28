package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences

/**
 * 煮蛋计时的跨进程状态（Activity / Receiver / 响铃页共享）。
 *
 * 用墙钟结束时刻保存，因此退出页面、切后台、杀进程后都能恢复剩余时间。
 */
object EggStore {

    private const val NAME = "egg_timer_prefs"
    private const val KEY_END_AT = "egg_end_at"
    private const val KEY_TOTAL = "egg_total_ms"
    private const val KEY_LABEL = "egg_label"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun save(context: Context, endAt: Long, totalMs: Long, label: String) {
        prefs(context).edit()
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_TOTAL, totalMs)
            .putString(KEY_LABEL, label)
            .apply()
    }

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

    /** 已结束但用户尚未确认的时刻；无则为 0。 */
    fun finishedAt(context: Context): Long {
        val end = prefs(context).getLong(KEY_END_AT, 0L)
        return if (end in 1..System.currentTimeMillis()) end else 0L
    }

    fun totalMs(context: Context): Long = prefs(context).getLong(KEY_TOTAL, 0L)
    fun label(context: Context): String = prefs(context).getString(KEY_LABEL, "") ?: ""
}
