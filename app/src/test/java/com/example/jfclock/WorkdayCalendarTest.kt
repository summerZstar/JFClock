package com.example.jfclock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/**
 * 法定工作日判定测试（对应 docs/TEST_CASES.md A03）。
 *
 * 走 Robolectric：init() 解析 JSON 需要 org.json，单例状态需要可重置。
 * 模拟 2026 年国庆：10/1–10/8 放假、10/10（周六）调休补班。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkdayCalendarTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var originalTz: TimeZone? = null

    @Before
    fun setUp() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        WorkdayCalendar.resetForTest()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
        WorkdayCalendar.resetForTest()
    }

    private fun dayStart(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    private fun seedPrefs(json: String) {
        // 键名与 WorkdayCalendar 内部一致：prefs 名 workday_calendar、数据键 data_<year>
        context.getSharedPreferences("workday_calendar", Context.MODE_PRIVATE)
            .edit().putString("data_2026", json).commit()
    }

    private fun nationalDay2026Json(): String = """
        {"days":[
            {"date":"2026-10-01","isHoliday":true},
            {"date":"2026-10-02","isHoliday":true},
            {"date":"2026-10-03","isHoliday":true},
            {"date":"2026-10-04","isHoliday":true},
            {"date":"2026-10-05","isHoliday":true},
            {"date":"2026-10-06","isHoliday":true},
            {"date":"2026-10-07","isHoliday":true},
            {"date":"2026-10-08","isHoliday":true},
            {"date":"2026-10-10","isHoliday":false}
        ]}
    """.trimIndent()

    private fun holiday2026(): Map<String, Boolean> = mapOf(
        "2026-10-01" to true, "2026-10-02" to true, "2026-10-03" to true,
        "2026-10-04" to true, "2026-10-05" to true, "2026-10-06" to true,
        "2026-10-07" to true, "2026-10-08" to true, "2026-10-10" to false
    )

    @Test
    fun `init - 从本地缓存解析节假日与调休补班`() {
        seedPrefs(nationalDay2026Json())
        WorkdayCalendar.init(context)

        assertTrue(WorkdayCalendar.hasDataFor(2026))
        // 10/1（周四）国庆放假
        assertFalse(WorkdayCalendar.isWorkDay(dayStart(2026, 10, 1)))
        // 10/9（周五）节后正常上班
        assertTrue(WorkdayCalendar.isWorkDay(dayStart(2026, 10, 9)))
        // 10/10 周六调休补班，照常上班
        assertTrue(WorkdayCalendar.isWorkDay(dayStart(2026, 10, 10)))
        // 10/11 周日、10/17 周六（无数据）仍按周末休
        assertFalse(WorkdayCalendar.isWorkDay(dayStart(2026, 10, 11)))
        assertFalse(WorkdayCalendar.isWorkDay(dayStart(2026, 10, 17)))
    }

    @Test
    fun `无数据 - 回退周一至周五`() {
        assertFalse(WorkdayCalendar.hasDataFor(2026))
        assertTrue(WorkdayCalendar.isWorkDay(dayStart(2026, 9, 25)))   // 周五
        assertFalse(WorkdayCalendar.isWorkDay(dayStart(2026, 9, 26)))  // 周六
        assertFalse(WorkdayCalendar.isWorkDay(dayStart(2026, 9, 27)))  // 周日
        assertTrue(WorkdayCalendar.isWorkDay(dayStart(2026, 9, 28)))   // 周一
    }

    @Test
    fun `computeNextTrigger - 工作日闹钟跳过国庆假期落到节后首个工作日`() {
        WorkdayCalendar.loadForTest(holiday2026(), setOf(2026))
        val a = alarm()

        // 9/30（周三）18:00 视角：10/1–10/8 放假，10/9（周五）是节后第一个工作日
        assertEquals(time(2026, 10, 9, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 30, 18, 0)))
    }

    @Test
    fun `computeNextTrigger - 跳过节后首个工作日后命中周六调休补班`() {
        WorkdayCalendar.loadForTest(holiday2026(), setOf(2026))
        val a = alarm(skipTime = time(2026, 10, 9, 9, 0))

        assertEquals(time(2026, 10, 10, 9, 0),
            AlarmScheduler.computeNextTrigger(a, time(2026, 9, 30, 18, 0)))
    }

    private fun alarm(skipTime: Long = 0) = Alarm(
        id = 1, hour = 9, minute = 0, enabled = true, label = "",
        repeatType = Alarm.REPEAT_WORKDAY, anchorTime = 0, vibrate = true, sound = true,
        createdAt = 0, skipTime = skipTime
    )
}
