package com.example.jfclock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 展示文案与时长格式化测试（纯 JVM）。 */
class AlarmUtilsFormatTest {

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

    private fun dayStart(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis
    }

    private fun alarm(
        repeatType: Int,
        anchorTime: Long = 0,
        label: String = "",
        intenseAlert: Boolean = false,
        repeatDays: Int = 0
    ) = Alarm(
        id = 1, hour = 8, minute = 0, enabled = true, label = label,
        repeatType = repeatType, anchorTime = anchorTime, vibrate = true, sound = true,
        createdAt = 0, intenseAlert = intenseAlert, repeatDays = repeatDays
    )

    @Test
    fun `格式化距今时长 - 毫秒向上取整到分钟`() {
        assertEquals("不足1分钟", formatDurationUntil(0))
        assertEquals("1分钟", formatDurationUntil(1))
        assertEquals("1分钟", formatDurationUntil(60_000))
        assertEquals("5分钟", formatDurationUntil(4 * 60_000 + 30_000))
        assertEquals("1小时0分钟", formatDurationUntil(60 * 60_000 - 1))
        assertEquals("2小时0分钟", formatDurationUntil(2 * 60 * 60_000))
        assertEquals("2小时3分钟", formatDurationUntil(2 * 60 * 60_000 + 3 * 60_000))
    }

    @Test
    fun `格式化煮蛋时长 - 整分钟秒数混合`() {
        assertEquals("5分钟", formatEggDuration(300))
        assertEquals("1分钟", formatEggDuration(60))
        assertEquals("45秒", formatEggDuration(45))
        assertEquals("1分30秒", formatEggDuration(90))
        assertEquals("5分5秒", formatEggDuration(305))
    }

    @Test
    fun `稍后提醒倒计时 - 规格要求的秒级格式`() {
        // 规格示例：还有 4分30秒（07:10）
        assertEquals("4分30秒", formatSnoozeCountdown(4 * 60_000 + 30_000))
        assertEquals("30秒", formatSnoozeCountdown(30_000))
        assertEquals("4分31秒", formatSnoozeCountdown(4 * 60_000 + 30_001)) // 向上取整到秒
        assertEquals("1分0秒", formatSnoozeCountdown(60_000))
        assertEquals("0秒", formatSnoozeCountdown(0))
        assertEquals("0秒", formatSnoozeCountdown(-1))
    }

    @Test
    fun `精确时长 - 计时器响铃页与通知用`() {
        // 回归：15 秒不能显示成「1分钟」
        assertEquals("15秒", formatDurationPrecise(15_000))
        assertEquals("45秒", formatDurationPrecise(45_000))
        assertEquals("59秒", formatDurationPrecise(59_000))
        assertEquals("1分钟", formatDurationPrecise(59_999)) // 59.999s 向上取整到秒即 1 分钟
        assertEquals("1分钟", formatDurationPrecise(60_000))
        assertEquals("1分30秒", formatDurationPrecise(90_000))
        assertEquals("19分钟", formatDurationPrecise(19 * 60_000))
        assertEquals("1小时", formatDurationPrecise(3_600_000))
        assertEquals("1小时30分钟", formatDurationPrecise(5_400_000))
    }

    @Test
    fun `下次响铃渲染标记 - 同一分钟内保持恒定`() {
        val now = dayStart(2026, 10, 11) + 8 * 3600_000 + 30_000L // 08:00:30
        val near = now + 30_000          // 30 秒后：应走「即将响铃」
        val soon = now + 5 * 60_000 + 20_000 // 5 分 20 秒后
        val far = now + 25 * 3600_000    // 25 小时后：只显示日期

        // 1 分钟内文案恒定，逐秒刷新不会再写 View
        assertEquals(nextRingStamp(near, now), nextRingStamp(near, now + 20_000))
        // 同一分钟内的多次采样标记一致
        assertEquals(nextRingStamp(soon, now), nextRingStamp(soon, now + 10_000))
        // 跨过一分钟才允许重排
        assertNotEquals(nextRingStamp(soon, now), nextRingStamp(soon, now + 60_000))
        // 超过 1 天：与当前时刻无关，永远同一个标记
        assertEquals(nextRingStamp(far, now), nextRingStamp(far, now + 3600_000))
    }

    @Test
    fun `重复方式文案 - 六种类型`() {
        assertEquals("仅一次", repeatText(alarm(Alarm.REPEAT_ONCE)))
        assertEquals("每天", repeatText(alarm(Alarm.REPEAT_DAILY)))
        assertEquals("工作日", repeatText(alarm(Alarm.REPEAT_WORKDAY)))
        assertEquals("每月 15 号", repeatText(alarm(Alarm.REPEAT_MONTHLY, dayStart(2026, 9, 15))))
        assertEquals("每年 5月20日", repeatText(alarm(Alarm.REPEAT_YEARLY, dayStart(2026, 5, 20))))
        assertEquals("每 3 天", repeatText(alarm(3)))
    }

    @Test
    fun `自定义周几文案 - 连续三天及以上压缩`() {
        assertEquals("周一", customRepeatText(1 shl 0))
        assertEquals("周一至五", customRepeatText(0b00011111))
        assertEquals("周五至日", customRepeatText(0b01110000))
        assertEquals("周一、三、五", customRepeatText((1 shl 0) or (1 shl 2) or (1 shl 4)))
        assertEquals("周一、日", customRepeatText((1 shl 0) or (1 shl 6)))
        // 周六、周日只连两天，不压成「六至日」
        assertEquals("周六、日", customRepeatText((1 shl 5) or (1 shl 6)))
        assertEquals("每天", customRepeatText(Alarm.WEEKDAY_MASK_ALL))
        assertEquals("自定义", customRepeatText(0))
    }

    @Test
    fun `重复方式文案 - 自定义走周几描述`() {
        assertEquals("周一、三、五",
            repeatText(alarm(Alarm.REPEAT_CUSTOM, repeatDays = (1 shl 0) or (1 shl 2) or (1 shl 4))))
        assertEquals("每天", repeatText(alarm(Alarm.REPEAT_CUSTOM, repeatDays = Alarm.WEEKDAY_MASK_ALL)))
    }

    @Test
    fun `列表描述 - 重复方式加标签与增强标记`() {
        assertEquals("每天", describeAlarm(alarm(Alarm.REPEAT_DAILY)))
        assertEquals("每天 · 吃药", describeAlarm(alarm(Alarm.REPEAT_DAILY, label = "吃药")))
        assertEquals("每天 · 吃药 · 增强",
            describeAlarm(alarm(Alarm.REPEAT_DAILY, label = "吃药", intenseAlert = true)))
    }
}
