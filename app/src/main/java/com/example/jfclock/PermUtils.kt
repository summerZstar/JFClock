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

    /**
     * Android 14+ 全屏提醒是否可用。
     *
     * 用公开 API [NotificationManager.canUseFullScreenIntent]；早先靠反射隐藏方法，
     * 反射失败会误报「已开启」，导致该给的兜底提示没给。
     */
    fun isFullScreenIntentEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } catch (_: Exception) {
            false
        }
    }

    /** 「显示在其他应用上层」：授予后应用可从后台直接拉起界面（OPPO 常靠这条）。 */
    fun isOverlayEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)

    fun isDndAccessEnabled(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    /** 是否已加入电池优化白名单（ColorOS 清理后台后仍能触发闹钟的关键设置）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (_: Exception) {
            false
        }
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

    /** Android 14+ 的「全屏提醒」授权页（系统设置里单独一项）。 */
    fun fullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 34) return null
        // 用字面量而非 Settings 常量，避免不同 SDK 版本缺失常量导致编译失败
        return Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT")
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 「显示在其他应用上层」授权页。 */
    fun overlaySettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * ColorOS 每应用「特殊应用权限」页候选（含悬浮窗/后台弹出界面/发送全屏通知等，
     * 见 设置 → 应用 → 特殊应用权限）。无公开 API，逐个尝试。
     * 注意：这里【不含】兜底页——兜底由设置页统一处理（退到应用详情页，
     * 「其他权限 → 特殊权限」就在应用信息里）。
     * 「锁屏显示」「后台弹出界面」两行共用这条链。
     */
    fun oppoPermissionDetailsIntents(context: Context): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.singlepage.PermissionDetailsActivity"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("package_name", context.packageName),
        Intent().setComponent(
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.singlepage.PermissionDetailsActivity"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("package_name", context.packageName)
    )

    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun dndSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 电池优化白名单：先尝试直接弹授权对话框，失败退到系统白名单列表页。 */
    fun batteryOptimizationIntents(context: Context): List<Intent> {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val listPage = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return listOf(direct, listPage)
    }

    /**
     * ColorOS 自启动管理页候选（无公开 API，逐个尝试；ColorOS 12+ 多在 oplus.battery/safecenter）。
     * 不含兜底页——失败时由 [settingsApplicationsIntents] 退到「设置 → 应用」。
     */
    fun oppoAutoStartIntents(context: Context): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.oplus.battery",
            "com.oplus.battery.startup.startup.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent().setComponent(ComponentName("com.oplus.safecenter",
            "com.oplus.safecenter.startupapp.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent().setComponent(ComponentName("com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent().setComponent(ComponentName("com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent().setComponent(ComponentName("com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /**
     * 「设置 → 应用」页（自启动就挂在这一级下面）。
     *
     * 不能用 ACTION_MANAGE_APPLICATIONS_SETTINGS：那是应用列表，比「应用」页低一层，
     * 从那里根本走不到自启动。私有组件试不通就停在设置首页，让用户自己往下点。
     */
    fun settingsApplicationsIntents(): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.android.settings",
            "com.android.settings.Settings\$ApplicationsSettingsActivity")),
        Intent().setComponent(ComponentName("com.oplus.settings",
            "com.oplus.settings.Settings\$ApplicationsSettingsActivity")),
        Intent(Settings.ACTION_SETTINGS)
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
