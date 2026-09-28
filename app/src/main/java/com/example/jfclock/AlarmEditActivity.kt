package com.example.jfclock

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.NumberPicker
import androidx.appcompat.app.AppCompatActivity
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
    }

    private fun load() {
        lifecycleScope.launch {
            val a = repo.getById(editingId) ?: run { finish(); return@launch }
            binding.pickerHour.value = a.hour
            binding.pickerMinute.value = a.minute
            binding.etLabel.setText(a.label)
            binding.swVibrate.isChecked = a.vibrate
            binding.swSound.isChecked = a.sound
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
                    createdAt = System.currentTimeMillis()
                )
                val id = repo.insert(alarm)
                AlarmScheduler.schedule(applicationContext, alarm.copy(id = id))
            }
            finish()
        }
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
