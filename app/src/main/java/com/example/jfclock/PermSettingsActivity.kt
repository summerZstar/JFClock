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
import kotlinx.coroutines.launch

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
            { PermUtils.open(it, PermUtils.channelSettingsIntent(it), PermUtils.notificationSettingsIntent(it)) }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(Item(R.string.perm_dnd_title, R.string.perm_dnd_desc,
                { PermUtils.isDndAccessEnabled(it) },
                { PermUtils.open(it, PermUtils.dndSettingsIntent()) }))
        }
        if (PermUtils.isOppoFamily()) {
            add(Item(R.string.perm_autostart_title, R.string.perm_autostart_desc,
                { PermUtils.isOppoAutoStartEnabled(it) },
                { PermUtils.open(it, *PermUtils.oppoAutoStartIntents(it).toTypedArray()) }))
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
            val row = LayoutInflater.from(this).inflate(R.layout.item_perm_row, container, false)
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
                if (!item.go(this)) {
                    android.widget.Toast.makeText(
                        this, R.string.perm_page_unavailable, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            container.addView(row)
            if (index != items.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.marginStart = dp(20) }
                    setBackgroundColor(ContextCompat.getColor(this@PermSettingsActivity, R.color.light_divider))
                }
                container.addView(divider)
            }
        }
        if (missing == 0) {
            binding.tvSummary.setText(R.string.perm_all_ok)
            binding.tvSummary.visibility = View.VISIBLE
        } else {
            binding.tvSummary.visibility = View.GONE
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
