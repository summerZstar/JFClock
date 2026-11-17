package com.example.jfclock

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.jfclock.databinding.ViewTimerPresetItemBinding

/**
 * 常用计时器列表：点击套用预设、长按编辑、按住右侧手柄上下拖动排序。
 *
 * 拖动只在手柄上开始（长按整行是编辑），拖完把新顺序的 id 列表交给 [onOrderChanged] 落库。
 */
class TimerPresetAdapter(
    private val onApply: (TimerPreset) -> Unit,
    private val onEdit: (TimerPreset) -> Unit
) : RecyclerView.Adapter<TimerPresetAdapter.Holder>() {

    /** 由外部创建并 attach 到 RecyclerView 的 ItemTouchHelper；手柄按下时用它起拖。 */
    var itemTouchHelper: ItemTouchHelper? = null

    /** 拖动结束后的新顺序（按 id 从前往后）。 */
    var onOrderChanged: ((List<Long>) -> Unit)? = null

    private val items = mutableListOf<TimerPreset>()

    class Holder(val binding: ViewTimerPresetItemBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<TimerPreset>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ViewTimerPresetItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val preset = items[position]
        val context = holder.itemView.context
        holder.binding.ivPresetType.setImageResource(
            if (preset.scheduled) R.drawable.ic_timer_scheduled else R.drawable.ic_timer_countdown
        )
        holder.binding.ivPresetType.contentDescription = context.getString(
            if (preset.scheduled) R.string.timer_preset_type_scheduled
            else R.string.timer_preset_type_countdown
        )
        holder.binding.tvPresetName.text = preset.name
        holder.binding.tvPresetDuration.text = formatTimerClock(preset.seconds * 1000L)
        // 定时类型多一行副标题，直接看出是「到点自己开始」的计时器
        if (preset.scheduled) {
            holder.binding.tvPresetSubtitle.text = context.getString(
                R.string.timer_preset_scheduled_fmt,
                String.format(java.util.Locale.US, "%02d:%02d", preset.startHour, preset.startMinute)
            )
            holder.binding.tvPresetSubtitle.visibility = View.VISIBLE
        } else {
            holder.binding.tvPresetSubtitle.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onApply(preset) }
        holder.itemView.setOnLongClickListener { onEdit(preset); true }
        holder.binding.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }
    }

    /** 当前顺序的 id（拖动结束后回写用）。 */
    fun currentIds(): List<Long> = items.map { it.id }

    /** 挂到列表上：只允许上下拖，只从手柄起拖，落点后回调新顺序。 */
    fun attachTouchHelper(recyclerView: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = from.bindingAdapterPosition
                val toPos = to.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                val moved = items.removeAt(fromPos)
                items.add(toPos, moved)
                notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun isLongPressDragEnabled() = false

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                onOrderChanged?.invoke(currentIds())
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        itemTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
    }
}