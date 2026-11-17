package com.example.jfclock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.jfclock.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AlarmAdapter
    private val repo by lazy { (application as JFClockApp).repository }
    private var tipShown = false
    /** 最近一次列表数据：标题行「提醒」按秒刷新时复用，不必每秒重查库。 */
    private var alarms: List<Alarm> = emptyList()
    /** 「提醒」当前指向的触发时刻；过了或列表变了才重算。 */
    private var reminderAt: Long? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响前台响铃，仅影响全屏通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarAppearance(this)

        // Android 13+ 需要运行时申请通知权限，否则全屏闹钟通知会被静默丢弃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        adapter = AlarmAdapter(
            onToggle = { alarm, enabled ->
                if (!enabled && alarm.repeatType != Alarm.REPEAT_ONCE) {
                    // 关闭重复闹钟：参考 ColorOS，询问是仅跳过一次还是彻底关闭
                    askDisableRecurring(alarm)
                } else {
                    lifecycleScope.launch {
                        val updated = alarm.copy(
                            enabled = enabled,
                            skipTime = if (enabled) 0L else alarm.skipTime,
                            snoozeUntil = 0L
                        )
                        repo.update(updated)
                        AlarmScheduler.schedule(applicationContext, updated)
                        if (enabled) {
                            // 启动闹钟后提示一次「距离下次响铃还有 X」
                            val next = AlarmScheduler.computeNextTrigger(updated)
                            if (next > 0) {
                                tipShown = true
                                toastNextRing(next)
                            }
                        }
                    }
                }
            },
            onItemClick = { openEdit(it) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { openEdit(null) }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, PermSettingsActivity::class.java))
        }
        binding.tvPermWarn.setOnClickListener {
            startActivity(Intent(this, PermSettingsActivity::class.java))
        }
        setupBottomNav()

        // 首次启动：引导用户开启后台响铃所需权限
        val guidePrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!guidePrefs.getBoolean("perm_guide_shown", false)) {
            guidePrefs.edit().putBoolean("perm_guide_shown", true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_guide_title)
                .setMessage(R.string.perm_guide_msg)
                .setPositiveButton(R.string.perm_guide_go) { _, _ ->
                    startActivity(Intent(this, PermSettingsActivity::class.java))
                }
                .setNegativeButton(R.string.perm_guide_later, null)
                .show()
        }

        // 顶部时钟显示到秒；卡片上的「下次响铃」按分钟级刷新，「稍后提醒」按秒
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val now = System.currentTimeMillis()
                    binding.tvClock.text = timeFmt.format(Date(now))
                    refreshReminder(now)
                    adapter.refreshStatusLines(now)
                    // 对齐到下一个整秒再睡：不用 250ms 空转跑逻辑
                    delay(1000 - now % 1000)
                }
            }
        }

        lifecycleScope.launch {
            repo.allAlarms.collectLatest { list ->
                adapter.submitList(list)
                alarms = list
                refreshReminder(System.currentTimeMillis())
                val hasItems = list.isNotEmpty()
                binding.recycler.visibility = if (hasItems) android.view.View.VISIBLE else android.view.View.GONE
                binding.emptyView.visibility = if (hasItems) android.view.View.GONE else android.view.View.VISIBLE
                // 进页面只提示一次「距离下次响铃还有 X」，之后每条都显示在自己卡片上
                if (!tipShown) {
                    nextTriggerOf(list)?.let {
                        tipShown = true
                        toastNextRing(it)
                    }
                }
            }
        }
    }

    /** 底部 tab：闹钟页就是本页，其余四个入口打开对应功能页。 */
    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tabEggTimer -> startActivity(Intent(this, EggTimerActivity::class.java))
                R.id.tabPomodoro -> startActivity(Intent(this, PomodoroActivity::class.java))
                R.id.tabTimer -> startActivity(Intent(this, TimerActivity::class.java))
                R.id.tabStopwatch -> startActivity(Intent(this, StopwatchActivity::class.java))
            }
            true
        }
    }

    /**
     * 标题行下的「提醒」：全部闹钟里最近的一次触发时刻（含未到点的「稍后提醒」）。
     * 只在这个时刻过去、或闹钟列表变化时才重新遍历计算，平时只按秒刷新文案。
     */
    private fun refreshReminder(now: Long) {
        val cached = reminderAt
        val next = if (cached != null && cached > now) cached else nextTriggerOf(alarms)
        reminderAt = next
        if (next == null) {
            binding.tvReminder.visibility = View.GONE
            return
        }
        val text = getString(R.string.home_reminder_fmt, describeDayTime(this, next, now))
        binding.tvReminder.visibility = View.VISIBLE
        if (binding.tvReminder.text?.toString() != text) binding.tvReminder.text = text
    }

    private fun nextTriggerOf(list: List<Alarm>): Long? = list
        .filter { it.enabled }
        .flatMap { it.triggerCandidates() }
        .minOrNull()

    /** 一个闹钟的所有待响时刻：正常排程 + 尚未到点的「延后再响」。 */
    private fun Alarm.triggerCandidates(): List<Long> = buildList {
        // 仅一次闹钟响过会被自动停用，但它待响的「稍后提醒」仍要计入顶部提示
        if (enabled) {
            AlarmScheduler.computeNextTrigger(this@triggerCandidates).takeIf { t -> t > 0 }?.let { add(it) }
        }
        if (snoozeUntil > 0) add(snoozeUntil)
    }

    /** 启动 tip：告诉用户下一次什么时候响（参考 ColorOS 的启动提示）。 */
    private fun toastNextRing(next: Long) {
        val at = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(next))
        android.widget.Toast.makeText(
            this,
            getString(
                R.string.next_ring_tip,
                formatDurationUntil(next - System.currentTimeMillis()),
                at
            ),
            android.widget.Toast.LENGTH_LONG
        ).show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.skip_dialog_title)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val updated = if (which == 0) {
                        // 保持开启，仅跳过下一次触发
                        alarm.copy(skipTime = next)
                    } else {
                        // 彻底关闭：连同「稍后提醒」一起取消（A07）
                        alarm.copy(enabled = false, skipTime = 0L, snoozeUntil = 0L)
                    }
                    repo.update(updated)
                    AlarmScheduler.schedule(applicationContext, updated)
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                // 用户取消：重绑该项让开关回到开启状态
                val pos = adapter.currentList.indexOfFirst { it.id == alarm.id }
                if (pos >= 0) adapter.notifyItemChanged(pos)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从其它功能页返回：tab 选中态回到「闹钟」（点其它 tab 是去开新页面，不是停留）
        binding.bottomNav.selectedItemId = R.id.tabAlarm
        // 从系统授权页返回：若已授予精确闹钟权限，把所有闹钟重排为精确触发
        if (AlarmScheduler.canScheduleExactAlarms(applicationContext)) {
            lifecycleScope.launch {
                AlarmScheduler.rescheduleAll(applicationContext, repo.getAll())
            }
        }
        binding.tvPermWarn.visibility =
            if (PermUtils.allKeyPermsGranted(applicationContext)) View.GONE else View.VISIBLE
        refreshReminder(System.currentTimeMillis())
        syncWorkdayCalendar()
    }

    /**
     * 节假日数据每月检查一次是否过期；拿到新数据后重排工作日闹钟，
     * 否则「工作日」闹钟会按旧的放假安排响。
     */
    private fun syncWorkdayCalendar() {
        if (!WorkdayCalendar.needsRefresh(applicationContext)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = try {
                WorkdayCalendar.refresh(applicationContext)
            } catch (_: Exception) {
                emptyList()
            }
            if (updated.isEmpty()) return@launch
            AlarmScheduler.rescheduleAll(applicationContext, repo.getAll())
            withContext(Dispatchers.Main) { adapter.invalidateNextCache() }
        }
    }

    private fun openEdit(alarm: Alarm?) {
        val intent = Intent(this, AlarmEditActivity::class.java)
        alarm?.let { intent.putExtra("alarmId", it.id) }
        startActivity(intent)
    }
}
