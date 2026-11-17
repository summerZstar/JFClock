package com.example.jfclock

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 状态栏图标深浅色跟随系统深浅色（部分 OEM 不响应主题属性，需代码强制）。 */
fun applyStatusBarAppearance(activity: Activity) {
    val night = activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        .isAppearanceLightStatusBars = !night
}

/** 当天 00:00 的毫秒值，作为「每天/间隔」计算的基准日期。 */
fun dayStartNow(): Long {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

/** 距离下次响铃的提示文案（毫秒差 -> 「X 小时 Y 分钟」）。 */
fun formatDurationUntil(ms: Long): String {
    val totalMin = (ms + 59999) / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}小时${m}分钟"
        m >= 1 -> "${m}分钟"
        else -> "不足1分钟"
    }
}

/**
 * 「稍后提醒」倒计时文案（毫秒差 -> 「X分Y秒」）。
 * 规格要求列表行显示到秒（如「还有 4分30秒」），与顶部分钟级倒计时分开。
 */
fun formatSnoozeCountdown(ms: Long): String {
    if (ms <= 0) return "0秒"
    val totalSec = (ms + 999) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}

/**
 * 精确时长文案（计时器响铃页、通知用）：15秒 -> 「15秒」，
 * 90秒 -> 「1分30秒」，5400秒 -> 「1小时30分钟」。
 * 顶部分钟级倒计时（formatDurationUntil）不要换成这个，两处语义不同。
 */
fun formatDurationPrecise(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600
    val m = totalSec % 3600 / 60
    val s = totalSec % 60
    return when {
        h > 0 -> if (m > 0) "${h}小时${m}分钟" else "${h}小时"
        m > 0 -> if (s > 0) "${m}分${s}秒" else "${m}分钟"
        else -> "${s}秒"
    }
}

/** 煮蛋时长文案（秒 -> 「X分Y秒」）。 */
fun formatEggDuration(sec: Int): String = when {
    sec % 60 == 0 -> "${sec / 60}分钟"
    sec < 60 -> "${sec}秒"
    else -> "${sec / 60}分${sec % 60}秒"
}

