package com.example.jfclock

import android.app.NotificationManager
import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.jfclock.databinding.ActivityEggRingBinding

/**
 * 煮蛋完成响铃页：锁屏上显示并点亮屏幕，支持关闭 / 延长 1 分钟。
 */
class EggRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEggRingBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var label: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEggRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        label = intent.getStringExtra(EggReceiver.EXTRA_LABEL)
            ?: EggStore.label(this).ifBlank { getString(R.string.egg_done_title) }
        binding.tvLabel.text = label

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        startFeedback()

        binding.btnDismiss.setOnClickListener { dismiss() }
        binding.btnExtend.setOnClickListener { extendOneMinute() }
    }

    private fun startFeedback() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)
        ringtone?.play()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 600, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopFeedback() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun dismiss() {
        stopFeedback()
        EggStore.clear(this)
        getSystemService(NotificationManager::class.java).cancel(EggReceiver.NOTIFY_ID)
        finish()
    }

    /** 延长 1 分钟：重新排程并回到计时状态。 */
    private fun extendOneMinute() {
        stopFeedback()
        val endAt = System.currentTimeMillis() + 60_000L
        EggStore.save(this, endAt, 60_000L, label)
        EggScheduler.schedule(this, endAt, label)
        getSystemService(NotificationManager::class.java).cancel(EggReceiver.NOTIFY_ID)
        finish()
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }
}
