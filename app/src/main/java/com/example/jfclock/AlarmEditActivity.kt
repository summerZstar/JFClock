package com.example.jfclock

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityAlarmEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 新建 / 编辑闹钟。
 *
 * 重复方式点「重复」行弹窗单选：仅一次、每天、法定工作日、自定义（每周几）、
 * 每月几号、每年几月几号、每隔 N 天。子参数按类型在下面就地展开：
 * 起始日期（仅一次/每月/每年/每隔 N 天）、星期圆钮（自定义）、间隔天数。
 *
 * 默认「仅一次」；若所选时刻已过，保存时自动顺延到最早可响的一天，
 * 提示里写明实际日期，避免出现「开关亮着但永远不响」的假闹钟。
 */
class AlarmEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmEditBinding
    private val repo by lazy { (application as JFClockApp).repository }

    private var editingId: Long = -1L
    private var repeatType: Int = Alarm.REPEAT_ONCE
    private var intervalDays: Int = 2

    /** 自定义重复：选中的星期位掩码（位 0 = 周一 … 位 6 = 周日）。 */
    private var repeatDays: Int = 0
    private val anchor: Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val weekdayButtons: List<TextView>
        get() = listOf(binding.wd1, binding.wd2, binding.wd3, binding.wd4, binding.wd5, binding.wd6, binding.wd7)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarAppearance(this)

        editingId = intent.getLongExtra("alarmId", -1L)

        setupPickers()
        setupRepeat()
        setupOptions()

        binding.toolbar.title =
            if (editingId != -1L) getString(R.string.edit_alarm) else getString(R.string.new_alarm)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.edit_menu)
        // 新建时不显示删除
        binding.toolbar.menu.findItem(R.id.action_delete)?.isVisible = editingId != -1L
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> { save(); true }
                R.id.action_delete -> { delete(); true }
                else -> false
            }
        }

        if (editingId != -1L) load() else updateRepeatUi()
    }

    private fun setupPickers() {
        binding.pickerHour.setFormatter { String.format(Locale.US, "%02d", it) }
        binding.pickerMinute.setFormatter { String.format(Locale.US, "%02d", it) }
        if (editingId == -1L) {
            // 新建默认「下一个整点」：默认重复方式是「仅一次」，若默认 00:00 会一保存就已过
            val c = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
            binding.pickerHour.value = c.get(Calendar.HOUR_OF_DAY)
            binding.pickerMinute.value = 0
        }
        // 时分变化时刷新「仅一次」提示里写明的实际响铃时刻
        binding.pickerHour.setOnValueChangedListener { _, _, _ -> updateRepeatUi() }
        binding.pickerMinute.setOnValueChangedListener { _, _, _ -> updateRepeatUi() }
    }

    private fun setupRepeat() {
        updateIntervalText()

        binding.repeatRow.setOnClickListener { showRepeatDialog() }
        binding.btnMinus.setOnClickListener {
            if (intervalDays > 2) intervalDays -= 1
            updateIntervalText()
        }
        binding.btnPlus.setOnClickListener {
            if (intervalDays < 30) intervalDays += 1
            updateIntervalText()
        }

        binding.anchorLayout.setOnClickListener { pickAnchorDate() }

        val weekdayNames = resources.getStringArray(R.array.weekday_short)
        weekdayButtons.forEachIndexed { i, button ->
            button.text = weekdayNames[i]
            button.setOnClickListener {
                repeatDays = repeatDays xor (1 shl i)
                syncWeekdayButtons()
                updateRepeatUi()
            }
        }
    }

    /** 重复方式弹窗：单选列表，选中即生效；参数继续用下面的行调整。 */
    private fun showRepeatDialog() {
        // 下标 0..5 分别对应负值类型，下标 6 是「间隔重复」（正数天数为合法值）
        val fixedTypes = intArrayOf(
            Alarm.REPEAT_ONCE, Alarm.REPEAT_DAILY, Alarm.REPEAT_WORKDAY,
            Alarm.REPEAT_CUSTOM, Alarm.REPEAT_MONTHLY, Alarm.REPEAT_YEARLY
        )
        val labels = arrayOf(
            getString(R.string.repeat_once),
            getString(R.string.repeat_daily),
            getString(R.string.repeat_workday),
            getString(R.string.repeat_custom),
            getString(R.string.repeat_monthly),
            getString(R.string.repeat_yearly),
            getString(R.string.repeat_interval)
        )
        val checked = if (repeatType > 0) 6 else fixedTypes.indexOf(repeatType).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.repeat_row_label)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which == 6) {
                    repeatType = intervalDays
                } else {
                    repeatType = fixedTypes[which]
                    if (repeatType == Alarm.REPEAT_CUSTOM && repeatDays == 0) {
                        // 第一次切到自定义：预选周一至周五
                        repeatDays = Alarm.WEEKDAY_MASK_WORKDAYS
                    }
                }
                syncWeekdayButtons()
                updateRepeatUi()
                dialog.dismiss()
            }
            .show()
    }

    private fun syncWeekdayButtons() {
        weekdayButtons.forEachIndexed { i, button ->
            button.isSelected = repeatDays and (1 shl i) != 0
        }
    }

    /** 起始日期决定「哪天起算 / 几号 / 几月几日」，用系统日期选择器直接选。 */
    private fun pickAnchorDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                anchor.set(Calendar.YEAR, year)
                anchor.set(Calendar.MONTH, month)
                anchor.set(Calendar.DAY_OF_MONTH, day)
                updateRepeatUi()
            },
            anchor.get(Calendar.YEAR),
            anchor.get(Calendar.MONTH),
            anchor.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * 「仅一次」的实际触发时刻：起始日期当天；若该时刻已过则顺延到最早可响的一天。
     * 保存时写回 anchorTime，避免出现「开关亮着但永远不响」的假闹钟。
     */
    private fun effectiveOnceTime(): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = anchor.timeInMillis
            set(Calendar.HOUR_OF_DAY, binding.pickerHour.value)
            set(Calendar.MINUTE, binding.pickerMinute.value)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        var guard = 0
        while (c.timeInMillis <= now && guard++ < 400) {
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun updateRepeatUi() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        binding.tvAnchorDate.text = fmt.format(anchor.time)
        binding.tvRepeatSummary.text =
            repeatSummaryText(repeatType, anchor.timeInMillis, repeatDays)

        val isInterval = repeatType > 0
        binding.intervalLayout.visibility = if (isInterval) View.VISIBLE else View.GONE
        binding.weekdayLayout.visibility =
            if (repeatType == Alarm.REPEAT_CUSTOM) View.VISIBLE else View.GONE

        val needAnchor = repeatType == Alarm.REPEAT_ONCE || repeatType == Alarm.REPEAT_MONTHLY ||
                repeatType == Alarm.REPEAT_YEARLY || isInterval
        binding.anchorLayout.visibility = if (needAnchor) View.VISIBLE else View.GONE
        val needHint = needAnchor || repeatType == Alarm.REPEAT_CUSTOM
        binding.tvAnchorHint.visibility = if (needHint) View.VISIBLE else View.GONE
        binding.tvAnchorHint.text = when {
            repeatType == Alarm.REPEAT_MONTHLY ->
                getString(R.string.repeat_anchor_hint_monthly, anchor.get(Calendar.DAY_OF_MONTH))
            repeatType == Alarm.REPEAT_YEARLY -> getString(
                R.string.repeat_anchor_hint_yearly,
                anchor.get(Calendar.MONTH) + 1,
                anchor.get(Calendar.DAY_OF_MONTH)
            )
            repeatType == Alarm.REPEAT_ONCE -> getString(
                R.string.repeat_anchor_hint_once_fmt,
                describeDayTime(this, effectiveOnceTime(), System.currentTimeMillis())
            )
            isInterval -> getString(R.string.repeat_anchor_hint_interval)
            repeatType == Alarm.REPEAT_CUSTOM -> getString(R.string.repeat_custom_hint)
            else -> ""
        }
        // 工作日闹钟依赖节假日数据，顺带提示数据是否已同步
        binding.tvWorkdayTip.visibility =
            if (repeatType == Alarm.REPEAT_WORKDAY) View.VISIBLE else View.GONE
        binding.tvWorkdayTip.text = if (WorkdayCalendar.hasDataFor(Calendar.getInstance().get(Calendar.YEAR))) {
            getString(R.string.workday_data_ready, WorkdayCalendar.lastSyncText(this).orEmpty())
        } else {
            getString(R.string.workday_data_missing)
        }
    }

    private fun updateIntervalText() {
        binding.tvInterval.text = intervalDays.toString()
        if (repeatType > 0) repeatType = intervalDays
        updateRepeatUi()
    }

    private fun setupOptions() {
        binding.swVibrate.isChecked = true
        binding.swSound.isChecked = true
        // 稍后提醒：1~30 分钟，默认 5（区间与默认值在布局的 wheel 属性里）
        binding.pickerSnooze.setFormatter { it.toString() }
    }

    private fun load() {
        lifecycleScope.launch {
            val a = repo.getById(editingId) ?: run { finish(); return@launch }
            binding.pickerHour.value = a.hour
            binding.pickerMinute.value = a.minute
            binding.etLabel.setText(a.label)
            binding.swVibrate.isChecked = a.vibrate
            binding.swSound.isChecked = a.sound
            binding.swIntense.isChecked = a.intenseAlert
            binding.etNote.setText(a.specialNote)
            binding.pickerSnooze.value = a.snoozeMinutes.coerceIn(1, 30)
            if (a.anchorTime > 0) anchor.timeInMillis = a.anchorTime
            repeatDays = a.repeatDays
            when {
                a.repeatType == Alarm.REPEAT_ONCE -> repeatType = Alarm.REPEAT_ONCE
                a.repeatType == Alarm.REPEAT_DAILY -> repeatType = Alarm.REPEAT_DAILY
                a.repeatType == Alarm.REPEAT_WORKDAY -> repeatType = Alarm.REPEAT_WORKDAY
                a.repeatType == Alarm.REPEAT_CUSTOM -> repeatType = Alarm.REPEAT_CUSTOM
                a.repeatType == Alarm.REPEAT_MONTHLY -> repeatType = Alarm.REPEAT_MONTHLY
                a.repeatType == Alarm.REPEAT_YEARLY -> repeatType = Alarm.REPEAT_YEARLY
                a.repeatType > 0 -> repeatType = a.repeatType
                else -> repeatType = Alarm.REPEAT_ONCE
            }
            if (repeatType > 0) intervalDays = repeatType
            syncWeekdayButtons()
            updateIntervalText()
        }
    }

    private fun save() {
        val hour = binding.pickerHour.value
        val minute = binding.pickerMinute.value
        val label = binding.etLabel.text.toString().trim()
        val vibrate = binding.swVibrate.isChecked
        val sound = binding.swSound.isChecked
        val intense = binding.swIntense.isChecked
        val note = binding.etNote.text.toString().trim()
        val snooze = binding.pickerSnooze.value
        // 间隔型要把当前选中的天数写回 repeatType，否则还是进入页面时的旧值
        if (repeatType > 0) repeatType = intervalDays
        val daysMask = if (repeatType == Alarm.REPEAT_CUSTOM) repeatDays and Alarm.WEEKDAY_MASK_ALL else 0
        if (repeatType == Alarm.REPEAT_CUSTOM && daysMask == 0) {
            android.widget.Toast.makeText(this, R.string.repeat_custom_need_day, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 仅一次：所选时刻已过则顺延到最早可响的一天，提示里会写明实际日期
        val anchorMs = if (repeatType == Alarm.REPEAT_ONCE) effectiveOnceTime() else anchor.timeInMillis

        lifecycleScope.launch {
            val saved = if (editingId != -1L) {
                val existing = repo.getById(editingId) ?: return@launch
                // 编辑即视为重新设定：清掉待响的「延后再响」，它用的是独立 PendingIntent，schedule 顶不掉
                AlarmScheduler.cancelSnooze(applicationContext, existing.id)
                existing.copy(
                    hour = hour,
                    minute = minute,
                    label = label,
                    repeatType = repeatType,
                    anchorTime = anchorMs,
                    vibrate = vibrate,
                    sound = sound,
                    intenseAlert = intense,
                    specialNote = note,
                    snoozeMinutes = snooze,
                    repeatDays = daysMask,
                    enabled = true,   // 编辑保存后自动启用
                    skipTime = 0L,    // 主动编辑视为重新启用，清除「仅一次跳过」
                    snoozeUntil = 0L
                ).also {
                    repo.update(it)
                    AlarmScheduler.schedule(applicationContext, it)
                }
            } else {
                val alarm = Alarm(
                    hour = hour,
                    minute = minute,
                    enabled = true,
                    label = label,
                    repeatType = repeatType,
                    anchorTime = anchorMs,
                    vibrate = vibrate,
                    sound = sound,
                    createdAt = System.currentTimeMillis(),
                    snoozeMinutes = snooze,
                    intenseAlert = intense,
                    specialNote = note,
                    repeatDays = daysMask
                )
                val id = repo.insert(alarm)
                val withId = alarm.copy(id = id)
                AlarmScheduler.schedule(applicationContext, withId)
                withId
            }
            // 启动闹钟后提示一次「距离下次响铃还有 X」
            showSavedTip(saved)
        }
    }

    /** 保存成功：优先提示下次响铃时间；缺精确闹钟权限时改为引导授权。 */
    private fun showSavedTip(alarm: Alarm) {
        if (!AlarmScheduler.canScheduleExactAlarms(applicationContext)) {
            promptExactAlarmPermission()
            return
        }
        val next = AlarmScheduler.computeNextTrigger(alarm)
        val msg = if (next > 0) {
            val at = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(java.util.Date(next))
            getString(
                R.string.next_ring_tip,
                formatDurationUntil(next - System.currentTimeMillis()),
                at
            )
        } else {
            getString(R.string.alarm_saved)
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    private fun promptExactAlarmPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.need_exact_alarm_title)
            .setMessage(R.string.need_exact_alarm_msg)
            .setPositiveButton(R.string.go_grant) { _, _ ->
                startActivity(Intent(this, PermSettingsActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.save_anyway) { _, _ -> finish() }
            .show()
    }

    private fun delete() {
        if (editingId == -1L) { finish(); return }
        lifecycleScope.launch {
            val a = repo.getById(editingId)
            if (a != null) {
                AlarmScheduler.cancel(applicationContext, a)
                repo.delete(a)
            }
            finish()
        }
    }
}
