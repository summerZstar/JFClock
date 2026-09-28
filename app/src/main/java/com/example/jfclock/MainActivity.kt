package com.example.jfclock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.jfclock.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AlarmAdapter
    private val repo by lazy { (application as JFClockApp).repository }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响前台响铃，仅影响全屏通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 强制浅色状态栏（深色图标），部分 OEM 不响应主题属性
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        // Android 13+ 需要运行时申请通知权限，否则全屏闹钟通知会被静默丢弃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        adapter = AlarmAdapter(
            onToggle = { alarm, enabled ->
                if (!enabled && alarm.repeatType != -1) {
                    // 关闭重复闹钟：参考 ColorOS，询问是仅跳过一次还是彻底关闭
                    askDisableRecurring(alarm)
                } else {
                    lifecycleScope.launch {
                        val updated = alarm.copy(
                            enabled = enabled,
                            skipTime = if (enabled) 0L else alarm.skipTime
                        )
                        repo.update(updated)
                        AlarmScheduler.schedule(applicationContext, updated)
                    }
                }
            },
            onItemClick = { openEdit(it) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { openEdit(null) }
        binding.btnEggTimer.setOnClickListener {
            startActivity(Intent(this, EggTimerActivity::class.java))
        }

        // 顶部时钟显示到秒
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    binding.tvClock.text = timeFmt.format(Date())
                    delay(250)
                }
            }
        }

        lifecycleScope.launch {
            repo.allAlarms.collectLatest { list ->
                adapter.submitList(list)
                val hasItems = list.isNotEmpty()
                binding.recycler.visibility = if (hasItems) android.view.View.VISIBLE else android.view.View.GONE
                binding.emptyView.visibility = if (hasItems) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    /** 关闭重复闹钟时的三选项弹窗：仅某日关闭一次 / 彻底关闭 / 取消。 */
    private fun askDisableRecurring(alarm: Alarm) {
        val next = AlarmScheduler.computeNextTrigger(alarm.copy(skipTime = 0L))
        if (next == -1L) return
        val dateText = SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(next))
        val options = arrayOf(
            getString(R.string.skip_once_fmt, dateText),
            getString(R.string.skip_disable)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.skip_dialog_title)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val updated = if (which == 0) {
                        // 保持开启，仅跳过下一次触发
                        alarm.copy(skipTime = next)
                    } else {
                        alarm.copy(enabled = false, skipTime = 0L)
                    }
                    repo.update(updated)
                    AlarmScheduler.schedule(applicationContext, updated)
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                // 用户取消：刷新列表让开关回到开启状态
                binding.recycler.adapter?.notifyDataSetChanged()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页返回：若已授予精确闹钟权限，把所有闹钟重排为精确触发
        if (AlarmScheduler.canScheduleExactAlarms(applicationContext)) {
            lifecycleScope.launch {
                AlarmScheduler.rescheduleAll(applicationContext, repo.getAll())
            }
        }
    }

    private fun openEdit(alarm: Alarm?) {
        val intent = Intent(this, AlarmEditActivity::class.java)
        alarm?.let { intent.putExtra("alarmId", it.id) }
        startActivity(intent)
    }
}
