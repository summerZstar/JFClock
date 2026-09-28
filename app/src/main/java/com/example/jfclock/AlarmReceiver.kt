package com.example.jfclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟触发广播。
 * 1. 拉起全屏响铃界面 AlarmRingActivity
 * 2. 重新安排下一次触发（仅一次闹钟则置为关闭）
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("alarmId", -1)
        if (id == -1L) return

        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alarmId", id)
        }
        context.startActivity(ringIntent)

        val app = context.applicationContext as JFClockApp
        CoroutineScope(Dispatchers.IO).launch {
            val alarm = app.repository.getById(id) ?: return@launch
            if (!alarm.enabled) return@launch
            if (alarm.repeatType == -1) {
                // 仅一次：触发后关闭
                app.repository.update(alarm.copy(enabled = false))
            }
            AlarmScheduler.schedule(context.applicationContext, alarm)
        }
    }
}
