package com.example.jfclock

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
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
 */
class AlarmRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var alarmId: Long = -1L
    private var snoozeMinutes = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        alarmId = intent.getLongExtra("alarmId", -1L)

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
            binding.tvLabel.text = a?.label?.takeIf { it.isNotBlank() } ?: getString(R.string.ring_dismiss)
            snoozeMinutes = a?.snoozeMinutes?.coerceIn(1, 30) ?: 5
            binding.btnSnooze.text = getString(R.string.snooze_fmt, snoozeMinutes)
            startFeedback(a?.sound != false, a?.vibrate != false)
        }

        binding.btnDismiss.setOnClickListener { dismiss() }
        binding.btnSnooze.setOnClickListener { snooze() }
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
            val pattern = longArrayOf(0, 600, 400)
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
        stopFeedback()
        finish()
    }

    private fun snooze() {
        stopFeedback()
        // 5 分钟后再次触发，复用 AlarmReceiver
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            this,
            9000 + alarmId.toInt(),
            android.content.Intent(this, AlarmReceiver::class.java).putExtra("alarmId", alarmId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + snoozeMinutes * 60 * 1000
        val show = android.app.PendingIntent.getActivity(
            this,
            9000 + alarmId.toInt() + 100000,
            android.content.Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(triggerAt, show), pi)
        finish()
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }
}
