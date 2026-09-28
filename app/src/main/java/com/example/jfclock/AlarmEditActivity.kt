package com.example.jfclock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityAlarmEditBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 新建 / 编辑闹钟。
 *
 * 重复方式支持：仅一次、每天、以及「固定间隔天数」（每隔 N 天）。
 */
class AlarmEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmEditBinding
    private val repo by lazy { (application as JFClockApp).repository }

    private var editingId: Long = -1L
    private var repeatType: Int = 2          // 默认：每隔 2 天，突出间隔天数功能
    private var intervalDays: Int = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 强制浅色状态栏（深色图标），部分 OEM 不响应主题属性
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        editingId = intent.getLongExtra("alarmId", -1L)

        setupPickers()
        setupRepeat()
        setupOptions()

        binding.toolbar.title = if (editingId != -1L) getString(R.string.edit_alarm) else getString(R.string.new_alarm)
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

        if (editingId != -1L) load()
    }

    private fun setupPickers() {
        binding.pickerHour.minValue = 0
        binding.pickerHour.maxValue = 23
        binding.pickerHour.setFormatter { String.format(Locale.US, "%02d", it) }
        binding.pickerMinute.minValue = 0
        binding.pickerMinute.maxValue = 59
        binding.pickerMinute.setFormatter { String.format(Locale.US, "%02d", it) }
        // ColorOS 风格：不显示分隔线之间的文字选择框
        binding.pickerHour.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        binding.pickerMinute.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
    }

    private fun setupRepeat() {
        // 默认选中「间隔重复」
        binding.rbInterval.isChecked = true
        binding.intervalLayout.visibility = View.VISIBLE
        updateIntervalText()

        binding.repeatGroup.setOnCheckedChangeListener { _, checkedId ->
            repeatType = when (checkedId) {
                R.id.rbOnce -> -1
                R.id.rbDaily -> 0
                else -> intervalDays
            }
            binding.intervalLayout.visibility =
                if (checkedId == R.id.rbInterval) View.VISIBLE else View.GONE
        }

        binding.btnMinus.setOnClickListener {
            if (intervalDays > 2) intervalDays -= 1
            updateIntervalText()
        }
        binding.btnPlus.setOnClickListener {
            if (intervalDays < 30) intervalDays += 1
            updateIntervalText()
        }
    }

    private fun updateIntervalText() {
        binding.tvInterval.text = intervalDays.toString()
        if (binding.rbInterval.isChecked) repeatType = intervalDays
    }

    private fun setupOptions() {
        binding.swVibrate.isChecked = true
        binding.swSound.isChecked = true
        // 稍后提醒：1~30 分钟，默认 5
        binding.pickerSnooze.minValue = 1
        binding.pickerSnooze.maxValue = 30
        binding.pickerSnooze.value = 5
        binding.pickerSnooze.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        binding.pickerSnooze.wrapSelectorWheel = true
    }

    private fun load() {
        lifecycleScope.launch {
            val a = repo.getById(editingId) ?: run { finish(); return@launch }
            binding.pickerHour.value = a.hour
            binding.pickerMinute.value = a.minute
            binding.etLabel.setText(a.label)
            binding.swVibrate.isChecked = a.vibrate
            binding.swSound.isChecked = a.sound
            binding.pickerSnooze.value = a.snoozeMinutes.coerceIn(1, 30)
            when {
                a.repeatType == -1 -> { binding.rbOnce.isChecked = true }
                a.repeatType == 0 -> { binding.rbDaily.isChecked = true }
                else -> {
                    intervalDays = a.repeatType
                    binding.rbInterval.isChecked = true
                    updateIntervalText()
                }
            }
        }
    }

    private fun save() {
        val hour = binding.pickerHour.value
        val minute = binding.pickerMinute.value
        val label = binding.etLabel.text.toString().trim()
        val vibrate = binding.swVibrate.isChecked
        val sound = binding.swSound.isChecked
        val snooze = binding.pickerSnooze.value
        val anchor = dayStartNow()

        lifecycleScope.launch {
            if (editingId != -1L) {
                val existing = repo.getById(editingId)
                if (existing != null) {
                    val updated = existing.copy(
                        hour = hour,
                        minute = minute,
                        label = label,
                        repeatType = repeatType,
                        vibrate = vibrate,
                        sound = sound,
                        snoozeMinutes = snooze,
                        anchorTime = if (existing.anchorTime == 0L) anchor else existing.anchorTime
                    )
                    repo.update(updated)
                    AlarmScheduler.schedule(applicationContext, updated)
                }
            } else {
                val alarm = Alarm(
                    hour = hour,
                    minute = minute,
                    enabled = true,
                    label = label,
                    repeatType = repeatType,
                    anchorTime = anchor,
                    vibrate = vibrate,
                    sound = sound,
                    createdAt = System.currentTimeMillis(),
                    snoozeMinutes = snooze
                )
                val id = repo.insert(alarm)
                AlarmScheduler.schedule(applicationContext, alarm.copy(id = id))
            }
            // 精确闹钟权限被系统拒绝时，引导用户去授权（闹钟仍会保存，只是可能略有延迟）
            if (!AlarmScheduler.canScheduleExactAlarms(applicationContext)) {
                promptExactAlarmPermission()
            } else {
                finish()
            }
        }
    }

    private fun promptExactAlarmPermission() {
        AlertDialog.Builder(this)
            .setTitle("需要「闹钟和提醒」权限")
            .setMessage("系统未授予精确闹钟权限，闹钟可能不准时响铃。\n点击「去授权」后在列表中允许本应用的「闹钟和提醒」。")
            .setPositiveButton("去授权") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } catch (_: Exception) {
                    // 部分机型无此设置页，忽略
                }
                finish()
            }
            .setNegativeButton("仍然保存") { _, _ -> finish() }
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
