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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟触发广播。
 *
 * 响铃优先交给 [RingService]（前台服务能合法地后台拉起界面并持续播铃声）；
 * 服务被系统拒绝时才退回「有声通知 + 全屏意图」，用户点通知进入响铃界面。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        /** 全屏意图通道：高优先级但不发声，发声由服务负责，避免双份铃声。 */
        const val CHANNEL_ID = "jfclock_alarm_fsi"
        /** 服务起不来时用的有声通道。 */
        const val AUDIBLE_CHANNEL_ID = "jfclock_alarm_ring"
        private const val LEGACY_CHANNEL_ID = "jfclock_alarm"

        fun notifyReqCode(id: Long): Int = (id + 7).toInt()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("alarmId", -1)
        if (id == -1L) return

        val app = context.applicationContext as JFClockApp
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val alarm = try {
                app.repository.getById(id)
            } catch (_: Exception) {
                null
            }
            val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("alarmId", id)
            }
            val serviceOk = RingService.start(
                context,
                RingService.KIND_ALARM,
                id = id,
                sound = alarm?.sound != false,
                vibrate = alarm?.vibrate != false
            )
            if (!serviceOk) {
                try {
                    context.startActivity(ringIntent)
                } catch (_: Exception) {
                }
            }
            postNotification(context, id, ringIntent, audible = !serviceOk)

            try {
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

    private fun postNotification(
        context: Context,
        id: Long,
        ringIntent: Intent,
        audible: Boolean
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = if (audible) AUDIBLE_CHANNEL_ID else CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannels(nm)
        }

        val reqCode = notifyReqCode(id)
        val fullScreenPi = PendingIntent.getActivity(
            context,
            reqCode,
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle("闹钟")
            .setContentText("闹钟时间到了")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .setOngoing(true)
            // 全屏提醒未授权时（Android 14+ 会静默忽略全屏意图），点这条动作即可进入
            .addAction(R.drawable.ic_clock, context.getString(R.string.ring_open), fullScreenPi)
            .build()

        nm.notify(reqCode, notification)
    }

    private fun ensureChannels(nm: NotificationManager) {
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val fsi = NotificationChannel(
            CHANNEL_ID,
            "闹钟提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到点弹出响铃界面（声音由闹钟服务播放）"
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val audible = NotificationChannel(
            AUDIBLE_CHANNEL_ID,
            "闹钟响铃（兜底）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "后台无法启动响铃服务时，由通知直接发声"
            alarmSound?.let { setSound(it, audioAttributes) }
            vibrationPattern = longArrayOf(0, 600, 400)
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(fsi)
        nm.createNotificationChannel(audible)
    }
}
