package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityEggTimerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 煮蛋计时器：预设不同熟度的煮蛋时长（长按预设可修改默认时间），
 * 点击开始倒计时，结束后响铃提醒。进入页面默认选中溏心蛋。
 */
class EggTimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEggTimerBinding
    private var job: Job? = null
    private var running = false
    private var totalMs = 0L
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private lateinit var prefs: SharedPreferences
    private var softMin = 6
    private var mediumMin = 8
    private var hardMin = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEggTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        prefs = getSharedPreferences("egg_timer_prefs", Context.MODE_PRIVATE)
        softMin = prefs.getInt("soft", 6)
        mediumMin = prefs.getInt("medium", 8)
        hardMin = prefs.getInt("hard", 10)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.pickerMinutes.minValue = 1
        binding.pickerMinutes.maxValue = 30
        binding.pickerMinutes.value = softMin
        binding.pickerMinutes.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        binding.pickerMinutes.wrapSelectorWheel = true
        styleNumberPicker(binding.pickerMinutes)

        binding.btnSoft.setOnClickListener { binding.pickerMinutes.value = softMin }
        binding.btnMedium.setOnClickListener { binding.pickerMinutes.value = mediumMin }
        binding.btnHard.setOnClickListener { binding.pickerMinutes.value = hardMin }

        // 长按预设可修改它的默认时间（持久化保存）
        binding.btnSoft.setOnLongClickListener { editPreset("soft", it as TextView); true }
        binding.btnMedium.setOnLongClickListener { editPreset("medium", it as TextView); true }
        binding.btnHard.setOnLongClickListener { editPreset("hard", it as TextView); true }

        // 进入页面默认选中溏心蛋；拨动滚轮时同步高亮对应预设
        updatePresetHighlight(softMin)
        binding.pickerMinutes.setOnValueChangedListener { _, _, newVal ->
            updatePresetHighlight(newVal)
            if (!running) updateDisplay(newVal * 60_000L, newVal * 60_000L)
        }

        binding.btnStart.setOnClickListener { startTimer() }
        binding.btnStop.setOnClickListener { stopTimer(reset = true) }
    }

    /** 高亮与当前分钟数一致的预设按钮：实心白字 = 选中，描边 = 未选中。 */
    private fun updatePresetHighlight(minutes: Int) {
        val accent = ContextCompat.getColor(this, R.color.coloros_accent)
        val selected = mapOf(
            binding.btnSoft to (minutes == softMin),
            binding.btnMedium to (minutes == mediumMin),
            binding.btnHard to (minutes == hardMin)
        )
        for ((btn, isSelected) in selected) {
            if (isSelected) {
                btn.backgroundTintList = ColorStateList.valueOf(accent)
                btn.setTextColor(Color.WHITE)
                btn.strokeColor = ColorStateList.valueOf(accent)
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(accent)
                btn.strokeColor = ColorStateList.valueOf(accent)
            }
        }
    }

    /** 长按预设：弹窗修改该类型的默认分钟数并持久化。 */
    private fun editPreset(key: String, btn: TextView) {
        val current = when (key) {
            "soft" -> softMin
            "medium" -> mediumMin
            else -> hardMin
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 0)
        }
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 30
            value = current
            wrapSelectorWheel = true
        }
        container.addView(picker)
        container.addView(TextView(this).apply {
            text = getString(R.string.unit_minutes)
            textSize = 16f
            setPadding(24, 0, 0, 0)
        })
        styleNumberPicker(picker)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.egg_edit_preset, btn.text))
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val v = picker.value
                prefs.edit().putInt(key, v).apply()
                when (key) {
                    "soft" -> softMin = v
                    "medium" -> mediumMin = v
                    else -> hardMin = v
                }
                binding.pickerMinutes.value = v
                updatePresetHighlight(v)
                if (!running) updateDisplay(v * 60_000L, v * 60_000L)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
