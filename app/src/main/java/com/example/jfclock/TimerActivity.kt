package com.example.jfclock

import android.os.Bundle
import android.view.View
import android.widget.Space
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.jfclock.databinding.ActivityTimerBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 计时器（倒计时）：参考 ColorOS 时钟的大圆环 + 中心倒计时，
 * 时/分/秒三列滚轮选时长，下方三列「档位滚轮」配 −/+ 圆钮一键增减。
 *
 * 两种类型：拨好就点开始（手动倒计时）、或打开「定时开始」滚一个时刻到点自动倒数；
 * 两种都能存进「常用计时器」，列表用秒表/铃铛图标区分。
 *
 * 倒计时走 AlarmManager（[TimerScheduler]）+ 前台服务响铃（[RingService]），
 * 结束时刻按墙钟持久化，所以切走、锁屏、杀进程都能准点响。
 */
class TimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimerBinding
    private var ticker: Job? = null
    private var lastState = -1
    /** 定时开始的时刻；-1 表示未选。 */
    private var scheduledHour = -1
    private var scheduledMinute = -1

    private val presetDao by lazy { (application as JFClockApp).database.timerPresetDao() }
    private var presets: List<TimerPreset> = emptyList()
    private lateinit var presetAdapter: TimerPresetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarAppearance(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupWheel(binding.pickerHour, 23)
        setupWheel(binding.pickerMinute, 59)
        setupWheel(binding.pickerSecond, 59)

        buildDeltaColumn(R.string.timer_step_hours, HOUR_STEPS, 3600)
        buildDeltaColumn(R.string.timer_step_minutes, MINUTE_STEPS, 60)
        buildDeltaColumn(R.string.timer_step_seconds, SECOND_STEPS, 1)

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnReset.setOnClickListener { onResetClicked() }
        binding.switchScheduled.setOnCheckedChangeListener { _, checked ->
            if (checked && scheduledHour < 0) showStartTimePicker()
            render()
        }
        binding.rowScheduledStart.setOnClickListener { showStartTimePicker() }
        binding.btnPresetAdd.setOnClickListener { showPresetDialog(null) }

        seedDefaultPresets()
        presetAdapter = TimerPresetAdapter(
            onApply = { applyPreset(it) },
            onEdit = { showPresetDialog(it) }
        )
        binding.presetList.layoutManager = LinearLayoutManager(this)
        binding.presetList.adapter = presetAdapter
        presetAdapter.attachTouchHelper(binding.presetList)
        presetAdapter.onOrderChanged = { ids -> persistPresetOrder(ids) }
        lifecycleScope.launch {
            presetDao.observeAll().collect { list ->
                presets = list
                presetAdapter.submit(list)
            }
        }

        startTicker()
    }

    // ---- 常用计时器 ----

    /** 首次使用时塞几个常见预设（参考 ColorOS：刷牙/蒸蛋/午睡/计时）。 */
    private fun seedDefaultPresets() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prefs = getSharedPreferences("timer_prefs", MODE_PRIVATE)
            if (prefs.getBoolean("presets_seeded", false)) return@launch
            presetDao.insert(TimerPreset(name = getString(R.string.timer_preset_default_brush), seconds = 120, sortOrder = 0))
            presetDao.insert(TimerPreset(name = getString(R.string.timer_preset_default_egg), seconds = 600, sortOrder = 1))
            presetDao.insert(TimerPreset(name = getString(R.string.timer_preset_default_nap), seconds = 1800, sortOrder = 2))
            presetDao.insert(TimerPreset(name = getString(R.string.timer_preset_default_generic), seconds = 300, sortOrder = 3))
            prefs.edit().putBoolean("presets_seeded", true).apply()
        }
    }

    /** 拖动排序落库：新顺序与库里一致就不写，避免每次拖动都整表更新。 */
    private fun persistPresetOrder(ids: List<Long>) {
        if (ids == presets.map { it.id }) return
        lifecycleScope.launch {
            ids.forEachIndexed { index, id -> presetDao.updateOrder(id, index) }
        }
    }

    /**
     * 点预设：把时长、名称和类型（是否定时开始）填进拨盘，**不自动开始**。
     * 用户确认无误后再点底部的「开始」，避免误触就跑起来。
     */
    private fun applyPreset(preset: TimerPreset) {
        if (!isIdle()) {
            Toast.makeText(this, R.string.timer_preset_busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (preset.seconds <= 0) return
        setDial(preset.seconds)
        binding.etName.setText(preset.name)
        // 先写时刻再切开关：开关监听里会用 scheduledHour 判断要不要弹选择器
        if (preset.scheduled) {
            scheduledHour = preset.startHour
            scheduledMinute = preset.startMinute
        } else {
            scheduledHour = -1
            scheduledMinute = -1
        }
        binding.switchScheduled.isChecked = preset.scheduled
        // 预设列表在下方，填完把页面拉回顶部，让填好的滚轮落在第一屏
        binding.scrollRoot.post { binding.scrollRoot.smoothScrollTo(0, 0) }
        Toast.makeText(
            this,
            if (preset.scheduled) {
                getString(
                    R.string.timer_preset_applied_scheduled_fmt,
                    preset.name,
                    formatDurationPrecise(preset.seconds * 1000L),
                    clockText(preset.startHour, preset.startMinute)
                )
            } else {
                getString(
                    R.string.timer_preset_applied_fmt,
                    preset.name,
                    formatDurationPrecise(preset.seconds * 1000L)
                )
            },
            Toast.LENGTH_SHORT
        ).show()
        render()
    }

    /** 新建 / 编辑 / 删除预设，类型（倒计时 or 定时开始）一起存。 */
    private fun showPresetDialog(existing: TimerPreset?) {
        val view = layoutInflater.inflate(R.layout.view_timer_preset_edit, null)
        val nameInput = view.findViewById<android.widget.EditText>(R.id.etPresetName)
        val pickerHour = view.findViewById<WheelPicker>(R.id.pickerPresetHour)
        val pickerMinute = view.findViewById<WheelPicker>(R.id.pickerPresetMinute)
        val pickerSecond = view.findViewById<WheelPicker>(R.id.pickerPresetSecond)
        val switchScheduled = view.findViewById<androidx.appcompat.widget.SwitchCompat>(
            R.id.switchPresetScheduled
        )
        val groupStart = view.findViewById<View>(R.id.groupPresetStart)
        val startHourPicker = view.findViewById<WheelPicker>(R.id.pickerPresetStartHour)
        val startMinutePicker = view.findViewById<WheelPicker>(R.id.pickerPresetStartMinute)
        val scheduledHint = view.findViewById<android.widget.TextView>(R.id.tvPresetScheduledHint)
        listOf(pickerHour to 23, pickerMinute to 59, pickerSecond to 59)
            .forEach { (p, max) -> bindWheel(p, max) }
        bindWheel(startHourPicker, 23)
        bindWheel(startMinutePicker, 59)
        existing?.let {
            nameInput.setText(it.name)
            pickerHour.value = it.seconds / 3600
            pickerMinute.value = it.seconds % 3600 / 60
            pickerSecond.value = it.seconds % 60
            if (it.scheduled) {
                startHourPicker.value = it.startHour
                startMinutePicker.value = it.startMinute
                switchScheduled.isChecked = true
            }
        }
        val applyStartVisibility = {
            groupStart.visibility = if (switchScheduled.isChecked) View.VISIBLE else View.GONE
            scheduledHint.setText(
                if (switchScheduled.isChecked) R.string.timer_preset_scheduled_on_hint
                else R.string.timer_preset_scheduled_off_hint
            )
        }
        applyStartVisibility()
        switchScheduled.setOnCheckedChangeListener { _, _ -> applyStartVisibility.invoke() }
        view.findViewById<View>(R.id.rowPresetScheduled).setOnClickListener {
            switchScheduled.toggle()
        }

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) R.string.timer_preset_new else R.string.timer_preset_edit
            )
            .setView(view)
            .setPositiveButton(R.string.timer_preset_save, null)
            .setNegativeButton(R.string.action_cancel, null)
        if (existing != null) builder.setNeutralButton(R.string.timer_preset_delete, null)
        val dialog = builder.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val seconds = pickerHour.value * 3600 + pickerMinute.value * 60 + pickerSecond.value
            val scheduled = switchScheduled.isChecked
            if (name.isEmpty()) {
                Toast.makeText(
                    this,
                    if (scheduled) R.string.timer_preset_scheduled_need_name
                    else R.string.timer_preset_need_name,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (seconds <= 0) {
                Toast.makeText(this, R.string.timer_preset_need_time, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val startHour = if (scheduled) startHourPicker.value else -1
            val startMinute = if (scheduled) startMinutePicker.value else 0
            lifecycleScope.launch {
                if (existing == null) {
                    // 新预设排在最后（sortOrder 取当前条数）
                    presetDao.insert(
                        TimerPreset(
                            name = name, seconds = seconds,
                            startHour = startHour, startMinute = startMinute,
                            sortOrder = presets.size
                        )
                    )
                } else {
                    presetDao.update(
                        existing.copy(
                            name = name, seconds = seconds,
                            startHour = startHour, startMinute = startMinute
                        )
                    )
                }
            }
            dialog.dismiss()
        }
        if (existing != null) {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                lifecycleScope.launch { presetDao.delete(existing) }
                dialog.dismiss()
            }
        }
    }

    /** 滚轮统一接线：量程 + 循环 + 两位补零。 */
    private fun bindWheel(picker: WheelPicker, max: Int) {
        picker.minValue = 0
        picker.maxValue = max
        picker.wrapSelectorWheel = true
        picker.setFormatter { String.format(Locale.US, "%02d", it) }
    }

    private fun clockText(hour: Int, minute: Int) =
        String.format(Locale.US, "%02d:%02d", hour, minute)

    /** 选「定时开始」的时刻：和时长一样用滚轮拨时/分。 */
    private fun showStartTimePicker() {
        val view = layoutInflater.inflate(R.layout.view_timer_start_time, null)
        val hourPicker = view.findViewById<WheelPicker>(R.id.pickerStartHour)
        val minutePicker = view.findViewById<WheelPicker>(R.id.pickerStartMinute)
        bindWheel(hourPicker, 23)
        bindWheel(minutePicker, 59)
        // 没选过就先拨到下一个整点，比 08:00 更贴近「等会儿开始」的用法
        val nextHour = (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) + 1) % 24
        hourPicker.value = if (scheduledHour >= 0) scheduledHour else nextHour
        minutePicker.value = if (scheduledMinute >= 0) scheduledMinute else 0
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.timer_scheduled_pick)
            .setView(view)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            scheduledHour = hourPicker.value
            scheduledMinute = minutePicker.value
            binding.switchScheduled.isChecked = true
            render()
            dialog.dismiss()
        }
    }

    /** 摘要行：固定与「定时开始」同排，所以只给「X 开始 · Y 响铃」这种短文案。 */
    private fun scheduledSummaryText(): String {
        if (!binding.switchScheduled.isChecked || scheduledHour < 0) {
            return getString(R.string.timer_scheduled_off_summary)
        }
        val seconds = dialSeconds()
        if (seconds <= 0) return getString(R.string.timer_scheduled_pick)
        val now = System.currentTimeMillis()
        val total = seconds * 1000L
        // 已过的开始时刻：只要「开始 + 时长」还没过去就按当天算，所以这里也要带时长
        val startAt = TimerStore.nextOccurrence(now, scheduledHour, scheduledMinute, total)
        return getString(
            R.string.timer_scheduled_summary_fmt,
            describeDayTime(this, startAt, now),
            describeDayTime(this, startAt + total, startAt)
        )
    }

    private fun setupWheel(picker: WheelPicker, max: Int) {
        bindWheel(picker, max)
        picker.setOnValueChangedListener { _, _, _ -> if (isIdle()) render() }
    }

    /**
     * 一列档位增减：滚轮滚到想用的档位，左侧减、右侧加，可连点累加；单位（时/分/秒）决定档位值换算成多少秒。
     *
     * 三列（小时/分钟/秒钟）各是一块独立小组：块内是「− 滚轮 +」，块与块之间留 [DELTA_COL_GAP_DP]。
     * 间距加在**列之间**而不是列内部——加在列内部会挤扁中间的滚轮，把「小时档」这类单位文字逼到换行。
     */
    private fun buildDeltaColumn(unitLabelRes: Int, steps: IntArray, secondsPerStep: Int) {
        if (binding.rowDeltaCols.childCount > 0) {
            binding.rowDeltaCols.addView(
                Space(this).apply {
                    minimumWidth = (DELTA_COL_GAP_DP * resources.displayMetrics.density).toInt()
                }
            )
        }
        val col = layoutInflater.inflate(R.layout.view_timer_delta_col, binding.rowDeltaCols, false)
        val picker = col.findViewById<WheelPicker>(R.id.pickerDeltaStep)
        picker.minValue = 0
        picker.maxValue = steps.size - 1
        picker.wrapSelectorWheel = true
        picker.setFormatter { String.format(Locale.US, "%02d", steps[it]) }
        col.findViewById<android.widget.TextView>(R.id.tvDeltaUnit).setText(unitLabelRes)
        fun stepSeconds() = steps[picker.value] * secondsPerStep
        col.findViewById<MaterialButton>(R.id.btnStepMinus).setOnClickListener {
            step(-stepSeconds())
        }
        col.findViewById<MaterialButton>(R.id.btnStepPlus).setOnClickListener {
            step(stepSeconds())
        }
        binding.rowDeltaCols.addView(col)
    }

    /** 加减一个固定增量：只在停表时有效，越界由 [setDial] 截到 0..24h。 */
    private fun step(deltaSeconds: Int) {
        if (!isIdle()) return
        setDial(dialSeconds() + deltaSeconds)
        render()
    }

    private fun isIdle() = TimerStore.state(this) == TimerStore.STATE_IDLE

    /** 滚轮当前拨到的总秒数。 */
    private fun dialSeconds(): Int =
        binding.pickerHour.value * 3600 + binding.pickerMinute.value * 60 + binding.pickerSecond.value

    /** 带动画滚到指定秒数，上限 24 小时。 */
    private fun setDial(seconds: Int) {
        val s = seconds.coerceIn(0, 24 * 3600 - 1)
        binding.pickerHour.animateTo(s / 3600)
        binding.pickerMinute.animateTo(s % 3600 / 60)
        binding.pickerSecond.animateTo(s % 60)
    }

    private fun onStartClicked() {
        when {
            // 定时开始还没到：这次点击 = 取消定时
            TimerStore.pendingStart(this) -> {
                TimerScheduler.cancel(this)
                TimerStore.reset(this)
            }

            TimerStore.state(this) == TimerStore.STATE_RUNNING -> {
                val remaining = TimerStore.remaining(this)
                TimerScheduler.cancel(this)
                TimerStore.pause(this, remaining)
            }

            TimerStore.state(this) == TimerStore.STATE_PAUSED -> {
                val endAt = TimerStore.resume(this)
                TimerScheduler.schedule(this, endAt, TimerStore.label(this))
            }

            else -> {
                val seconds = dialSeconds()
                if (seconds <= 0) {
                    Toast.makeText(this, R.string.timer_need_time, Toast.LENGTH_SHORT).show()
                    return
                }
                val total = seconds * 1000L
                val label = binding.etName.text?.toString()?.trim().orEmpty()
                val useScheduled = binding.switchScheduled.isChecked && scheduledHour >= 0
                if (useScheduled) {
                    // 定时开始：结束时刻 = 开始时刻 + 时长，只排这一条闹钟，
                    // 杀进程后到点照样响；等待期间按墙钟状态展示
                    val startAt = TimerStore.nextOccurrence(
                        System.currentTimeMillis(), scheduledHour, scheduledMinute, total
                    )
                    val endAt = startAt + total
                    TimerStore.start(this, total, endAt, label, startAt)
                    TimerScheduler.schedule(this, endAt, label)
                    val startText = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                        .format(Date(startAt))
                    val ringText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endAt))
                    Toast.makeText(
                        this,
                        getString(R.string.timer_scheduled_toast_fmt, startText, ringText),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val endAt = System.currentTimeMillis() + total
                    TimerStore.start(this, total, endAt, label)
                    TimerScheduler.schedule(this, endAt, label)
                }
            }
        }
        TimerCountdown.refresh(this)
        render()
    }

    /** 停下并回到拨盘：清掉排程与运行状态，已拨的时间与名称保留。 */
    private fun resetAll() {
        TimerScheduler.cancel(this)
        TimerStore.reset(this)
        TimerCountdown.refresh(this)
        render()
    }

    /**
     * 底部「重置」：停表时把拨盘清零（00:00:00），计时中/暂停时先停表回拨盘。
     * 名称和「定时开始」不动，方便清完时长接着改。
     */
    private fun onResetClicked() {
        if (isIdle()) {
            setDial(0)
            render()
        } else {
            resetAll()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (true) {
                render()
                delay(200)
            }
        }
    }

    private fun render() {
        val state = TimerStore.state(this)
        val dialed = state == TimerStore.STATE_IDLE
        val pendingStart = TimerStore.pendingStart(this)
        val remaining = if (dialed) dialSeconds() * 1000L else TimerStore.remaining(this)
        val total = if (dialed) remaining else TimerStore.totalMs(this)
        val storedLabel = TimerStore.label(this)
        val now = System.currentTimeMillis()

        if (state != lastState) {
            onStateChanged(state, storedLabel)
            lastState = state
        }

        // 对齐 ColorOS：设置时长时只看滚轮（圆盘隐藏），启动/暂停/定时等待时看圆盘
        binding.ringGroup.visibility = if (dialed) View.GONE else View.VISIBLE
        binding.dialGroup.visibility = if (dialed) View.VISIBLE else View.GONE
        binding.quickGroup.visibility = if (dialed) View.VISIBLE else View.GONE
        binding.etName.visibility = if (dialed) View.VISIBLE else View.GONE
        binding.rowScheduledStart.visibility = if (dialed) View.VISIBLE else View.GONE
        binding.presetGroup.visibility = if (dialed) View.VISIBLE else View.GONE
        binding.tvStatus.visibility = if (dialed) View.GONE else View.VISIBLE
        binding.tvTimerLabel.visibility = if (dialed) View.GONE else View.VISIBLE
        if (dialed) binding.tvScheduledSummary.text = scheduledSummaryText()


        binding.tvCountdown.text = formatTimerClock(remaining)
        binding.ring.progress = if (pendingStart || total <= 0 || remaining <= 0) {
            0
        } else {
            (remaining * 1000 / total).toInt().coerceIn(0, 1000)
        }

        val name = if (dialed) binding.etName.text?.toString()?.trim().orEmpty() else storedLabel
        binding.tvTimerLabel.text = when {
            pendingStart -> {
                val at = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                    .format(Date(TimerStore.startAt(this)))
                getString(R.string.timer_scheduled_title) + " " + at +
                        if (name.isBlank()) "" else " · $name"
            }
            state == TimerStore.STATE_RUNNING -> {
                val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now + remaining))
                getString(R.string.timer_end_at_fmt, at) +
                        if (name.isBlank()) "" else " · $name"
            }
            else -> name
        }

        binding.tvStatus.text = getString(
            when {
                pendingStart -> R.string.timer_state_scheduled
                state == TimerStore.STATE_RUNNING -> R.string.timer_state_running
                state == TimerStore.STATE_PAUSED -> R.string.timer_state_paused
                else -> R.string.timer_state_idle
            }
        )
        binding.btnStart.setText(
            when {
                pendingStart -> R.string.timer_cancel_scheduled
                state == TimerStore.STATE_RUNNING -> R.string.timer_pause
                state == TimerStore.STATE_PAUSED -> R.string.timer_resume
                else -> R.string.timer_start
            }
        )
    }

    /** 停表 → 运行 → 停表时把滚轮回填成上次的时长，名称也回填。 */
    private fun onStateChanged(state: Int, storedLabel: String) {
        if (state != TimerStore.STATE_IDLE) return
        if (dialSeconds() == 0) {
            val lastTotalSec = (TimerStore.totalMs(this) / 1000L).toInt()
            if (lastTotalSec > 0) setDial(lastTotalSec)
        }
        if (binding.etName.text.isNullOrBlank() && storedLabel.isNotBlank()) {
            binding.etName.setText(storedLabel)
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        ticker = null
        super.onDestroy()
    }

    companion object {
        /** 三列档位滚轮的可选增量（单位分别是时/分/秒）。 */
        private val HOUR_STEPS = intArrayOf(1, 2, 3, 6, 12)
        private val MINUTE_STEPS = intArrayOf(1, 5, 10, 15, 30, 45)
        private val SECOND_STEPS = intArrayOf(5, 10, 15, 30, 45)

        /** 档位三列之间的间距（dp）：要明显大于圆钮到本列滚轮的距离，三块才分得开。 */
        private const val DELTA_COL_GAP_DP = 16
    }
}
