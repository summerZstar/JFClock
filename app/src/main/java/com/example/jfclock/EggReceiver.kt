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
 * 煮蛋结束广播：优先交给 [RingService] 前台服务响铃并拉起界面，
 * 服务被拒时退回「有声通知 + 全屏意图」。
 */
class EggReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jfclock_egg_fsi"
        const val AUDIBLE_CHANNEL_ID = "jfclock_egg_ring"
        private const val LEGACY_CHANNEL_ID = "jfclock_egg"
        const val EXTRA_LABEL = "eggLabel"
        const val NOTIFY_ID = 52001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: context.getString(R.string.egg_done_title)

        val ringIntent = Intent(context, EggRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_LABEL, label)
        }
        val serviceOk = RingService.start(
            context, RingService.KIND_EGG, label = label
        )
        if (!serviceOk) {
            try {
                context.startActivity(ringIntent)
            } catch (_: Exception) {
            }
        }
        postNotification(context, label, ringIntent, audible = !serviceOk)
    }

    private fun postNotification(
        context: Context,
        label: String,
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
            .setContentTitle(context.getString(R.string.egg_done_title))
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentPi, true)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_clock, context.getString(R.string.egg_open_ring), contentPi)
            .build()

        nm.notify(NOTIFY_ID, notification)
    }

    private fun ensureChannels(context: Context, nm: NotificationManager) {
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val fsi = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.egg_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点弹出响铃界面（声音由响铃服务播放）"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val audible = NotificationChannel(
            AUDIBLE_CHANNEL_ID,
            context.getString(R.string.egg_channel_audible),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "后台无法启动响铃服务时，由通知直接发声"
            alarmSound?.let { setSound(it, audioAttributes) }
            vibrationPattern = longArrayOf(0, 600, 400)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(fsi)
        nm.createNotificationChannel(audible)
    }
}
