package com.example.jfclock

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.jfclock.databinding.ItemAlarmBinding

class AlarmAdapter(
    private val onToggle: (Alarm, Boolean) -> Unit,
    private val onItemClick: (Alarm) -> Unit
) : ListAdapter<Alarm, AlarmAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Alarm>() {
            override fun areItemsTheSame(a: Alarm, b: Alarm) = a.id == b.id
            override fun areContentsTheSame(a: Alarm, b: Alarm) = a == b
        }
    }

    inner class VH(val binding: ItemAlarmBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val alarm = getItem(position)
        val b = holder.binding
        b.timeText.text = String.format("%02d:%02d", alarm.hour, alarm.minute)
        b.descText.text = describeAlarm(alarm)
        b.switchCompat.isChecked = alarm.enabled
        // 用 tag 避免 setOnCheckedChangeListener 在复用时的误触发
        b.switchCompat.tag = alarm.id
        b.switchCompat.setOnCheckedChangeListener { _, isChecked ->
            val tag = b.switchCompat.tag
            if (tag == alarm.id) onToggle(alarm, isChecked)
        }
        b.root.setOnClickListener { onItemClick(alarm) }
    }
}
