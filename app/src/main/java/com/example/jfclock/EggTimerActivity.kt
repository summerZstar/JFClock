package com.example.jfclock

import android.content.Context
import android.content.SharedPreferences
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityEggTimerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 煮蛋计时器：预设不同熟度的煮蛋时长（点右上角设置图标可修改，支持分+秒），
 * 点预设或拨滚轮选中后开始倒计时，结束后响铃提醒。进入页面默认选中溏心蛋。
 */
class EggTimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEggTimerBinding
    private var job: Job? = null
    private var running = false
    private var totalMs = 0L
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private lateinit var prefs: SharedPreferences
    private var softSec = 360
    private var mediumSec = 480
    private var hardSec = 600
    private var curSeconds = 360L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEggTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        prefs = getSharedPreferences("egg_timer_prefs", Context.MODE_PRIVATE)
        loadPresets()
        curSeconds = softSec.toLong()

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.pickerMinutes.minValue = 1
        binding.pickerMinutes.maxValue = 30
        binding.pickerMinutes.value = (curSeconds / 60).toInt().coerceIn(1, 30)
        binding.pickerMinutes.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        binding.pickerMinutes.wrapSelectorWheel = true
        styleNumberPicker(binding.pickerMinutes)

        binding.btnSoft.setOnClickListener { selectPreset(softSec, R.string.egg_soft) }
        binding.btnMedium.setOnClickListener { selectPreset(mediumSec, R.string.egg_medium) }
        binding.btnHard.setOnClickListener { selectPreset(hardSec, R.string.egg_hard) }
        binding.btnEditPresets.setOnClickListener { showPresetSettings() }

        // 拨滚轮 = 自定义，取消预设选中态
        binding.pickerMinutes.setOnValueChangedListener { _, _, newVal ->
            if (!running) {
                curSeconds = newVal * 60L
                uncheckAll()
                binding.tvStatus.text = getString(R.string.egg_custom)
                updateDisplay(curSeconds * 1000, curSeconds * 1000)
            }
        }

        binding.tvStatus.text = getString(R.string.egg_soft)
        selectPreset(softSec, R.string.egg_soft)

        binding.btnStart.setOnClickListener { startTimer() }
        binding.btnStop.setOnClickListener { stopTimer(reset = true) }
    }

    private fun loadPresets() {
        softSec = prefs.getInt("soft_s", 360)
        mediumSec = prefs.getInt("medium_s", 480)
        hardSec = prefs.getInt("hard_s", 600)
    }

    private fun selectPreset(seconds: Int, nameRes: Int) {
        curSeconds = seconds.toLong()
        binding.btnSoft.isChecked = nameRes == R.string.egg_soft
        binding.btnMedium.isChecked = nameRes == R.string.egg_medium
        binding.btnHard.isChecked = nameRes == R.string.egg_hard
        binding.tvStatus.text = getString(nameRes)
        if (!running) updateDisplay(curSeconds * 1000, curSeconds * 1000)
    }

    private fun uncheckAll() {
        binding.btnSoft.isChecked = false
        binding.btnMedium.isChecked = false
        binding.btnHard.isChecked = false
    }

    /** 预设设置对话框：每种类型可设置分钟 + 秒。 */
    private fun showPresetSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val rows = listOf(
            getString(R.string.egg_soft) to softSec,
            getString(R.string.egg_medium) to mediumSec,
            getString(R.string.egg_hard) to hardSec
        ).map { (name, sec) -> buildPresetRow(container, name, sec) }

        AlertDialog.Builder(this)
            .setTitle(R.string.egg_preset_settings)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val values = rows.map { (minPicker, secPicker) ->
                    minPicker.value * 60 + secPicker.value
                }
                if (values.any { it == 0 }) {
                    Toast.makeText(this, R.string.egg_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit()
                    .putInt("soft_s", values[0])
                    .putInt("medium_s", values[1])
                    .putInt("hard_s", values[2])
                    .apply()
                loadPresets()
                Toast.makeText(this, R.string.egg_saved, Toast.LENGTH_SHORT).show()
                // 当前选中的预设若被修改，同步更新显示
                when {
                    binding.btnSoft.isChecked -> selectPreset(softSec, R.string.egg_soft)
                    binding.btnMedium.isChecked -> selectPreset(mediumSec, R.string.egg_medium)
                    binding.btnHard.isChecked -> selectPreset(hardSec, R.string.egg_hard)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 构建一行「名称 + 分钟滚轮 + 秒滚轮」。返回分/秒两个 picker。 */
    private fun buildPresetRow(
        container: LinearLayout,
        name: String,
        initialSec: Int
    ): Pair<NumberPicker, NumberPicker> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        row.addView(TextView(this).apply {
            text = name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        })
        val minPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 59; value = initialSec / 60
            wrapSelectorWheel = true
        }
        val secPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 59; value = initialSec % 60
            wrapSelectorWheel = true
        }
        row.addView(minPicker)
        row.addView(TextView(this).apply {
            text = getString(R.string.unit_minutes); textSize = 15f; setPadding(8, 0, 24, 0)
        })
        row.addView(secPicker)
        row.addView(TextView(this).apply {
            text = getString(R.string.unit_seconds); textSize = 15f; setPadding(8, 0, 0, 0)
        })
        container.addView(row)
        styleNumberPicker(minPicker)
        styleNumberPicker(secPicker)
        return minPicker to secPicker
    }

    private fun startTimer() {
        if (running) return
        stopFeedback()
        running = true
        totalMs = curSeconds * 1000
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
            updateDisplay(curSeconds * 1000, curSeconds * 1000)
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
