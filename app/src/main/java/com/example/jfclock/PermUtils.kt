package com.example.jfclock

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 后台响铃所需权限的检测与系统设置页跳转。
 *
 * 说明：OPPO/ColorOS 的「自启动」没有公开直达 intent，只能尝试
 * 安全中心组件、失败后退到应用详情页，并配文字指引。
 */
object PermUtils {

    fun isNotificationEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isExactAlarmEnabled(context: Context): Boolean =
        AlarmScheduler.canScheduleExactAlarms(context)

    /** Android 14+ 全屏意图权限；隐藏 API 反射查询，失败时视为可用。 */
    fun isFullScreenIntentEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val m = nm.javaClass.getMethod("isFullScreenIntentAllowed")
            m.invoke(nm) as? Boolean ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun isDndAccessEnabled(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    /** 后台/锁屏响铃的关键权限是否齐备（不含可选的勿扰与自启动）。 */
    fun allKeyPermsGranted(context: Context): Boolean =
        isNotificationEnabled(context) &&
                isExactAlarmEnabled(context) &&
                isFullScreenIntentEnabled(context)

    /** ColorOS 自启动状态：从安全中心 SharedPreferences 读取，读不到返回 null（未知）。 */
    fun isOppoAutoStartEnabled(context: Context): Boolean? {
        if (!isOppoFamily()) return null
        return try {
            val sp = context.applicationContext
                .createPackageContext("com.coloros.safecenter", Context.CONTEXT_IGNORE_SECURITY)
                .getSharedPreferences("selfstart_list", Context.MODE_PRIVATE)
            when (sp.getInt(context.packageName, -1)) {
                1 -> true
                0 -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isOppoFamily(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("oppo") || m.contains("oneplus") ||
                m.contains("realme") || m.contains("coloros")
    }

    /** 依次尝试打开 intent，全部失败返回 false。 */
    fun open(context: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 通知渠道设置页（Android 14+ 的「全屏提醒」开关在渠道页内）。 */
    fun channelSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmReceiver.CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun dndSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** ColorOS 自启动管理页候选组件（无公开 API，逐个尝试）。 */
    fun oppoAutoStartIntents(context: Context): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent().setComponent(ComponentName("com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent().setComponent(ComponentName("com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
