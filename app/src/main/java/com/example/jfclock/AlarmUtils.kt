package com.example.jfclock

import android.graphics.Paint
import android.util.TypedValue
import android.widget.EditText
import android.widget.NumberPicker
import androidx.core.content.ContextCompat
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

/**
 * 统一 NumberPicker 样式：深色大号文字（部分 OEM 默认渲染成浅灰看不清）。
 */
fun styleNumberPicker(picker: NumberPicker) {
    val color = ContextCompat.getColor(picker.context, R.color.coloros_text_primary)
    val sizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 24f, picker.resources.displayMetrics
    )
    for (i in 0 until picker.childCount) {
        val child = picker.getChildAt(i)
        if (child is EditText) {
            child.setTextColor(color)
            child.textSize = 24f
        }
    }
    try {
        val f = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
        f.isAccessible = true
        (f.get(picker) as Paint).apply {
            this.color = color
            this.textSize = sizePx
        }
    } catch (_: Exception) {
        // 不同 ROM 字段名可能不同，失败则保持默认
    }
    picker.invalidate()
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
