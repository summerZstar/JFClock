package com.example.jfclock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 核心闹钟时间计算测试（对应 docs/TEST_CASES.md A01–A06、A08 的可离线验证部分）。
 *
 * computeNextTrigger 只依赖 java.util.Calendar，可直接在 JVM 上运行。
 * 全部用例固定东八区；2026-09-25 是周五、09-26 周六、09-27 周日、09-29 周二、10-01 周四。
 */
class AlarmSchedulerComputeNextTest {

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

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    private fun dayStart(year: Int, month: Int, day: Int) = time(year, month, day, 0, 0)

    private fun alarm(
        hour: Int,
        minute: Int,
        repeatType: Int,
        anchorTime: Long,
        skipTime: Long = 0,
        repeatDays: Int = 0
    ) = Alarm(
        id = 1, hour = hour, minute = minute, enabled = true, label = "",
        repeatType = repeatType, anchorTime = anchorTime, vibrate = true, sound = true,
        createdAt = 0, skipTime = skipTime, repeatDays = repeatDays
    )

    /** 测试基准「现在」：2026-09-29（周二）10:00。 */
    private val now = time(2026, 9, 29, 10, 0)

    // ---- A01 仅一次 ----

    @Test
    fun `A01 仅一次 - 当天未到点返回当天时刻`() {
        val a = alarm(14, 30, Alarm.REPEAT_ONCE, dayStart(2026, 9, 29))
        assertEquals(time(2026, 9, 29, 14, 30), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A01 仅一次 - 时间已过不再触发`() {
        val a = alarm(8, 0, Alarm.REPEAT_ONCE, dayStart(2026, 9, 29))
        assertEquals(-1L, AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A01 仅一次 - 未来日期在那天触发`() {
        val a = alarm(8, 0, Alarm.REPEAT_ONCE, dayStart(2026, 10, 1))
        assertEquals(time(2026, 10, 1, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A01 仅一次 - 起始日早于今天不再触发`() {
        val a = alarm(14, 30, Alarm.REPEAT_ONCE, dayStart(2026, 9, 1))
        assertEquals(-1L, AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A08 跳过一次 - 仅一次被跳过则不再触发`() {
        val a = alarm(14, 30, Alarm.REPEAT_ONCE, dayStart(2026, 9, 29),
            skipTime = time(2026, 9, 29, 14, 30))
        assertEquals(-1L, AlarmScheduler.computeNextTrigger(a, now))
    }

    // ---- A02 每天 ----

    @Test
    fun `A02 每天 - 当天已过则明天触发`() {
        val a = alarm(7, 30, Alarm.REPEAT_DAILY, dayStart(2026, 9, 29))
        assertEquals(time(2026, 9, 30, 7, 30), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A02 每天 - 起始日在昨天也能找到今天的下一次`() {
        val a = alarm(23, 0, Alarm.REPEAT_DAILY, dayStart(2026, 9, 28))
        assertEquals(time(2026, 9, 29, 23, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A02 每天 - 起始日为空时从今天算`() {
        val a = alarm(23, 0, Alarm.REPEAT_DAILY, 0)
        assertEquals(time(2026, 9, 29, 23, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A08 跳过一次 - 每天跳过后顺延一天`() {
        val a = alarm(7, 30, Alarm.REPEAT_DAILY, dayStart(2026, 9, 29),
            skipTime = time(2026, 9, 30, 7, 30))
        assertEquals(time(2026, 10, 1, 7, 30), AlarmScheduler.computeNextTrigger(a, now))
    }

    // ---- A06 间隔 N 天 ----

    @Test
    fun `A06 间隔5天 - 与规格示例一致 9月1日起经9月11日后是9月16日`() {
        val a = alarm(8, 0, 5, dayStart(2026, 9, 1))
        assertEquals(time(2026, 9, 16, 8, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 11, 9, 0)))
    }

    @Test
    fun `A06 间隔1天 - 起始日在过去时推进到明天`() {
        val a = alarm(8, 0, 1, dayStart(2026, 9, 1))
        assertEquals(time(2026, 9, 30, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A06 间隔2天 - 起始日在过去保持奇数日序列`() {
        val a = alarm(8, 0, 2, dayStart(2026, 9, 1))
        assertEquals(time(2026, 10, 1, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A06 间隔 - 起始日为未来按起始日触发`() {
        val a = alarm(8, 0, 5, dayStart(2026, 10, 5))
        assertEquals(time(2026, 10, 5, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A06 间隔 - 起始日为今天且未到点当天触发`() {
        val a = alarm(23, 59, 3, dayStart(2026, 9, 29))
        assertEquals(time(2026, 9, 29, 23, 59), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A06 间隔5天 - 深夜视角跨午夜仍按数学序列`() {
        // 9/1 起每隔 5 天：…9/21、9/26、10/1。9/26 之后（仍在 9/26 当天 23:59）应为 10/1
        val a = alarm(0, 5, 5, dayStart(2026, 9, 1))
        assertEquals(time(2026, 10, 1, 0, 5),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 26, 23, 59)))
    }

    @Test
    fun `A08 跳过一次 - 间隔闹钟跳过后落到下个间隔点`() {
        val a = alarm(8, 0, 5, dayStart(2026, 9, 1), skipTime = time(2026, 10, 1, 8, 0))
        assertEquals(time(2026, 10, 6, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    // ---- A04 每月几号（月末规则）----

    @Test
    fun `A04 每月31号 - 1月中旬下一次是1月31日`() {
        val a = alarm(9, 0, Alarm.REPEAT_MONTHLY, dayStart(2026, 1, 31))
        assertEquals(time(2026, 1, 31, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 1, 15, 10, 0)))
    }

    @Test
    fun `A04 每月31号 - 2月无31日落到月末28日`() {
        val a = alarm(9, 0, Alarm.REPEAT_MONTHLY, dayStart(2026, 1, 31))
        assertEquals(time(2026, 2, 28, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 2, 1, 10, 0)))
    }

    @Test
    fun `A04 每月31号 - 闰年2月落到29日`() {
        val a = alarm(9, 0, Alarm.REPEAT_MONTHLY, dayStart(2026, 1, 31))
        assertEquals(time(2028, 2, 29, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2028, 2, 1, 10, 0)))
    }

    @Test
    fun `A04 每月31号 - 4月无31日落到30日`() {
        val a = alarm(9, 0, Alarm.REPEAT_MONTHLY, dayStart(2026, 1, 31))
        assertEquals(time(2026, 4, 30, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 4, 1, 10, 0)))
    }

    @Test
    fun `A04 每月31号 - 当月31日已过则下月取月末`() {
        val a = alarm(9, 0, Alarm.REPEAT_MONTHLY, dayStart(2026, 1, 31))
        assertEquals(time(2026, 2, 28, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 1, 31, 10, 0)))
    }

    @Test
    fun `A04 每月28号 - 日常号数每月固定同一天`() {
        val a = alarm(9, 0, Alarm.REPEAT_MONTHLY, dayStart(2026, 2, 28))
        assertEquals(time(2026, 3, 28, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 3, 1, 10, 0)))
    }

    // ---- A05 每年几月几日（2/29 规则）----

    @Test
    fun `A05 每年 - 普通日期在明年同日触发`() {
        val a = alarm(7, 0, Alarm.REPEAT_YEARLY, dayStart(2025, 5, 10))
        assertEquals(time(2026, 5, 10, 7, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 1, 1, 10, 0)))
    }

    @Test
    fun `A05 每年 - 2月29日在平年落到2月28日`() {
        val a = alarm(7, 0, Alarm.REPEAT_YEARLY, dayStart(2024, 2, 29))
        assertEquals(time(2026, 2, 28, 7, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 1, 1, 10, 0)))
    }

    @Test
    fun `A05 每年 - 2月29日在闰年正常触发`() {
        val a = alarm(7, 0, Alarm.REPEAT_YEARLY, dayStart(2024, 2, 29))
        assertEquals(time(2028, 2, 29, 7, 0),
            AlarmScheduler.computeNextTrigger(a, time(2028, 1, 1, 10, 0)))
    }

    @Test
    fun `A05 每年 - 当年已过推到明年`() {
        val a = alarm(7, 0, Alarm.REPEAT_YEARLY, dayStart(2025, 5, 10))
        assertEquals(time(2027, 5, 10, 7, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 6, 1, 10, 0)))
    }

    // ---- A03 工作日（本类不注入节假日数据，验证周一至周五回退规则）----

    @Test
    fun `A03 工作日 - 周五已过跳到周一`() {
        val a = alarm(17, 0, Alarm.REPEAT_WORKDAY, 0)
        assertEquals(time(2026, 9, 28, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 25, 18, 0)))
    }

    @Test
    fun `A03 工作日 - 周五未过当天触发`() {
        val a = alarm(17, 0, Alarm.REPEAT_WORKDAY, 0)
        assertEquals(time(2026, 9, 25, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 25, 10, 0)))
    }

    @Test
    fun `A03 工作日 - 周六跳到周一`() {
        val a = alarm(17, 0, Alarm.REPEAT_WORKDAY, 0)
        assertEquals(time(2026, 9, 28, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 26, 10, 0)))
    }

    @Test
    fun `A03 工作日 - 周日跳到周一`() {
        val a = alarm(17, 0, Alarm.REPEAT_WORKDAY, 0)
        assertEquals(time(2026, 9, 28, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 27, 10, 0)))
    }

    @Test
    fun `A03 工作日 - 周一已过跳到周二`() {
        val a = alarm(17, 0, Alarm.REPEAT_WORKDAY, 0)
        assertEquals(time(2026, 9, 29, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 28, 18, 0)))
    }

    @Test
    fun `A08 跳过一次 - 工作日跳过周一后用周二`() {
        val a = alarm(17, 0, Alarm.REPEAT_WORKDAY, 0, skipTime = time(2026, 9, 28, 17, 0))
        assertEquals(time(2026, 9, 29, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 25, 18, 0)))
    }

    // ---- A11 自定义周几（位 0 = 周一 … 位 6 = 周日）----

    /** 2026-09-29 是周二。 */
    private val customBase = dayStart(2026, 9, 29)

    private fun customMask(vararg weekdays: Int): Int =
        weekdays.fold(0) { acc, dow -> acc or Alarm.weekdayBit(dow) }

    @Test
    fun `自定义映射 - 周日是位6 周一是位0 周六是位5`() {
        assertEquals(1 shl 0, Alarm.weekdayBit(Calendar.MONDAY))
        assertEquals(1 shl 5, Alarm.weekdayBit(Calendar.SATURDAY))
        assertEquals(1 shl 6, Alarm.weekdayBit(Calendar.SUNDAY))
        assertEquals(Alarm.WEEKDAY_MASK_ALL,
            customMask(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY))
    }

    @Test
    fun `A11 自定义 - 只选周日从周二看是本周日`() {
        val a = alarm(9, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = customMask(Calendar.SUNDAY))
        assertEquals(time(2026, 10, 4, 9, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A11 自定义 - 选周一和周三命中更近的周三`() {
        val a = alarm(9, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = customMask(Calendar.MONDAY, Calendar.WEDNESDAY))
        assertEquals(time(2026, 9, 30, 9, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A11 自定义 - 当天（周二）已过顺延到下周二`() {
        val a = alarm(8, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = customMask(Calendar.TUESDAY))
        assertEquals(time(2026, 10, 6, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A11 自定义 - 当天（周二）未过当天响应`() {
        val a = alarm(23, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = customMask(Calendar.TUESDAY))
        assertEquals(time(2026, 9, 29, 23, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A11 自定义 - 工作日遮罩在周五晚间命中周一`() {
        val a = alarm(17, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = Alarm.WEEKDAY_MASK_WORKDAYS)
        assertEquals(time(2026, 9, 28, 17, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 25, 18, 0)))
    }

    @Test
    fun `A11 自定义 - 周末遮罩在周五晚间命中周六`() {
        val a = alarm(9, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = customMask(Calendar.SATURDAY, Calendar.SUNDAY))
        assertEquals(time(2026, 9, 26, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 25, 18, 0)))
    }

    @Test
    fun `A11 自定义 - 全部星期等同每天`() {
        val a = alarm(8, 0, Alarm.REPEAT_CUSTOM, customBase,
            repeatDays = Alarm.WEEKDAY_MASK_ALL)
        assertEquals(time(2026, 9, 30, 8, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A11 自定义 - 未选任何星期不触发`() {
        val a = alarm(8, 0, Alarm.REPEAT_CUSTOM, customBase, repeatDays = 0)
        assertEquals(-1L, AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A08 跳过一次 - 自定义跳过当天下一次`() {
        val a = alarm(17, 0, Alarm.REPEAT_CUSTOM, customBase,
            skipTime = time(2026, 9, 29, 17, 0),
            repeatDays = Alarm.WEEKDAY_MASK_WORKDAYS)
        assertEquals(time(2026, 9, 30, 17, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    @Test
    fun `A08 跳过一次 - 只选周日跳过后落到下周日（窗口需盖住两周）`() {
        val a = alarm(9, 0, Alarm.REPEAT_CUSTOM, customBase,
            skipTime = time(2026, 10, 4, 9, 0),
            repeatDays = customMask(Calendar.SUNDAY))
        assertEquals(time(2026, 10, 11, 9, 0), AlarmScheduler.computeNextTrigger(a, now))
    }

    // ---- 防御 ----

    @Test
    fun `未知重复类型返回不再触发`() {
        val a = alarm(8, 0, -99, dayStart(2026, 9, 29))
        assertEquals(-1L, AlarmScheduler.computeNextTrigger(a, now))
    }
}
