package com.example.jfclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 时间变更后重新安排所有启用的闹钟。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED
        ) {
            val app = context.applicationContext as JFClockApp
            CoroutineScope(Dispatchers.IO).launch {
                val alarms = app.repository.getAll()
                AlarmScheduler.rescheduleAll(context.applicationContext, alarms)
            }
        }
    }
}
