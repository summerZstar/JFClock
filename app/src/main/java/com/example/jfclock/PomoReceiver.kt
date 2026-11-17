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
 * 番茄阶段结束广播：响铃 + 自动衔接下一阶段。
 *
 * 自动衔接（默认开）会立即把下一阶段排到 AlarmManager，所以人不在 App 里
 * 也能「工作 → 休息 → 工作」连续转下去；关掉时停在等待状态，由界面点开始。
 */
class PomoReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jfclock_pomo_fsi"
        const val AUDIBLE_CHANNEL_ID = "jfclock_pomo_ring"
        const val NOTIFY_ID = 53001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val finished = intent.getIntExtra(PomoStore.EXTRA_PHASE, PomoStore.PHASE_WORK)
        val next = PomoStore.nextPhase(context, finished)

        val label = context.getString(
            R.string.pomo_ring_label_fmt,
            PomoStore.minutesOf(context, finished),
            PomoStore.phaseName(context, finished)
        )
        val note = if (next == PomoStore.PHASE_WORK) {
            context.getString(R.string.pomo_ring_next_work, PomoStore.completedRounds(context) + 1)
        } else {
            context.getString(
                R.string.pomo_ring_next_break_fmt,
                PomoStore.minutesOf(context, next),
                PomoStore.phaseName(context, next)
            )
        }

        val ringIntent = Intent(context, PomoRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PomoStore.EXTRA_PHASE, finished)
            putExtra(PomoStore.EXTRA_LABEL, label)
            putExtra(PomoStore.EXTRA_NOTE, note)
        }
        val serviceOk = RingService.start(
            context, RingService.KIND_POMO, label = label, note = note
        )
        if (!serviceOk) {
            try {
                context.startActivity(ringIntent)
            } catch (_: Exception) {
            }
        }
        postNotification(context, label, note, ringIntent, audible = !serviceOk)

        if (PomoStore.autoNext(context)) {
            val total = PomoStore.minutesOf(context, next) * 60_000L
            val endAt = System.currentTimeMillis() + total
            PomoStore.start(context, next, endAt, total)
            PomoScheduler.schedule(context, endAt, next)
        } else {
            PomoStore.awaitUser(context, next)
        }
    }

    private fun postNotification(
        context: Context,
        label: String,
        note: String,
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
            .setContentTitle(note)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentPi, true)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_clock, context.getString(R.string.pomo_open_ring), contentPi)
            .build()

        nm.notify(NOTIFY_ID, notification)
    }

    private fun ensureChannels(context: Context, nm: NotificationManager) {
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val fsi = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pomo_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "阶段结束弹出响铃界面（声音由响铃服务播放）"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val audible = NotificationChannel(
            AUDIBLE_CHANNEL_ID,
            context.getString(R.string.pomo_channel_audible),
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
