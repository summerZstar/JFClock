package com.example.jfclock

import java.util.Calendar

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

/** 重复方式文案（ColorOS 风格）。 */
fun repeatText(alarm: Alarm): String = when {
    alarm.repeatType == -1 -> "仅一次"
    alarm.repeatType == 0 -> "每天"
    else -> "每 ${alarm.repeatType} 天"
}

/** 列表项第二行：重复方式 + 标签。 */
fun describeAlarm(alarm: Alarm): String {
    val parts = mutableListOf<String>()
    parts.add(repeatText(alarm))
    if (alarm.label.isNotBlank()) parts.add(alarm.label)
    return parts.joinToString(" · ")
}
