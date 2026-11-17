package com.example.jfclock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 计时器「定时开始」的时间计算测试（TimerStore.nextOccurrence）。
 * 全部固定东八区；2026-09-29 是周二。
 */
class TimerScheduleTest {

    private var originalTz: TimeZone? = null

    @Before
    fun setUp() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, minute, second)
        return c.timeInMillis
    }

    @Test
    fun `今天的时刻还没过 - 定在今天`() {
        val now = time(2026, 9, 29, 22, 0)
        assertEquals(time(2026, 9, 29, 23, 0), TimerStore.nextOccurrence(now, 23, 0))
    }

    @Test
    fun `今天的时刻已过 - 定在明天`() {
        val now = time(2026, 9, 29, 22, 0)
        assertEquals(time(2026, 9, 30, 21, 30), TimerStore.nextOccurrence(now, 21, 30))
    }

    @Test
    fun `正好等于现在 - 算明天避免立即触发`() {
        val now = time(2026, 9, 29, 23, 0)
        assertEquals(time(2026, 9, 30, 23, 0), TimerStore.nextOccurrence(now, 23, 0))
    }

    @Test
    fun `跨零点 - 深夜选凌晨算第二天`() {
        val now = time(2026, 9, 29, 23, 0)
        assertEquals(time(2026, 9, 30, 0, 30), TimerStore.nextOccurrence(now, 0, 30))
    }

    @Test
    fun `当前时间的秒数不影响结果 - 秒被抹零`() {
        val now = time(2026, 9, 29, 22, 0, 37)
        assertEquals(time(2026, 9, 29, 23, 0), TimerStore.nextOccurrence(now, 23, 0))
    }

    @Test
    fun `月初跨到下月`() {
        val now = time(2026, 9, 30, 23, 0)
        assertEquals(time(2026, 10, 1, 7, 30), TimerStore.nextOccurrence(now, 7, 30))
    }

    // ---- 开始时刻已过：看「开始 + 时长」是否也过去了 ----

    @Test
    fun `开始已过但结束还没到 - 按当天算`() {
        val now = time(2026, 9, 29, 14, 0)
        // 10:00 开始、时长 8 小时 → 18:00 响，今天这轮还能触发
        assertEquals(
            time(2026, 9, 29, 10, 0),
            TimerStore.nextOccurrence(now, 10, 0, 8 * 3600_000L)
        )
    }

    @Test
    fun `开始和结束都过去了 - 顺延明天`() {
        val now = time(2026, 9, 29, 14, 0)
        assertEquals(
            time(2026, 9, 30, 10, 0),
            TimerStore.nextOccurrence(now, 10, 0, 3600_000L)
        )
    }

    @Test
    fun `结束正好是现在 - 顺延明天`() {
        val now = time(2026, 9, 29, 11, 0)
        assertEquals(
            time(2026, 9, 30, 10, 0),
            TimerStore.nextOccurrence(now, 10, 0, 3600_000L)
        )
    }

    @Test
    fun `不给时长 - 保持原来的顺延行为`() {
        val now = time(2026, 9, 29, 14, 0)
        assertEquals(time(2026, 9, 30, 10, 0), TimerStore.nextOccurrence(now, 10, 0))
    }
}
