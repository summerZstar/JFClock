package com.example.jfclock

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 计时器预设（常用计时器）。
 * 属于长期用户数据，按产品规格存 Room（v5 新增 timer_presets 表，v6 加定时开始字段，
 * v8 加排序字段）。
 */
@Entity(tableName = "timer_presets")
data class TimerPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 时长（秒）。 */
    val seconds: Int,
    /** 定时开始的「时」；-1 表示普通倒计时（填好就立即开始）。 */
    @ColumnInfo(defaultValue = "-1") val startHour: Int = -1,
    /** 定时开始的「分」，仅 startHour >= 0 时有意义。 */
    @ColumnInfo(defaultValue = "0") val startMinute: Int = 0,
    /** 列表里的显示顺序，拖动排序时改写。 */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
) {
    /** 是否属于「定时开始」类型。 */
    val scheduled: Boolean get() = startHour in 0..23
}
