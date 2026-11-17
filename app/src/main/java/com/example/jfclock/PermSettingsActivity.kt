package com.example.jfclock

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityPermSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 应用内权限设置页：逐项展示后台响铃所需权限状态，点击直达对应系统设置页。
 */
class PermSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermSettingsBinding

    private class Item(
        val title: Int,
        val desc: Int,
        val check: (android.content.Context) -> Boolean?,
        val go: (android.content.Context) -> Boolean
    )

    private val items by lazy { buildList {
        add(Item(R.string.perm_notif_title, R.string.perm_notif_desc,
            { PermUtils.isNotificationEnabled(it) },
            { PermUtils.open(it, PermUtils.notificationSettingsIntent(it), PermUtils.appDetailsIntent(it)) }))
        add(Item(R.string.perm_exact_title, R.string.perm_exact_desc,
            { PermUtils.isExactAlarmEnabled(it) },
            { c ->
                val exact = PermUtils.exactAlarmSettingsIntent(c)
                PermUtils.open(c, *listOfNotNull(exact).plus(PermUtils.appDetailsIntent(c)).toTypedArray())
            }))
        add(Item(R.string.perm_fs_title, R.string.perm_fs_desc,
            { if (Build.VERSION.SDK_INT >= 34) PermUtils.isFullScreenIntentEnabled(it) else true },
            { c ->
                val chain = listOfNotNull(
                    PermUtils.fullScreenIntentSettingsIntent(c),
                    PermUtils.channelSettingsIntent(c),
                    PermUtils.notificationSettingsIntent(c),
                    PermUtils.appDetailsIntent(c)
                )
                PermUtils.open(c, *chain.toTypedArray())
            }))
        add(Item(R.string.perm_popup_title, R.string.perm_popup_desc,
            { null }, // ColorOS 私有开关，无公开 API 读取状态
            { c ->
                // 特殊应用权限页打不开时退到应用详情：开关就在 应用信息 → 其他权限 → 特殊权限
                val chain = PermUtils.oppoPermissionDetailsIntents(c) + PermUtils.appDetailsIntent(c)
                PermUtils.open(c, *chain.toTypedArray())
            }))
        add(Item(R.string.perm_overlay_title, R.string.perm_overlay_desc,
            { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PermUtils.isOverlayEnabled(it) else true },
            { c ->
                val chain = listOfNotNull(PermUtils.overlaySettingsIntent(c)) +
                        PermUtils.appDetailsIntent(c)
                PermUtils.open(c, *chain.toTypedArray())
            }))
        add(Item(R.string.perm_lockscreen_title, R.string.perm_lockscreen_desc,
            { null }, // 厂商「锁屏显示」无公开 API 可读，只能提示用户手动确认
            { c ->
                // 与「后台弹出界面」同一张特殊权限页，打不开同样退应用详情
                val chain = PermUtils.oppoPermissionDetailsIntents(c) + PermUtils.appDetailsIntent(c)
                PermUtils.open(c, *chain.toTypedArray())
            }))
        add(Item(R.string.perm_battery_title, R.string.perm_battery_desc,
            { PermUtils.isIgnoringBatteryOptimizations(it) },
            { c -> PermUtils.open(c, *PermUtils.batteryOptimizationIntents(c).toTypedArray()) }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(Item(R.string.perm_dnd_title, R.string.perm_dnd_desc,
                { PermUtils.isDndAccessEnabled(it) },
                { PermUtils.open(it, PermUtils.dndSettingsIntent()) }))
        }
        if (PermUtils.isOppoFamily()) {
            add(Item(R.string.perm_autostart_title, R.string.perm_autostart_desc,
                { PermUtils.isOppoAutoStartEnabled(it) },
                { c ->
                    // 自启动管理页 → 设置里的「应用」页 → 设置首页（不用应用列表，层级不对）
                    val chain = PermUtils.oppoAutoStartIntents(c) +
                            PermUtils.settingsApplicationsIntents()
                    PermUtils.open(c, *chain.toTypedArray())
                }))
        }
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyStatusBarAppearance(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        renderRows()
        // 授权返回后把所有闹钟重排为当前可用的最高精度
        lifecycleScope.launch {
            val repo = (application as JFClockApp).repository
            AlarmScheduler.rescheduleAll(applicationContext, repo.getAll())
        }
    }

    private fun renderRows() {
        val container = binding.permContainer
        container.removeAllViews()
        var missing = 0
        items.forEachIndexed { index, item ->
            val row = newRow()
            row.findViewById<android.widget.TextView>(R.id.tvTitle).setText(item.title)
            row.findViewById<android.widget.TextView>(R.id.tvDesc).setText(item.desc)
            val status = row.findViewById<android.widget.TextView>(R.id.tvStatus)
            when (item.check(this)) {
                true -> {
                    status.setText(R.string.perm_status_granted)
                    status.setTextColor(ContextCompat.getColor(this, R.color.light_text_secondary))
                }
                false -> {
                    status.setText(R.string.perm_status_denied)
                    status.setTextColor(ContextCompat.getColor(this, R.color.light_accent))
                    missing++
                }
                null -> {
                    status.setText(R.string.perm_status_manual)
                    status.setTextColor(ContextCompat.getColor(this, R.color.light_accent))
                }
            }
            row.setOnClickListener {
                // 每项的 go 链末尾都带自己的兜底页（应用详情 / 设置首页），
                // 兜底页也打不开才什么都不做，不弹「手动设置」提示打断用户
                item.go(this)
            }
            container.addView(row)
            if (index != items.lastIndex) {
                container.addView(divider())
            }
        }
        if (missing == 0) {
            binding.tvSummary.setText(R.string.perm_all_ok)
            binding.tvSummary.visibility = View.VISIBLE
        } else {
            binding.tvSummary.visibility = View.GONE
        }
        container.addView(divider())
        container.addView(workdayRow())
    }

    private fun newRow(): View =
        LayoutInflater.from(this).inflate(R.layout.item_perm_row, binding.permContainer, false)

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.marginStart = dp(20) }
        setBackgroundColor(ContextCompat.getColor(this@PermSettingsActivity, R.color.light_divider))
    }

    /**
     * 工作日闹钟依赖的法定节假日数据：点击立即同步，长按可换成自己的数据地址。
     * 这项不属于系统权限，所以不计进顶部的权限缺失统计。
     */
    private fun workdayRow(): View {
        val row = newRow()
        val title = row.findViewById<android.widget.TextView>(R.id.tvTitle)
        val desc = row.findViewById<android.widget.TextView>(R.id.tvDesc)
        val status = row.findViewById<android.widget.TextView>(R.id.tvStatus)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val ready = WorkdayCalendar.hasDataFor(year)

        title.setText(R.string.perm_workday_title)
        desc.text = if (ready) {
            val paper = WorkdayCalendar.paperUrl(this, year)
            getString(
                R.string.perm_workday_ready_fmt,
                WorkdayCalendar.lastSyncText(this) ?: "-",
                paper?.takeIf { it.isNotBlank() } ?: getString(R.string.perm_workday_default_source)
            )
        } else {
            getString(R.string.perm_workday_missing)
        }
        status.text = getString(
            if (ready) R.string.perm_workday_synced else R.string.perm_workday_sync_now
        )
        status.setTextColor(
            ContextCompat.getColor(
                this,
                if (ready) R.color.light_text_secondary else R.color.light_accent
            )
        )
        row.setOnClickListener { syncCalendar() }
        row.setOnLongClickListener {
            editCustomSource()
            true
        }
        return row
    }

    private fun syncCalendar() {
        android.widget.Toast.makeText(this, R.string.perm_workday_syncing,
            android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                try {
                    WorkdayCalendar.refresh(applicationContext)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (updated.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    AlarmScheduler.rescheduleAll(
                        applicationContext,
                        (application as JFClockApp).repository.getAll()
                    )
                }
            }
            android.widget.Toast.makeText(
                this@PermSettingsActivity,
                if (updated.isEmpty()) getString(R.string.perm_workday_sync_fail)
                else getString(R.string.perm_workday_sync_ok, updated.joinToString("、")),
                android.widget.Toast.LENGTH_LONG
            ).show()
            renderRows()
        }
    }

    /** 数据源可替换：网络受限的手机可以填自己的镜像地址，{year} 会换成年份。 */
    private fun editCustomSource() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.perm_workday_url_hint)
            setText(WorkdayCalendar.customUrl(this@PermSettingsActivity))
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_workday_url_title)
            .setMessage(R.string.perm_workday_url_desc)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                WorkdayCalendar.setCustomUrl(this, input.text.toString())
                renderRows()
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
