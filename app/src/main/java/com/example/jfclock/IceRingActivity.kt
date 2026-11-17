package com.example.jfclock

import android.app.NotificationManager
import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.jfclock.databinding.ActivityEggRingBinding

/**
 * 冰浴完成响铃页：复用煮蛋响铃的界面与配色，锁屏上显示并点亮屏幕，支持关闭 / 延长 1 分钟。
 */
class IceRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEggRingBinding
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var label: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEggRingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        label = intent.getStringExtra(IceReceiver.EXTRA_LABEL)
            ?: IceStore.label(this).ifBlank { getString(R.string.ice_done_title) }
        binding.tvTitle.setText(R.string.ice_done_title)
        binding.tvLabel.text = label
        binding.tvTip.setText(R.string.ice_done_tip)
        binding.btnExtend.setText(R.string.ice_extend_1min)

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
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        if (!RingService.ringing) startFeedback()

        binding.btnDismiss.setOnClickListener { dismiss() }
        binding.btnExtend.setOnClickListener { extendOneMinute() }
    }

    private fun finishRinging() {
        stopFeedback()
        RingService.stop(this)
        getSystemService(NotificationManager::class.java).cancel(IceReceiver.NOTIFY_ID)
    }

    private fun startFeedback() {
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

    private fun dismiss() {
        finishRinging()
        IceStore.clear(this)
        finish()
    }

    /** 延长 1 分钟：重新排程并回到计时状态。 */
    private fun extendOneMinute() {
        finishRinging()
        val endAt = System.currentTimeMillis() + 60_000L
        IceStore.save(this, endAt, 60_000L, label)
        IceScheduler.schedule(this, endAt, label)
        finish()
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }
}