/** 计时器倒计时 / 预设时长：MM:SS，超过 1 小时带小时位（H:MM:SS）。 */
fun formatTimerClock(ms: Long): String {
    val sec = (ms + 999) / 1000
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

// ---- 闹钟卡片上的「下次响铃」行：全部按分钟级（或更粗）粒度生成，不参与逐秒刷新 ----

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

// 缓存 SimpleDateFormat：列表每秒 refresh 一次，避免反复 new（主线程单线程使用）
private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val monthDayTimeFmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
private val monthDayFmt = SimpleDateFormat("M月d日", Locale.getDefault())

/** HH:mm */
fun formatClockShort(ms: Long): String = clockFmt.format(Date(ms))

/** M月d日 HH:mm */
fun formatMonthDayTime(ms: Long): String = monthDayTimeFmt.format(Date(ms))

/** M月d日 */
fun formatMonthDay(ms: Long): String = monthDayFmt.format(Date(ms))

/**
 * 渲染粒度标记：同一标记内生成的文案必然一样。
 * 列表把它当 View 的 tag，只有标记变了才 setText，
 * 这样逐秒刷新时绝大多数行是「零写入」，连 SimpleDateFormat 都不会调用到。
 *  - 1 分钟内：文案恒定（即将响铃）
 *  - 1 天内（严格小于 24h）：剩余分钟数每变一次才重排
 *  - 更远（含刚好 24h）：文案与 now 无关，只跟触发时刻有关
 */
fun nextRingStamp(next: Long, now: Long): Long {
    val diff = next - now
    return when {
        diff <= MINUTE_MS -> -1L
        diff < DAY_MS -> diff / MINUTE_MS
        else -> next
    }
}

/**
 * 单个闹钟的「下次响铃」文案，与重复方式同一行显示，故意不做秒级：
 * 秒级读数刷不停、卡片上密密麻麻反而看不清，也不利于列表流畅。
 * 只给「今天/明天/某月某日」这一天级信息，具体时分看卡片左侧的大字。
 */
fun formatNextRingLine(context: Context, next: Long, now: Long): String {
    val diff = next - now
    return when {
        diff <= MINUTE_MS -> context.getString(R.string.alarm_next_imminent)
        dayDiff(next, now) == 0 -> context.getString(R.string.alarm_next_in_fmt, formatDurationUntil(diff))
        dayDiff(next, now) == 1 -> context.getString(R.string.alarm_next_tomorrow)
        else -> context.getString(R.string.alarm_next_at_fmt, formatMonthDay(next))
    }
}

/** 24 小时内的友好时间：今天 07:00 / 明天 07:00 / 10月15日 07:00（编辑页「仅一次」提示用）。 */
fun describeDayTime(context: Context, next: Long, now: Long): String {
    val time = formatClockShort(next)
    val dayDiff = dayDiff(next, now)
    return when (dayDiff) {
        0 -> context.getString(R.string.alarm_next_today_fmt, time)
        1 -> context.getString(R.string.alarm_next_tomorrow_fmt, time)
        else -> formatMonthDayTime(next)
    }
}

/** next 相对 now 跨了几个自然日（0=今天，1=明天），按本地时区折算。 */
private fun dayDiff(next: Long, now: Long): Int {
    val zone = TimeZone.getDefault()
    val dayA = ((next + zone.getOffset(next)) / DAY_MS).toInt()
    val dayB = ((now + zone.getOffset(now)) / DAY_MS).toInt()
    return dayA - dayB
}

/** 重复方式文案（ColorOS 风格）。 */
fun repeatText(alarm: Alarm): String =
    repeatSummaryText(alarm.repeatType, alarm.anchorTime, alarm.repeatDays)

/** 重复方式文案；列表第二行与编辑页的「重复」摘要行共用。 */
fun repeatSummaryText(repeatType: Int, anchorTime: Long, repeatDays: Int = 0): String {
    val anchor = Calendar.getInstance().apply { timeInMillis = anchorTime }
    return when {
        repeatType == Alarm.REPEAT_ONCE -> "仅一次"
        repeatType == Alarm.REPEAT_DAILY -> "每天"
        repeatType == Alarm.REPEAT_WORKDAY -> "工作日"
        repeatType == Alarm.REPEAT_CUSTOM -> customRepeatText(repeatDays)
        repeatType == Alarm.REPEAT_MONTHLY -> "每月 ${anchor.get(Calendar.DAY_OF_MONTH)} 号"
        repeatType == Alarm.REPEAT_YEARLY ->
            "每年 ${anchor.get(Calendar.MONTH) + 1}月${anchor.get(Calendar.DAY_OF_MONTH)}日"
        else -> "每 $repeatType 天"
    }
}

/**
 * 自定义周几文案：连续三天及以上压成「一至五」，其余逐个列出。
 * 示例：周一至五 / 周一、三、五 / 周六、日 / 每天（全选）/ 自定义（未选）。
 */
fun customRepeatText(repeatDays: Int): String {
    val mask = repeatDays and Alarm.WEEKDAY_MASK_ALL
    if (mask == Alarm.WEEKDAY_MASK_ALL) return "每天"
    if (mask == 0) return "自定义"
    val names = charArrayOf('一', '二', '三', '四', '五', '六', '日')
    val parts = mutableListOf<String>()
    var i = 0
    while (i < 7) {
        if (mask and (1 shl i) == 0) {
            i++
            continue
        }
        var j = i
        while (j + 1 < 7 && mask and (1 shl (j + 1)) != 0) j++
        if (j - i + 1 >= 3) {
            parts.add("${names[i]}至${names[j]}")
        } else {
            for (k in i..j) parts.add(names[k].toString())
        }
        i = j + 1
    }
    return "周" + parts.joinToString("、")
}

/** 列表项第二行：重复方式 + 标签 + 增强/特殊提醒标记。 */
fun describeAlarm(alarm: Alarm): String {
    val parts = mutableListOf<String>()
    parts.add(repeatText(alarm))
    if (alarm.label.isNotBlank()) parts.add(alarm.label)
    if (alarm.intenseAlert) parts.add("增强")
    return parts.joinToString(" · ")
}
