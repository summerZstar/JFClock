package com.example.jfclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟触发广播。
 *
 * 关键点：Android 10+ 不允许从后台广播直接 startActivity 拉起界面
 *（锁屏/后台时活动弹不出来）。正确做法是发送一个【全屏意图通知】，
 * 由系统在锁屏/前台之上自动拉起 AlarmRingActivity。
 *
 * 同时仍直接 startActivity 一次（配合 singleTask，前台时更及时）。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jfclock_alarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("alarmId", -1)
        if (id == -1L) return

        val app = context.applicationContext as JFClockApp

        // 拉起响铃界面
        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alarmId", id)
        }
        try {
            context.startActivity(ringIntent)
        } catch (_: Exception) {
        }

        // 发送全屏意图通知（后台/锁屏也能弹出）
        postFullScreenNotification(context, id, ringIntent)

        // goAsync 保持接收器存活，确保重排程在进程被杀前完成
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarm = app.repository.getById(id)
                if (alarm != null && alarm.enabled) {
                    if (alarm.repeatType == -1) {
                        app.repository.update(alarm.copy(enabled = false))
                    }
                    AlarmScheduler.schedule(context.applicationContext, alarm)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postFullScreenNotification(
        context: Context,
        id: Long,
        ringIntent: Intent
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "闹钟",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "闹钟响铃通知"
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        val reqCode = (id + 7).toInt()
        val fullScreenPi = PendingIntent.getActivity(
            context,
            reqCode,
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle("闹钟")
            .setContentText("闹钟时间到了")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .build()

        nm.notify(reqCode, notification)
    }
}
