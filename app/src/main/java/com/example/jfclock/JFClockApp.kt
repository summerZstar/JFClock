package com.example.jfclock

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.PrintWriter
import java.io.StringWriter

class JFClockApp : Application() {

    val database by lazy { AlarmDatabase.get(this) }
    val repository by lazy { AlarmRepository(database.alarmDao()) }

    /**
     * 进程级协程作用域：响铃页点「稍后提醒」后立刻 finish，
     * 用 lifecycleScope 写库会被连同取消，延后状态就丢了。
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 工作日闹钟要在计算下次触发前就能判定，先装载本地缓存的节假日数据
        WorkdayCalendar.init(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            reportCrash(applicationContext, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

/**
 * 把异常堆栈复制到剪贴板（并在主线程尽量弹 Toast），方便用户粘贴反馈。
 * 在任意线程调用都安全。
 */
fun reportCrash(context: Context, t: Throwable) {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    val text = buildString {
        append("JFClock crash:\n")
        append(sw.toString().take(6000))
    }
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("JFClock-crash", text))
    } catch (_: Throwable) {
    }
    try {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context.applicationContext,
                "出错了，错误已复制到剪贴板，请粘贴反馈",
                Toast.LENGTH_LONG
            ).show()
        }
    } catch (_: Throwable) {
    }
}
