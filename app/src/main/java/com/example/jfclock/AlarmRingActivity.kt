package com.example.jfclock

import android.annotation.SuppressLint
import android.media.Ringtone
import android.app.NotificationManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityAlarmRingBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏响铃界面：在锁屏上也能显示，支持关闭 / 稍后提醒（5 分钟）。
 *
 * 增强提醒的闹钟把「关闭」改成长按，避免半睡状态一碰就把提醒抹掉。
 */
class AlarmRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var alarmId: Long = -1L
    private var snoozeMinutes = 5
    private var intense = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alarmId = intent.getLongExtra("alarmId", -1L)
        intense = intent.getBooleanExtra(RingService.EXTRA_INTENSE, false)

        // 锁屏上显示并点亮屏幕
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

        binding.tvTime.text = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        val app = application as JFClockApp
        lifecycleScope.launch {
            val a = app.repository.getById(alarmId)
            binding.tvLabel.text = a?.label?.takeIf { it.isNotBlank() } ?: getString(R.string.new_alarm)
            snoozeMinutes = a?.snoozeMinutes?.coerceIn(1, 30) ?: 5
            binding.btnSnooze.text = getString(R.string.snooze_fmt, snoozeMinutes)
            // 通知栏点进来时不带 extra，从数据库补齐这两项
            intense = intense || a?.intenseAlert == true
            showNote(intent.getStringExtra(RingService.EXTRA_NOTE).orEmpty().ifBlank { a?.specialNote.orEmpty() })
            bindDismiss()
            // 前台服务已在响铃时，界面不再重复播放
            if (!RingService.ringing) startFeedback(a?.sound != false, a?.vibrate != false)
        }

        binding.btnSnooze.setOnClickListener { snooze() }
        bindDismiss()
    }

    /** 增强提醒要把按钮换成「长按关闭」，等数据库补齐 intense 后可能再调一次。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindDismiss() {
        val button = binding.btnDismiss
        if (!intense) {
            button.setOnTouchListener(null)
            button.setOnClickListener { dismiss() }
            return
        }
        button.setText(R.string.ring_dismiss_hold)
        button.setOnClickListener(null)
        val press = Runnable { dismiss() }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.postDelayed(press, HOLD_DISMISS_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(press)
                    true
                }
                else -> false
            }
        }
    }

    /** 特殊提醒（吃什么药 / 接孩子）在响铃界面大字居中显示。 */
    private fun showNote(note: String) {
        binding.tvNote.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
        binding.tvNote.text = note
    }

    /** 用户已处理本次响铃：停服务、停本机播放、撤通知。 */
    private fun finishRinging() {
        stopFeedback()
        RingService.stop(this)
        getSystemService(NotificationManager::class.java)
            .cancel(AlarmReceiver.notifyReqCode(alarmId))
    }

    private fun startFeedback(playSound: Boolean, doVibrate: Boolean) {
        if (playSound) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
        }
        if (doVibrate) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            // 增强提醒震动改长脉冲（规格 §8）；渐强依赖 MediaPlayer，仅前台服务路径提供
            val pattern = if (intense) longArrayOf(0, 900, 250) else longArrayOf(0, 600, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopFeedback() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun dismiss() {
        finishRinging()
        finish()
    }

    /**
     * 稍后提醒：另排一条一次性闹钟，并把再响时刻写进 snoozeUntil，
     * 这样主界面列表能看出「这个闹钟延后了、还有多久再响」。
     */
    private fun snooze() {
        val triggerAt = System.currentTimeMillis() + snoozeMinutes * 60_000L
        finishRinging()
        AlarmScheduler.scheduleSnooze(this, alarmId, triggerAt)
        // 页面马上 finish，写库要用进程级作用域，否则随 lifecycleScope 一起被取消
        val app = application as JFClockApp
        app.appScope.launch {
            app.repository.getById(alarmId)?.let {
                app.repository.update(it.copy(snoozeUntil = triggerAt))
            }
        }
        android.widget.Toast.makeText(
            this,
            getString(R.string.alarm_snooze_toast_fmt, formatDurationUntil(
                triggerAt - System.currentTimeMillis())),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }

    companion object {
        /** 增强提醒下「长按关闭」的判定时长。 */
        private const val HOLD_DISMISS_MS = 1200L
    }
}
