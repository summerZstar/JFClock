package com.example.jfclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
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
 * 响铃前台服务：闹钟 / 煮蛋 / 冰浴 / 番茄钟 / 计时器到点后由它负责「响 + 弹界面」。
 *
 * 为什么要前台服务：Android 10+ 禁止后台进程直接 startActivity，
 * 广播里那句 startActivity 在切到别的 App 时会被系统静默丢弃（只有通知能出）。
 * 前台服务是合法的豁免方，AOSP 时钟也是这么做的；同时它保证即使界面被 ROM
 * 拦住，铃声也已经在响。
 *
 * 增强提醒（intense）改用 MediaPlayer：铃声 3 秒内从 15% 渐强到满音量、震动改长脉冲，
 * 并每分钟重新拉起一次响铃界面，避免界面被划掉后只剩静音。
 */
class RingService : Service() {

    private var ringtone: Ringtone? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var loopHandler: Handler? = null
    private var intense = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getIntExtra(EXTRA_KIND, KIND_ALARM) ?: KIND_ALARM
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val note = intent?.getStringExtra(EXTRA_NOTE).orEmpty()
        val playSound = intent?.getBooleanExtra(EXTRA_SOUND, true) ?: true
        val doVibrate = intent?.getBooleanExtra(EXTRA_VIBRATE, true) ?: true
        intense = intent?.getBooleanExtra(EXTRA_INTENSE, false) ?: false

        ringing = true
        try {
            startForegroundCompat(kind, id, label, note)
        } catch (t: Throwable) {
            reportCrash(applicationContext, t)
        }
        if (playSound) playRingtone()
        if (doVibrate) startVibrate()
        launchRingUi(kind, id, label, note)
        if (intense) keepUiAlive(kind, id, label, note)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(kind: Int, id: Long, label: String, note: String) {
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(note.ifBlank { getString(R.string.ring_service_title) })
            .setContentText(label.ifBlank { getString(R.string.ring_service_title) })
            .setContentIntent(
                PendingIntent.getActivity(
                    this, FGS_NOTIFY_ID, ringUiIntent(kind, id, label, note),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 起 startForeground 必须带类型；闹钟响铃按特殊用途申报
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, FGS_NOTIFY_ID, notification, type)
    }

    private fun ringUiIntent(kind: Int, id: Long, label: String, note: String): Intent {
        val intent = when (kind) {
            KIND_EGG -> Intent(this, EggRingActivity::class.java)
                .putExtra(EggReceiver.EXTRA_LABEL, label)
            KIND_POMO -> Intent(this, PomoRingActivity::class.java)
                .putExtra(PomoStore.EXTRA_LABEL, label)
                .putExtra(PomoStore.EXTRA_NOTE, note)
            KIND_TIMER -> Intent(this, TimerRingActivity::class.java)
                .putExtra(TimerStore.EXTRA_LABEL, label)
            KIND_ICE -> Intent(this, IceRingActivity::class.java)
                .putExtra(IceReceiver.EXTRA_LABEL, label)
            else -> Intent(this, AlarmRingActivity::class.java)
                .putExtra("alarmId", id)
                .putExtra(EXTRA_INTENSE, intense)
                .putExtra(EXTRA_NOTE, note)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return intent
    }

    private fun launchRingUi(kind: Int, id: Long, label: String, note: String) {
        try {
            startActivity(ringUiIntent(kind, id, label, note))
        } catch (_: Exception) {
            // 后台启动被 ROM 拦住：铃声仍在响，通知可点入
        }
    }

    /** 增强提醒：响铃界面被划掉也要继续把人叫回来。 */
    private fun keepUiAlive(kind: Int, id: Long, label: String, note: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!ringing) return
                launchRingUi(kind, id, label, note)
                handler.postDelayed(this, REPEAT_UI_MS)
            }
        }, REPEAT_UI_MS)
    }

    private fun playRingtone() {
        // 服务复用时（如煮蛋响完冰浴接着响）先停掉上一轮，避免两份铃声叠加
        stopFeedback()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        if (intense && playFadingIn(uri)) return
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioAttributes = alarmAudioAttributes()
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

    /** 增强提醒：Ringtone 没有音量接口，只有 MediaPlayer 能做渐强。 */
    private fun playFadingIn(uri: Uri): Boolean = try {
        player = MediaPlayer().apply {
            setAudioAttributes(alarmAudioAttributes())
            setDataSource(this@RingService, uri)
            isLooping = true
            setVolume(MIN_VOLUME, MIN_VOLUME)
            prepare()
            start()
        }
        val handler = Handler(Looper.getMainLooper())
        loopHandler = handler
        repeat(FADE_STEPS) { i ->
            val volume = MIN_VOLUME + (1f - MIN_VOLUME) * (i + 1) / FADE_STEPS
            handler.postDelayed({ player?.setVolume(volume, volume) }, FADE_STEP_MS * (i + 1))
        }
        true
    } catch (_: Exception) {
        releasePlayer()
        false
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
    }

    private fun alarmAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun startVibrate() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val pattern = if (intense) longArrayOf(0, 900, 250) else longArrayOf(0, 600, 400)
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
        releasePlayer()
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
        const val KIND_POMO = 2
        const val KIND_TIMER = 3
        const val KIND_ICE = 4
        private const val FGS_NOTIFY_ID = 49000
        private const val REPEAT_UI_MS = 60_000L
        private const val MIN_VOLUME = 0.15f
        private const val FADE_STEPS = 30
        private const val FADE_STEP_MS = 100L

        private const val EXTRA_KIND = "ringKind"
        private const val EXTRA_ID = "ringId"
        private const val EXTRA_SOUND = "ringSound"
        private const val EXTRA_VIBRATE = "ringVibrate"

        const val EXTRA_LABEL = "ringLabel"
        const val EXTRA_NOTE = "ringNote"
        const val EXTRA_INTENSE = "ringIntense"

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
            note: String = "",
            sound: Boolean = true,
            vibrate: Boolean = true,
            intense: Boolean = false
        ): Boolean {
            val intent = Intent(context, RingService::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_NOTE, note)
                .putExtra(EXTRA_SOUND, sound)
                .putExtra(EXTRA_VIBRATE, vibrate)
                .putExtra(EXTRA_INTENSE, intense)
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
