package com.example.jfclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 煮蛋结束广播：拉起响铃页 + 发送全屏意图通知（后台/锁屏也能弹出）。
 */
class EggReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jfclock_egg"
        const val EXTRA_LABEL = "eggLabel"
        const val NOTIFY_ID = 52001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: context.getString(R.string.egg_done_title)

        val ringIntent = Intent(context, EggRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_LABEL, label)
        }
        try {
            context.startActivity(ringIntent)
        } catch (_: Exception) {
        }
        postNotification(context, label, ringIntent)
    }

    private fun postNotification(context: Context, label: String, ringIntent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.egg_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.egg_channel_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        val contentPi = PendingIntent.getActivity(
            context, NOTIFY_ID + 1, ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(context.getString(R.string.egg_done_title))
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentPi, true)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFY_ID, notification)
    }
}
