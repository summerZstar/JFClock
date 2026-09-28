package com.example.jfclock

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 闹钟实体。
 *
 * repeatType 含义：
 *  -1  -> 仅一次
 *   0  -> 每天
 *  >0  -> 每隔 N 天（固定间隔天数），以 anchorTime 当天为起点
 *
 * anchorTime 为「间隔/每天」计算的基准日期（当天 00:00 的毫秒值）。
 *
 * skipTime：被「仅某日关闭一次」跳过的触发时间（毫秒），0 表示不跳过。
 */
@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val label: String,
    val repeatType: Int,
    val anchorTime: Long,
    val vibrate: Boolean,
    val sound: Boolean,
    val createdAt: Long,
    val snoozeMinutes: Int = 5,
    val skipTime: Long = 0
)
