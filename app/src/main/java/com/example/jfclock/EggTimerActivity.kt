package com.example.jfclock

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.widget.NumberPicker
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityEggTimerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 煮蛋计时器：预设不同熟度的煮蛋时长，点击开始倒计时，结束后响铃提醒。
 */
class EggTimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEggTimerBinding
    private var job: Job? = null
    private var running = false
    private var totalMs = 0L
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEggTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.pickerMinutes.minValue = 1
        binding.pickerMinutes.maxValue = 30
        binding.pickerMinutes.value = 6
        binding.pickerMinutes.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        binding.pickerMinutes.wrapSelectorWheel = true

        binding.btnSoft.setOnClickListener { binding.pickerMinutes.value = 6 }
        binding.btnMedium.setOnClickListener { binding.pickerMinutes.value = 8 }
        binding.btnHard.setOnClickListener { binding.pickerMinutes.value = 10 }
        binding.pickerMinutes.setOnValueChangedListener { _, _, newVal ->
            if (!running) updateDisplay(newVal * 60_000L, newVal * 60_000L)
        }

        binding.btnStart.setOnClickListener { startTimer() }
        binding.btnStop.setOnClickListener { stopTimer(reset = true) }
    }

    private fun startTimer() {
        if (running) return
        stopFeedback()
        running = true
        totalMs = binding.pickerMinutes.value * 60_000L
        binding.tvStatus.text = getString(R.string.egg_timer)
        job = lifecycleScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                updateDisplay(remaining, totalMs)
                delay(250)
                remaining -= 250
            }
            updateDisplay(0, totalMs)
            onFinished()
        }
    }

    private fun stopTimer(reset: Boolean) {
        job?.cancel()
        job = null
        running = false
        stopFeedback()
        if (reset) {
            val total = binding.pickerMinutes.value * 60_000L
            updateDisplay(total, total)
            binding.tvStatus.text = getString(R.string.egg_custom)
        }
    }

    private fun updateDisplay(remainingMs: Long, total: Long) {
        val totalSec = (remainingMs.coerceAtLeast(0) + 999) / 1000
        binding.tvCountdown.text = String.format(
            Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60
        )
        binding.progress.progress = if (total > 0) {
            ((total - remainingMs) * 1000 / total).toInt().coerceIn(0, 1000)
        } else 0
    }

    private fun onFinished() {
        running = false
        job = null
        binding.tvStatus.text = getString(R.string.egg_done)
        // 响铃 + 震动提醒，点「停止」结束
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

    override fun onDestroy() {
        job?.cancel()
        stopFeedback()
        super.onDestroy()
    }
}
