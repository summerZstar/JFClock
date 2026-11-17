package com.example.jfclock

import android.content.Intent
import android.app.NotificationManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityPomoRingBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 番茄阶段结束的响铃页：锁屏上显示并点亮屏幕。
 *
 * 「打开番茄钟」只停铃声、保留自动衔接好的下一阶段；
 * 「结束本轮」才会真正取消排程。
 */
class PomoRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPomoRingBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var ticker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPomoRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val finished = intent.getIntExtra(
            PomoStore.EXTRA_PHASE, PomoStore.phase(this)
        )
        binding.tvTitle.text = intent.getStringExtra(PomoStore.EXTRA_NOTE)
            ?: getString(R.string.pomo_ring_next_break_fmt, 5, getString(R.string.pomo_short_break))
        binding.tvLabel.text = intent.getStringExtra(PomoStore.EXTRA_LABEL)
            ?: getString(R.string.pomo_ring_label_fmt, PomoStore.minutesOf(this, finished),
                PomoStore.phaseName(this, finished))

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
        startTicker()

        binding.btnOpen.setOnClickListener { openTimer() }
        binding.btnStop.setOnClickListener { stopAll() }
    }

    /** 显示下一阶段还在跑多久，或提醒用户点开始。 */
    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (true) {
                binding.tvTip.text = when (PomoStore.state(this@PomoRingActivity)) {
                    PomoStore.STATE_RUNNING -> {
                        val remaining = (PomoStore.endAt(this@PomoRingActivity) -
                                System.currentTimeMillis()).coerceAtLeast(0)
                        getString(
                            R.string.pomo_next_running_fmt,
                            PomoStore.phaseName(this@PomoRingActivity, PomoStore.phase(this@PomoRingActivity)),
                            formatRemaining(remaining)
                        )
                    }
                    PomoStore.STATE_WAIT -> getString(R.string.pomo_next_waiting)
                    else -> getString(R.string.pomo_next_stopped)
                }
                delay(500)
            }
        }
    }

    private fun formatRemaining(ms: Long): String {
        val sec = (ms + 999) / 1000
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
    }

    private fun finishRinging() {
        stopFeedback()
        RingService.stop(this)
        getSystemService(NotificationManager::class.java).cancel(PomoReceiver.NOTIFY_ID)
    }

    /** 只关铃声，计时继续（自动衔接已经排好了下一阶段）。 */
    private fun openTimer() {
        finishRinging()
        startActivity(Intent(this, PomodoroActivity::class.java))
        finish()
    }

    private fun stopAll() {
        finishRinging()
        PomoScheduler.cancel(this)
        PomoStore.stop(this)
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
        ticker?.cancel()
        ticker = null
        stopFeedback()
        super.onDestroy()
    }
}
