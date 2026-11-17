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
import com.example.jfclock.databinding.ActivityTimerRingBinding

/**
 * 计时结束的响铃页：锁屏上显示并点亮屏幕。
 *
 * 「停止」结束本次计时；「再来一次」按原时长立刻重新倒数。
 */
class TimerRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimerRingBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimerRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val total = intent.getLongExtra(TimerStore.EXTRA_TOTAL_MS, 0L)
            .coerceAtLeast(TimerStore.totalMs(this))
        val name = intent.getStringExtra(TimerStore.EXTRA_LABEL).orEmpty()
            .ifBlank { TimerStore.label(this) }

        binding.tvLabel.text = name.ifBlank {
            getString(R.string.timer_ring_text_fmt, formatDurationPrecise(total))
        }
        binding.tvTip.text = if (total > 0) {
            getString(R.string.timer_ring_repeat_fmt, formatDurationPrecise(total))
        } else {
            getString(R.string.timer_ring_stop_only)
        }

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

        if (!RingService.ringing) startFeedback()

        binding.btnStop.setOnClickListener { stopAll() }
        binding.btnAgain.setOnClickListener { runAgain(total, name) }
    }

    private fun finishRinging() {
        stopFeedback()
        RingService.stop(this)
        getSystemService(NotificationManager::class.java).cancel(TimerReceiver.NOTIFY_ID)
    }

    private fun stopAll() {
        finishRinging()
        TimerScheduler.cancel(this)
        TimerStore.reset(this)
        TimerCountdown.refresh(this)
        finish()
    }

    /** 再来一次：按刚才的时长立刻倒数，页面关掉也照样准点响。 */
    private fun runAgain(totalMs: Long, name: String) {
        finishRinging()
        if (totalMs > 0) {
            val endAt = System.currentTimeMillis() + totalMs
            TimerStore.start(this, totalMs, endAt, name)
            TimerScheduler.schedule(this, endAt, name)
            android.widget.Toast.makeText(
                this, getString(R.string.timer_again_fmt, formatDurationPrecise(totalMs)),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        TimerCountdown.refresh(this)
        finish()
    }

    private fun startFeedback() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)
        ringtone?.play()

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
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }
}
