package com.example.jfclock

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityPomodoroBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 番茄时钟：专注 → 短休息，每满 rounds 个番茄换一次长休息。
 *
 * 计时走 AlarmManager（[PomoScheduler]）+ 前台服务响铃（[RingService]），
 * 阶段结束时刻按墙钟持久化，因此切走、锁屏、杀进程都能准点提醒；
 * 默认自动衔接下一阶段，关掉后停在「等你点开始」。
 */
class PomodoroActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPomodoroBinding
    private var ticker: Job? = null

    private data class Preset(val work: Int, val short: Int, val long: Int, val rounds: Int)

    private data class DurationRow(val name: String, val initial: Int, val max: Int, val unit: Int)

    companion object {
        private val CLASSIC = Preset(25, 5, 15, 4)
        private val DEEP = Preset(50, 10, 30, 2)
        private val SPRINT = Preset(15, 3, 10, 4)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPomodoroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarAppearance(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swAutoNext.isChecked = PomoStore.autoNext(this)
        binding.swAutoNext.setOnCheckedChangeListener { _, checked ->
            PomoStore.saveSettings(
                this,
                PomoStore.workMin(this),
                PomoStore.shortMin(this),
                PomoStore.longMin(this),
                PomoStore.rounds(this),
                checked
            )
            render()
        }

        val presets = listOf(CLASSIC to binding.btnPresetClassic, DEEP to binding.btnPresetDeep,
                SPRINT to binding.btnPresetSprint)
        presets.forEach { (preset, chip) ->
            chip.setOnClickListener { applyPreset(preset) }
        }

        binding.btnDurations.setOnClickListener { showDurationsDialog() }
        binding.btnEditDurations.setOnClickListener { showDurationsDialog() }
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnSkip.setOnClickListener { skipPhase() }
        binding.btnStop.setOnClickListener { stopAll() }

        startTicker()
    }

    private fun applyPreset(preset: Preset) {
        PomoStore.saveSettings(
            this,
            preset.work,
            preset.short,
            preset.long,
            preset.rounds,
            PomoStore.autoNext(this)
        )
        render()
    }

    private fun onStartClicked() {
        when (PomoStore.state(this)) {
            PomoStore.STATE_RUNNING -> {
                val remaining = (PomoStore.endAt(this) - System.currentTimeMillis())
                    .coerceAtLeast(0L)
                PomoScheduler.cancel(this)
                PomoStore.pause(this, remaining)
            }

            PomoStore.STATE_PAUSED -> beginPhase(PomoStore.phase(this), PomoStore.remainMs(this))
            else -> beginPhase(PomoStore.phase(this))
        }
        render()
    }

    private fun beginPhase(phase: Int, remainingMs: Long = 0L) {
        val full = PomoStore.minutesOf(this, phase) * 60_000L
        val remaining = if (remainingMs > 0) remainingMs else full
        val endAt = System.currentTimeMillis() + remaining
        PomoStore.start(this, phase, endAt, full)
        PomoScheduler.schedule(this, endAt, phase)
    }

    /** 跳过当前阶段：不计入已完成的番茄数，直接进入下一阶段。 */
    private fun skipPhase() {
        val state = PomoStore.state(this)
        if (state == PomoStore.STATE_IDLE) return
        val next = if (PomoStore.phase(this) == PomoStore.PHASE_WORK) {
            PomoStore.PHASE_SHORT
        } else PomoStore.PHASE_WORK
        PomoScheduler.cancel(this)
        beginPhase(next)
        render()
    }

    private fun stopAll() {
        PomoScheduler.cancel(this)
        RingService.stop(this)
        PomoStore.stop(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** 每秒按墙钟刷新，保证在其它页面时回来的显示也是准的。 */
    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (true) {
                render()
                delay(500)
            }
        }
    }

    private fun render() {
        val phase = PomoStore.phase(this)
        val state = PomoStore.state(this)
        val full = PomoStore.minutesOf(this, phase) * 60_000L
        val remaining = when (state) {
            PomoStore.STATE_RUNNING -> (PomoStore.endAt(this) - System.currentTimeMillis())
                .coerceIn(0L, full)
            PomoStore.STATE_PAUSED -> PomoStore.remainMs(this).coerceIn(0L, full)
            else -> full
        }
        val minutes = PomoStore.minutesOf(this, phase)

        val sec = (remaining + 999) / 1000
        binding.tvCountdown.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
        binding.progress.progress = if (full > 0) {
            ((full - remaining) * 1000 / full).toInt().coerceIn(0, 1000)
        } else 0

        binding.tvPhase.text = getString(
            R.string.pomo_phase_fmt,
            PomoStore.phaseName(this, phase),
            minutes
        )
        binding.tvRoundInfo.text = getString(
            R.string.pomo_round_info_fmt,
            PomoStore.currentRound(this),
            PomoStore.rounds(this),
            PomoStore.completedRounds(this)
        )
        binding.tvStatus.text = when (state) {
            PomoStore.STATE_RUNNING -> getString(R.string.pomo_state_running)
            PomoStore.STATE_PAUSED -> getString(R.string.pomo_state_paused)
            PomoStore.STATE_WAIT -> getString(R.string.pomo_state_wait)
            else -> getString(R.string.pomo_state_idle)
        }
        binding.btnStart.setText(
            when (state) {
                PomoStore.STATE_RUNNING -> R.string.pomo_pause
                PomoStore.STATE_PAUSED -> R.string.pomo_resume
                PomoStore.STATE_WAIT -> R.string.pomo_next_phase
                else -> R.string.pomo_start
            }
        )

        binding.tvDurations.text = getString(
            R.string.pomo_durations_fmt,
            PomoStore.workMin(this),
            PomoStore.shortMin(this),
            PomoStore.longMin(this),
            PomoStore.rounds(this)
        )
        binding.btnPresetClassic.isChecked = matches(CLASSIC)
        binding.btnPresetDeep.isChecked = matches(DEEP)
        binding.btnPresetSprint.isChecked = matches(SPRINT)
    }

    private fun matches(preset: Preset) =
        PomoStore.workMin(this) == preset.work && PomoStore.shortMin(this) == preset.short &&
                PomoStore.longMin(this) == preset.long && PomoStore.rounds(this) == preset.rounds

    /** 四个滚轮一行一个：专注 / 短休 / 长休 / 几轮后长休息。 */
    private fun showDurationsDialog() {
        val rows = listOf(
            DurationRow(getString(R.string.pomo_work), PomoStore.workMin(this), 180, R.string.unit_minutes),
            DurationRow(getString(R.string.pomo_short_break), PomoStore.shortMin(this), 60, R.string.unit_minutes),
            DurationRow(getString(R.string.pomo_long_break), PomoStore.longMin(this), 120, R.string.unit_minutes),
            DurationRow(getString(R.string.pomo_rounds_label), PomoStore.rounds(this), 12, R.string.unit_rounds)
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val pickers = rows.map { row ->
            val picker = WheelPicker(this).apply {
                minValue = 1
                maxValue = row.max
                setTextSizeSp(18f)
                setFormatter { it.toString() }
                value = row.initial.coerceIn(1, row.max)
            }
            container.addView(pickerRow(row.name, picker, row.unit))
            picker
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pomo_durations)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                PomoStore.saveSettings(
                    this,
                    pickers[0].value,
                    pickers[1].value,
                    pickers[2].value,
                    pickers[3].value,
                    PomoStore.autoNext(this)
                )
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickerRow(name: String, picker: WheelPicker, unitRes: Int): LinearLayout {
        val textColor = ContextCompat.getColor(this, R.color.light_text_primary)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
            addView(TextView(this@PomodoroActivity).apply {
                text = name
                textSize = 16f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            addView(picker)
            addView(TextView(this@PomodoroActivity).apply {
                text = context.getString(unitRes)
                textSize = 15f
                setTextColor(textColor)
                setPadding(12, 0, 0, 0)
            })
        }
    }

    override fun onDestroy() {
        // 只停界面刷新，AlarmManager 的排程继续生效
        ticker?.cancel()
        ticker = null
        super.onDestroy()
    }
}
