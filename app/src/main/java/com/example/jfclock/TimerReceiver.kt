package com.example.jfclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 计时器到点广播：交给 [RingService] 响铃并拉起全屏页。
 *
 * 服务起不来时退回「有声通知 + 全屏意图」，保证人不在 App 里也能被叫到。
 */
class TimerReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jfclock_timer_fsi"
        const val AUDIBLE_CHANNEL_ID = "jfclock_timer_ring"
        const val NOTIFY_ID = 56001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(TimerStore.EXTRA_LABEL).orEmpty()
            .ifBlank { TimerStore.label(context) }
        val title = context.getString(R.string.timer_ring_title)
        val text = label.ifBlank {
            context.getString(R.string.timer_ring_text_fmt, formatDurationPrecise(TimerStore.totalMs(context)))
        }

        TimerStore.fired(context)
        TimerScheduler.cancel(context)
        TimerCountdown.refresh(context) // 状态已归零：撤掉进行中的倒计时通知

        val ringIntent = Intent(context, TimerRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TimerStore.EXTRA_LABEL, label)
            putExtra(TimerStore.EXTRA_TOTAL_MS, TimerStore.totalMs(context))
        }
        val serviceOk = RingService.start(context, RingService.KIND_TIMER, label = label, note = title)
        if (!serviceOk) {
            try {
                context.startActivity(ringIntent)
            } catch (_: Exception) {
            }
        }
        postNotification(context, title, text, ringIntent, audible = !serviceOk)
    }

    private fun postNotification(
        context: Context,
        title: String,
        text: String,
        ringIntent: Intent,
        audible: Boolean
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = if (audible) AUDIBLE_CHANNEL_ID else CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannels(context, nm)

        val contentPi = PendingIntent.getActivity(
            context, NOTIFY_ID, ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentPi, true)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_clock, context.getString(R.string.timer_open), contentPi)
            .build()

        nm.notify(NOTIFY_ID, notification)
    }

    private fun ensureChannels(context: Context, nm: NotificationManager) {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.timer_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "计时结束弹出响铃界面（声音由响铃服务播放）"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                AUDIBLE_CHANNEL_ID,
                context.getString(R.string.timer_channel_audible),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "后台无法启动响铃服务时，由通知直接发声"
                alarmSound?.let { setSound(it, audioAttributes) }
                vibrationPattern = longArrayOf(0, 600, 400)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
