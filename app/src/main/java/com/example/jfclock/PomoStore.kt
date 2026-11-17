package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences

/**
 * 番茄钟的跨进程状态与设置（界面 / 广播 / 响铃页共享）。
 *
 * 与煮蛋一样按墙钟结束时刻保存，所以切到别的 App、锁屏甚至杀进程后
 * 重新进来都能恢复；阶段序号（第几个番茄）也持久化，保证长休息按轮次出现。
 */
object PomoStore {

    const val PHASE_WORK = 0
    const val PHASE_SHORT = 1
    const val PHASE_LONG = 2

    const val STATE_IDLE = 0
    const val STATE_RUNNING = 1
    /** 一个阶段响完了，等用户点「开始下一阶段」 */
    const val STATE_WAIT = 2
    /** 手动暂停，剩余时间存在 KEY_REMAIN */
    const val STATE_PAUSED = 3

    const val EXTRA_PHASE = "pomoPhase"
    const val EXTRA_LABEL = "pomoLabel"
    const val EXTRA_NOTE = "pomoNote"

    private const val NAME = "pomo_timer_prefs"
    private const val KEY_STATE = "pomo_state"
    private const val KEY_PHASE = "pomo_phase"
    private const val KEY_END_AT = "pomo_end_at"
    private const val KEY_TOTAL = "pomo_total_ms"
    private const val KEY_ROUND = "pomo_round"
    private const val KEY_REMAIN = "pomo_remain_ms"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---- 设置 ----

    fun workMin(context: Context) = prefs(context).getInt("work_min", 25).coerceIn(1, 180)
    fun shortMin(context: Context) = prefs(context).getInt("short_min", 5).coerceIn(1, 60)
    fun longMin(context: Context) = prefs(context).getInt("long_min", 15).coerceIn(1, 120)
    fun rounds(context: Context) = prefs(context).getInt("rounds", 4).coerceIn(2, 12)
    fun autoNext(context: Context) = prefs(context).getBoolean("auto_next", true)

    fun saveSettings(
        context: Context,
        work: Int,
        short: Int,
        long: Int,
        rounds: Int,
        autoNext: Boolean
    ) {
        prefs(context).edit()
            .putInt("work_min", work)
            .putInt("short_min", short)
            .putInt("long_min", long)
            .putInt("rounds", rounds)
            .putBoolean("auto_next", autoNext)
            .apply()
    }

    fun minutesOf(context: Context, phase: Int): Int = when (phase) {
        PHASE_SHORT -> shortMin(context)
        PHASE_LONG -> longMin(context)
        else -> workMin(context)
    }

    fun phaseName(context: Context, phase: Int): String = when (phase) {
        PHASE_SHORT -> context.getString(R.string.pomo_short_break)
        PHASE_LONG -> context.getString(R.string.pomo_long_break)
        else -> context.getString(R.string.pomo_work)
    }

    // ---- 运行状态 ----

    fun state(context: Context) = prefs(context).getInt(KEY_STATE, STATE_IDLE)
    fun phase(context: Context) = prefs(context).getInt(KEY_PHASE, PHASE_WORK)
    fun endAt(context: Context) = prefs(context).getLong(KEY_END_AT, 0L)
    fun totalMs(context: Context) = prefs(context).getLong(KEY_TOTAL, 0L)
    fun remainMs(context: Context) = prefs(context).getLong(KEY_REMAIN, 0L)

    /** 已完成的番茄数；工作阶段进行中时，当前这个还没算完成。 */
    fun completedRounds(context: Context) = prefs(context).getInt(KEY_ROUND, 0)

    /** 正在进行的这是第几个番茄（休息阶段返回已完成的数量）。 */
    fun currentRound(context: Context): Int =
        if (phase(context) == PHASE_WORK && state(context) != STATE_IDLE) {
            completedRounds(context) + 1
        } else completedRounds(context)

    fun start(context: Context, phase: Int, endAt: Long, totalMs: Long) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_RUNNING)
            .putInt(KEY_PHASE, phase)
            .putLong(KEY_END_AT, endAt)
            .putLong(KEY_TOTAL, totalMs)
            .putLong(KEY_REMAIN, 0L)
            .apply()
    }

    /** 暂停：记下剩余时间，排程由调用方取消。 */
    fun pause(context: Context, remainingMs: Long) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_PAUSED)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_REMAIN, remainingMs.coerceAtLeast(0L))
            .apply()
    }

    /** 阶段到点且未开启自动衔接：记住「下一个是什么」，等用户点开始。 */
    fun awaitUser(context: Context, nextPhase: Int) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_WAIT)
            .putInt(KEY_PHASE, nextPhase)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_REMAIN, 0L)
            .putLong(KEY_TOTAL, minutesOf(context, nextPhase) * 60_000L)
            .apply()
    }

    /** 手动停下：轮次计数与运行信息一起清零。 */
    fun stop(context: Context) {
        prefs(context).edit()
            .putInt(KEY_STATE, STATE_IDLE)
            .putInt(KEY_PHASE, PHASE_WORK)
            .putLong(KEY_END_AT, 0L)
            .putLong(KEY_TOTAL, 0L)
            .putLong(KEY_REMAIN, 0L)
            .remove(KEY_ROUND)
            .apply()
    }

    /**
     * 一个阶段结束后的下一个阶段。会累加已完成番茄数，因此每 rounds 个
     * 番茄出现一次长休息。
     */
    fun nextPhase(context: Context, finished: Int): Int {
        if (finished != PHASE_WORK) return PHASE_WORK
        val done = completedRounds(context) + 1
        prefs(context).edit().putInt(KEY_ROUND, done).apply()
        return if (done % rounds(context) == 0) PHASE_LONG else PHASE_SHORT
    }
}
