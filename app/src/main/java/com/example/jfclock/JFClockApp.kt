package com.example.jfclock

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

class JFClockApp : Application() {

    val database by lazy { AlarmDatabase.get(this) }
    val repository by lazy { AlarmRepository(database.alarmDao()) }

    override fun onCreate() {
        super.onCreate()
        setupCrashReporter()
    }

    /**
     * 崩溃时把堆栈复制到剪贴板（并尽量弹 Toast 提示），
     * 便于用户在任意输入框粘贴、反馈给开发者。
     */
    private fun setupCrashReporter() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("JFClock crash @ thread=").append(thread.name).append('\n')
                    append(sw.toString().take(6000))
                }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("JFClock-crash", text))
                Toast.makeText(
                    applicationContext,
                    "出错了：错误信息已复制到剪贴板，请在聊天/备忘录中粘贴反馈",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
