package com.example.jfclock

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Paint
import android.util.TypedValue
import android.widget.EditText
import android.widget.NumberPicker
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.Calendar

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

/**
 * 统一 NumberPicker 样式：深色大号文字（部分 OEM 默认渲染成浅灰看不清）。
 */
fun styleNumberPicker(picker: NumberPicker) {
    val color = ContextCompat.getColor(picker.context, R.color.light_text_primary)
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
        // 遍历所有 Paint 字段，兼容不同 ROM 的字段命名（mSelectorWheelPaint 等）
        for (f in NumberPicker::class.java.declaredFields) {
            if (f.type == Paint::class.java) {
                f.isAccessible = true
                (f.get(picker) as? Paint)?.apply {
                    this.color = color
                    this.textSize = sizePx
                }
            }
        }
    } catch (_: Exception) {
        // 失败则保持默认
    }
    picker.invalidate()
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

/** 煮蛋时长文案（秒 -> 「X分Y秒」）。 */
fun formatEggDuration(sec: Int): String = when {
    sec % 60 == 0 -> "${sec / 60}分钟"
    sec < 60 -> "${sec}秒"
    else -> "${sec / 60}分${sec % 60}秒"
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
