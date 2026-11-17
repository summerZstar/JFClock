package com.example.jfclock

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 闹钟实体。
 *
 * repeatType 含义：
 *  -1  -> 仅一次（在 anchorTime 当天响一次；编辑保存时若该时刻已过会顺延到最早可响的一天）
 *   0  -> 每天
 *  -2  -> 每月（anchorTime 的「几号」）
 *  -3  -> 每年（anchorTime 的「几月几号」）
 *  -4  -> 法定工作日（周一至周五，扣除节假日、加上调休补班日）
 *  -5  -> 自定义（每周 repeatDays 里选中的那几天）
 *  >0  -> 每隔 N 天，以 anchorTime 当天为第 0 天
 *
 * anchorTime 是「起始日期」：间隔从这天开始数，每月/每年取这天的号数与月日；
 * 仅一次则直接就是它响的那一天。存当天 00:00 的毫秒值。
 *
 * skipTime：被「仅某日关闭一次」跳过的触发时间（毫秒），0 表示不跳过。
 * snoozeUntil：「稍后提醒」的再响时刻（毫秒）。0 表示没有待响的延后。
 *   主界面据此在列表项下加一行「还有多久再响」，响过一次或停用后即清零。
 * intenseAlert：增强提醒——铃声渐强、震动加强，且需长按才能关闭。
 * specialNote：特殊提醒，响铃界面与通知上大字显示这句话（如「吃降压药」）。
 * repeatDays：自定义重复选中的星期位掩码，位 0 = 周一 … 位 6 = 周日，0 表示未选。
 *   只在 repeatType == REPEAT_CUSTOM 时有意义。
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
    val skipTime: Long = 0,
    val intenseAlert: Boolean = false,
    val specialNote: String = "",
    val snoozeUntil: Long = 0,
    val repeatDays: Int = 0
) {
    companion object {
        const val REPEAT_ONCE = -1
        const val REPEAT_DAILY = 0
        const val REPEAT_MONTHLY = -2
        const val REPEAT_YEARLY = -3
        const val REPEAT_WORKDAY = -4
        const val REPEAT_CUSTOM = -5

        /** repeatDays 全部七个星期位。 */
        const val WEEKDAY_MASK_ALL = 0x7F

        /** 周一至周五。 */
        const val WEEKDAY_MASK_WORKDAYS = 0x1F

        /** Calendar.DAY_OF_WEEK -> repeatDays 位（周一 = 位 0，周日 = 位 6）。 */
        fun weekdayBit(calendarDayOfWeek: Int): Int = 1 shl ((calendarDayOfWeek + 5) % 7)
    }
}
