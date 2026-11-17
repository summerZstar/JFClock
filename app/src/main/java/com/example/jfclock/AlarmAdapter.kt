package com.example.jfclock

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.jfclock.databinding.ItemAlarmBinding
import android.widget.TextView

class AlarmAdapter(
    private val onToggle: (Alarm, Boolean) -> Unit,
    private val onItemClick: (Alarm) -> Unit
) : ListAdapter<Alarm, AlarmAdapter.VH>(DIFF) {

    companion object {
        /** 不会再响的闹钟的下次触发时刻标记 */
        private const val NO_MORE_TRIGGER = -1L

        val DIFF = object : DiffUtil.ItemCallback<Alarm>() {
            override fun areItemsTheSame(a: Alarm, b: Alarm) = a.id == b.id
            override fun areContentsTheSame(a: Alarm, b: Alarm) = a == b
        }
    }

    inner class VH(val binding: ItemAlarmBinding) : RecyclerView.ViewHolder(binding.root)

    private var list: RecyclerView? = null

    /**
     * 下一次触发时刻的缓存 alarmId -> (当时的 Alarm 快照, 触发时刻)。
     * 工作日闹钟要遍历节假日日历，逐秒重算代价明显；而触发时刻在响之前根本不会变，
     * 所以只在「闹钟配置变了」或「已到点过期」时才真的重算一次。
     */
    private val nextCache = HashMap<Long, Pair<Alarm, Long>>()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        list = null
    }

    override fun onCurrentListChanged(
        previousList: MutableList<Alarm>,
        currentList: MutableList<Alarm>
    ) {
        // 清掉已经被删除的闹钟，避免缓存里留下垃圾
        if (nextCache.isEmpty()) return
        val alive = HashSet<Long>(currentList.size * 2)
        currentList.forEach { alive.add(it.id) }
        nextCache.keys.retainAll(alive)
    }

    /** 节假日数据更新后下次触发可能整体改变，调用方用它强制失效。 */
    fun invalidateNextCache() = nextCache.clear()

    private fun nextTriggerCached(alarm: Alarm, now: Long): Long {
        val cached = nextCache[alarm.id]
        if (cached != null && cached.first == alarm) {
            // 没过期就直接用；-1 表示不会再响，也不用反复重算
            if (cached.second > now || cached.second == NO_MORE_TRIGGER) return cached.second
        }
        val next = AlarmScheduler.computeNextTrigger(alarm, now)
        nextCache[alarm.id] = alarm to next
        return next
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val alarm = getItem(position)
        val b = holder.binding
        b.timeText.text = String.format("%02d:%02d", alarm.hour, alarm.minute)
        // 先解绑 listener 再拨开关：否则重绑时旧 listener 会合成一次回调，
        // 把「仅跳过下一次」等刚写库的状态又改回去
        b.switchCompat.setOnCheckedChangeListener(null)
        b.switchCompat.isChecked = alarm.enabled
        b.switchCompat.tag = alarm.id
        b.switchCompat.setOnCheckedChangeListener { _, isChecked ->
            val tag = b.switchCompat.tag
            if (tag == alarm.id) onToggle(alarm, isChecked)
        }
        b.root.setOnClickListener { onItemClick(alarm) }
        // 复用行要先清掉上一项的渲染标记，否则 UI 会沿用旧闹钟的文案
        b.descText.tag = null
        b.snoozeText.tag = null
        bindStatusLines(holder, alarm, System.currentTimeMillis())
    }

    /** 主界面每秒调用：刷新列表项的状态行（下次响铃 / 稍后提醒 / 跳过说明）。 */
    fun refreshStatusLines(now: Long) {
        val rv = list ?: return
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? VH ?: continue
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                bindStatusLines(holder, getItem(position), now)
            }
        }
    }

    /**
     * 列表项的状态显示：
     * 1. 第二行 = 「重复方式」（次要色）+「下次响铃」（主题蓝）同排双色，
     *    省掉独立一行；「下次」文案只给天级信息，稳定单行。
     * 2. 「稍后提醒」倒计时、 「仅跳过一次」说明文案长且每秒刷新，
     *    单独放第三行；它们出现时第二行只留重复方式。
     * 3. 关闭的闹钟不显示下次响铃，列表才不至于被一堆「下次」占满。
     */
    private fun bindStatusLines(holder: VH, alarm: Alarm, now: Long) {
        val snoozeView = holder.binding.snoozeText
        val until = alarm.snoozeUntil

        if (until > now) {
            setDescLine(holder, alarm, next = null, now = now)
            snoozeView.visibility = View.VISIBLE
            snoozeView.text = snoozeView.context.getString(
                R.string.alarm_snooze_line_fmt,
                formatSnoozeCountdown(until - now),
                formatClockShort(until)
            )
            return
        }

        val skipped = alarm.enabled && alarm.skipTime > now
        if (skipped) {
            val next = nextTriggerCached(alarm, now)
            if (next > 0) {
                setDescLine(holder, alarm, next = null, now = now)
                snoozeView.visibility = View.VISIBLE
                snoozeView.text = snoozeView.context.getString(
                    R.string.alarm_skip_line_fmt,
                    formatMonthDay(alarm.skipTime),
                    formatMonthDayTime(next)
                )
                return
            }
        }
        hideLine(snoozeView)

        val next = if (alarm.enabled) nextTriggerCached(alarm, now) else NO_MORE_TRIGGER
        setDescLine(holder, alarm, next.takeIf { it > 0 }, now)
    }

    /**
     * 第二行：重复方式 + 可选的下次响铃。
     * 只有渲染粒度变了才真正写 View（tag 是「重复方式哈希 + 下次响铃标记」）；
     * 逐秒刷新时绝大多数行是零写入，连 SimpleDateFormat 都不会调用到。
     */
    private fun setDescLine(holder: VH, alarm: Alarm, next: Long?, now: Long) {
        val tv = holder.binding.descText
        val desc = describeAlarm(alarm)
        val stamp = desc.hashCode().toLong() * 31 + (next?.let { nextRingStamp(it, now) } ?: 0L)
        if (tv.tag == stamp) return
        tv.tag = stamp
        if (next == null) {
            tv.text = desc
            return
        }
        val ctx = tv.context
        val full = SpannableStringBuilder(desc)
            .append(" · ")
            .append(formatNextRingLine(ctx, next, now))
        full.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.light_accent)),
            desc.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = full
    }

    private fun hideLine(view: TextView) {
        if (view.visibility == View.GONE) return
        view.text = ""
        view.visibility = View.GONE
        view.tag = null
    }
}
