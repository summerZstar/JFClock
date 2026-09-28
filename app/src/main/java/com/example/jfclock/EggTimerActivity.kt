package com.example.jfclock

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityEggTimerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 煮蛋计时：熟度（溏心/半熟/全熟）× 大小（M/L/XL）+ 冷藏加成算出建议时长。
 *
 * 计时走 AlarmManager（EggScheduler）+ 前台服务响铃（RingService），
 * 结束时刻按墙钟持久化，因此切走、锁屏、杀进程都能准点响，重进可恢复。
 */
class EggTimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEggTimerBinding
    private lateinit var prefs: SharedPreferences

    private var job: Job? = null
    private var running = false
    private var endAt = 0L
    private var totalMs = 0L

    private var softSec = 360
    private var mediumSec = 480
    private var hardSec = 600

    private var doneness = 0      // 0 溏心 / 1 半熟 / 2 全熟
    private var size = 0          // 0 M / 1 L / 2 XL
    private var fridge = false
    private var customSec = 0     // > 0 表示用自定义时长，忽略上方计算

    private val donenessNames by lazy {
        listOf(getString(R.string.egg_soft), getString(R.string.egg_medium), getString(R.string.egg_hard))
    }

    companion object {
        private val SIZE_NAMES = listOf("M蛋", "L蛋", "XL蛋")
        private val SIZE_DETAIL = listOf("56–63g", "64–70g", "71–80g")
        private val SIZE_OFFSETS = intArrayOf(0, 30, 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEggTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarAppearance(this)

        prefs = getSharedPreferences("egg_timer_prefs", MODE_PRIVATE)
        softSec = prefs.getInt("soft_s", 360)
        mediumSec = prefs.getInt("medium_s", 480)
        hardSec = prefs.getInt("hard_s", 600)
        doneness = prefs.getInt("doneness", 0).coerceIn(0, 2)
        size = prefs.getInt("size", 0).coerceIn(0, 2)
        fridge = prefs.getBoolean("fridge", false)
        customSec = prefs.getInt("custom_s", 0)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupChips()
        binding.swFridge.isChecked = fridge
        binding.swFridge.setOnCheckedChangeListener { _, checked ->
            fridge = checked
            prefs.edit().putBoolean("fridge", checked).apply()
            if (!running) recompute()
        }
        binding.btnEditPresets.setOnClickListener { showPresetSettings() }
        binding.btnCustom.setOnClickListener { showCustomDialog() }
        binding.btnGuide.setOnClickListener { toggleGuide() }
        binding.btnStart.setOnClickListener { startTimer() }
        binding.btnStop.setOnClickListener { stopTimer() }

        restoreState()
    }

    private fun setupChips() {
        listOf(binding.btnSoft, binding.btnMedium, binding.btnHard).forEachIndexed { index, btn ->
            btn.setOnClickListener { selectDoneness(index) }
        }
        listOf(binding.btnSizeM, binding.btnSizeL, binding.btnSizeXl).forEachIndexed { index, btn ->
            btn.setOnClickListener {
                size = index
                customSec = 0
                prefs.edit().putInt("size", index).putInt("custom_s", 0).apply()
                refreshChips()
                if (!running) recompute()
            }
        }
        refreshChips()
    }

    /** 点熟度会退出「自定义」。 */
    private fun selectDoneness(index: Int) {
        doneness = index
        customSec = 0
        prefs.edit().putInt("doneness", index).putInt("custom_s", 0).apply()
        refreshChips()
        if (!running) recompute()
    }

    private fun refreshChips() {
        val custom = customSec > 0
        binding.btnSoft.isChecked = !custom && doneness == 0
        binding.btnMedium.isChecked = !custom && doneness == 1
        binding.btnHard.isChecked = !custom && doneness == 2
        binding.btnSizeM.isChecked = !custom && size == 0
        binding.btnSizeL.isChecked = !custom && size == 1
        binding.btnSizeXl.isChecked = !custom && size == 2
        binding.tvCustomValue.text =
            if (custom) formatEggDuration(customSec) else getString(R.string.egg_custom_off)
        binding.tvCustomValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (custom) R.color.light_accent else R.color.light_text_primary
            )
        )
    }

    private fun toggleGuide() {
        val show = binding.tvGuideBody.visibility != View.VISIBLE
        binding.tvGuideBody.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvGuideToggle.setText(
            if (show) R.string.egg_guide_collapse else R.string.egg_guide_expand
        )
    }

    private fun baseSeconds(): Int = when (doneness) {
        1 -> mediumSec
        2 -> hardSec
        else -> softSec
    }

    private fun computedSeconds(): Int =
        if (customSec > 0) customSec
        else baseSeconds() + SIZE_OFFSETS[size] + if (fridge) 30 else 0

    private fun currentLabel(): String =
        "${if (customSec > 0) getString(R.string.egg_custom) else donenessNames[doneness]}" +
                " · ${SIZE_NAMES[size]}(${SIZE_DETAIL[size]}) · ${formatEggDuration(computedSeconds())}"

    /** 刷新倒计时、当前配置与时长来源说明。 */
    private fun recompute() {
        val total = computedSeconds()
        if (!running) {
            totalMs = total * 1000L
            updateDisplay(totalMs, totalMs)
            binding.tvStatus.text = currentLabel()
        }
        binding.tvFormula.text = if (customSec > 0) {
            getString(R.string.egg_calc_custom, formatEggDuration(total))
        } else {
            buildString {
                append(donenessNames[doneness]).append(' ').append(formatEggDuration(baseSeconds()))
                if (SIZE_OFFSETS[size] > 0) {
                    append(" ＋ ").append(SIZE_NAMES[size]).append(' ').append(SIZE_OFFSETS[size]).append("秒")
                }
                if (fridge) append(" ＋ 冷藏 30秒")
                append(" ＝ ").append(formatEggDuration(total))
            }
        }
    }

    /** 冷启动 / 从别的 App 回来时，按持久化的结束时刻恢复状态。 */
    private fun restoreState() {
        val pending = EggStore.runningEndAt(this)
        val finished = EggStore.finishedAt(this)
        refreshChips()
        recompute()
        when {
            pending > 0 -> {
                running = true
                endAt = pending
                totalMs = EggStore.totalMs(this).takeIf { it > 0 }
                    ?: (pending - System.currentTimeMillis())
                binding.tvStatus.setText(R.string.egg_running)
                startTicker()
            }
            finished > 0 -> {
                recompute()
                binding.tvStatus.setText(R.string.egg_finished)
            }
            else -> recompute()
        }
    }

    private fun startTimer() {
        if (running) return
        val seconds = computedSeconds()
        if (seconds <= 0) {
            Toast.makeText(this, R.string.egg_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        totalMs = seconds * 1000L
        endAt = System.currentTimeMillis() + totalMs
        val label = currentLabel()
        EggStore.save(this, endAt, totalMs, label)
        EggScheduler.schedule(this, endAt, label)
        binding.tvStatus.setText(R.string.egg_running)
        startTicker()
    }

    private fun stopTimer() {
        job?.cancel()
        job = null
        running = false
        endAt = 0L
        EggScheduler.cancel(this)
        EggStore.clear(this)
        recompute()
    }

    private fun startTicker() {
        job?.cancel()
        job = lifecycleScope.launch {
            while (true) {
                val remaining = endAt - System.currentTimeMillis()
                updateDisplay(remaining.coerceAtLeast(0), totalMs)
                if (remaining <= 0) {
                    running = false
                    binding.tvStatus.setText(R.string.egg_done)
                    break
                }
                delay(250)
            }
        }
    }

    private fun updateDisplay(remainingMs: Long, total: Long) {
        val sec = (remainingMs.coerceAtLeast(0) + 999) / 1000
        binding.tvCountdown.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
        binding.progress.progress = if (total > 0) {
            ((total - remainingMs) * 1000 / total).toInt().coerceIn(0, 1000)
        } else 0
    }

    /** 自定义时长：分 + 秒两个滚轮；选 0 分 0 秒即清除自定义。 */
    private fun showCustomDialog() {
        val initial = if (customSec > 0) customSec else computedSeconds()
        val (minPicker, secPicker) = buildTimePickers(initial)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.egg_custom_dialog_title)
            .setView(wrapPickers(minPicker, secPicker))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val sec = minPicker.value * 60 + secPicker.value
                customSec = if (sec <= 0) 0 else sec.coerceAtMost(120 * 60)
                prefs.edit().putInt("custom_s", customSec).apply()
                refreshChips()
                if (!running) recompute()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 熟度基础时长设置：每种熟度分别设置分钟 + 秒（大小/冷藏在此基础上叠加）。 */
    private fun showPresetSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.egg_size_detail)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@EggTimerActivity, R.color.light_text_secondary))
            setPadding(0, 0, 0, 12)
        })
        val rows = listOf(
            donenessNames[0] to softSec,
            donenessNames[1] to mediumSec,
            donenessNames[2] to hardSec
        ).map { (name, sec) -> name to buildTimePickers(sec).also { container.addView(wrapPickers(it.first, it.second, name)) } }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.egg_preset_settings)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val values = rows.map { (name, pickers) ->
                    val (minPicker, secPicker) = pickers
                    minPicker.value * 60 + secPicker.value
                }
                if (values.any { it == 0 }) {
                    Toast.makeText(this, R.string.egg_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                softSec = values[0]
                mediumSec = values[1]
                hardSec = values[2]
                prefs.edit()
                    .putInt("soft_s", softSec)
                    .putInt("medium_s", mediumSec)
                    .putInt("hard_s", hardSec)
                    .apply()
                Toast.makeText(this, R.string.egg_saved, Toast.LENGTH_SHORT).show()
                if (!running) recompute()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun buildTimePickers(initialSec: Int): Pair<NumberPicker, NumberPicker> {
        val minPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 120; value = (initialSec / 60).coerceAtMost(120)
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val secPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 59; value = initialSec % 60
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        styleNumberPicker(minPicker)
        styleNumberPicker(secPicker)
        return minPicker to secPicker
    }

    /** 「名称 + 分滚轮 + 秒滚轮」一行；name 传 null 时只显示滚轮。 */
    private fun wrapPickers(
        minPicker: NumberPicker,
        secPicker: NumberPicker,
        name: String? = null
    ): LinearLayout {
        val textColor = ContextCompat.getColor(this, R.color.light_text_primary)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
            if (name != null) {
                addView(TextView(this@EggTimerActivity).apply {
                    text = name
                    textSize = 16f
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f
                    )
                })
            }
            addView(minPicker)
            addView(TextView(this@EggTimerActivity).apply {
                text = context.getString(R.string.unit_minutes); textSize = 15f
                setTextColor(textColor); setPadding(8, 0, 20, 0)
            })
            addView(secPicker)
            addView(TextView(this@EggTimerActivity).apply {
                text = context.getString(R.string.unit_seconds); textSize = 15f
                setTextColor(textColor); setPadding(8, 0, 0, 0)
            })
        }
    }

    override fun onDestroy() {
        // 只停止界面刷新；AlarmManager 的排程继续生效
        job?.cancel()
        job = null
        super.onDestroy()
    }
}
