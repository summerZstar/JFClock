package com.example.jfclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 计时进行中的常驻通知（对齐 ColorOS 计时器的顶部倒计时体验）。
 *
 * 状态栏顶部那颗胶囊是 ColorOS 系统级特性、没有公开 API；能做到的最近似是
 * 一条不发声的常驻通知：运行中用系统 Chronometer 倒数（Android 7+ 通知栏里
 * 自己每秒跳动，进程被杀也照走），暂停/定时等待显示静态文字。
 * 计时状态每次变化后调 [refresh]，由它决定显示内容或撤掉通知。
 */
object TimerCountdown {

    private const val CHANNEL_ID = "jfclock_timer_progress"
    const val NOTIFY_ID = 56002

    /** 计时状态变化后调用：按当前状态显示/更新/撤掉常驻通知。 */
    fun refresh(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        val state = TimerStore.state(context)
        if (state == TimerStore.STATE_IDLE) {
            nm.cancel(NOTIFY_ID)
            return
        }
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannel(context)

        val label = TimerStore.label(context)
        val title = label.ifBlank { context.getString(R.string.timer_title) }
        val now = System.currentTimeMillis()
        val endAt = TimerStore.endAt(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 1,
                    Intent(context, TimerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        when {
            TimerStore.pendingStart(context, now) -> {
                val at = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                    .format(Date(TimerStore.startAt(context)))
                builder.setContentText(context.getString(R.string.timer_notif_scheduled_fmt, at))
            }
            state == TimerStore.STATE_RUNNING && endAt > now &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                // 系统自带倒计时 Chronometer：无需进程保活，通知栏每秒自己走
                builder.setUsesChronometer(true)
                    .setWhen(endAt)
                    .setChronometerCountDown(true)
            }
            state == TimerStore.STATE_RUNNING -> {
                val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endAt))
                builder.setContentText(context.getString(R.string.timer_notif_end_at_fmt, at))
            }
            else -> {
                val remain = TimerStore.remaining(context, now)
                builder.setContentText(
                    context.getString(R.string.timer_notif_paused_fmt, formatDurationPrecise(remain))
                )
            }
        }
        try {
            nm.notify(NOTIFY_ID, builder.build())
        } catch (_: SecurityException) {
            // 通知权限被关：静默跳过，不影响计时与响铃
        }
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.timer_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "计时进行中的常驻倒计时，不发声不震动"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }
}
