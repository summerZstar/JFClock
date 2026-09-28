package com.example.jfclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 响铃前台服务：闹钟/煮蛋到点后由它负责「响 + 弹界面」。
 *
 * 为什么要前台服务：Android 10+ 禁止后台进程直接 startActivity，
 * 广播里那句 startActivity 在切到别的 App 时会被系统静默丢弃（只有通知能出）。
 * 前台服务是合法的豁免方，AOSP 时钟也是这么做的；同时它保证即使界面被 ROM
 * 拦住，铃声也已经在响。
 */
class RingService : Service() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var loopHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getIntExtra(EXTRA_KIND, KIND_ALARM) ?: KIND_ALARM
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val playSound = intent?.getBooleanExtra(EXTRA_SOUND, true) ?: true
        val doVibrate = intent?.getBooleanExtra(EXTRA_VIBRATE, true) ?: true

        ringing = true
        try {
            startForegroundCompat(kind)
        } catch (t: Throwable) {
            reportCrash(applicationContext, t)
        }
        if (playSound) playRingtone()
        if (doVibrate) startVibrate()
        launchRingUi(kind, id, label)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(kind: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ring_service_channel),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
        val target = when (kind) {
            KIND_EGG -> Intent(this, EggRingActivity::class.java)
            else -> Intent(this, AlarmRingActivity::class.java)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(getString(R.string.ring_service_title))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, FGS_NOTIFY_ID, target,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_ALARM
        } else 0
        ServiceCompat.startForeground(this, FGS_NOTIFY_ID, notification, type)
    }

    private fun launchRingUi(kind: Int, id: Long, label: String) {
        val intent = when (kind) {
            KIND_EGG -> Intent(this, EggRingActivity::class.java)
                .putExtra(EggReceiver.EXTRA_LABEL, label)
            else -> Intent(this, AlarmRingActivity::class.java).putExtra("alarmId", id)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun playRingtone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
            }
            play()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // 旧系统没有 loop 属性，定时重播近似循环
            loopHandler = Handler(Looper.getMainLooper()).apply {
                postDelayed(object : Runnable {
                    override fun run() {
                        ringtone?.play()
                        postDelayed(this, 3000)
                    }
                }, 3000)
            }
        }
    }

    private fun startVibrate() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val pattern = longArrayOf(0, 600, 400)
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        }
    }

    private fun stopFeedback() {
        loopHandler?.removeCallbacksAndMessages(null)
        loopHandler = null
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        stopFeedback()
        ringing = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "jfclock_ring_service"
        const val KIND_ALARM = 0
        const val KIND_EGG = 1
        private const val FGS_NOTIFY_ID = 49000

        private const val EXTRA_KIND = "ringKind"
        private const val EXTRA_ID = "ringId"
        private const val EXTRA_LABEL = "ringLabel"
        private const val EXTRA_SOUND = "ringSound"
        private const val EXTRA_VIBRATE = "ringVibrate"

        /** 服务是否正在响铃；响铃界面据此决定是否自己播（避免双份铃声）。 */
        @Volatile
        var ringing = false
            private set

        /** @return 是否成功请求到前台服务；false 表示被系统拒绝，调用方需自带铃声兜底。 */
        fun start(
            context: Context,
            kind: Int,
            id: Long = -1L,
            label: String = "",
            sound: Boolean = true,
            vibrate: Boolean = true
        ): Boolean {
            val intent = Intent(context, RingService::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SOUND, sound)
                .putExtra(EXTRA_VIBRATE, vibrate)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                // Android 12+ 后台启动前台服务受限，交给通知铃声兜底
                false
            }
        }

        fun stop(context: Context) {
            ringing = false
            context.stopService(Intent(context, RingService::class.java))
        }
    }
}
