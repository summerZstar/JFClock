package com.example.jfclock

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.jfclock.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AlarmAdapter
    private val repo by lazy { (application as JFClockApp).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AlarmAdapter(
            onToggle = { alarm, enabled ->
                lifecycleScope.launch {
                    val updated = alarm.copy(enabled = enabled)
                    repo.update(updated)
                    AlarmScheduler.schedule(applicationContext, updated)
                }
            },
            onItemClick = { openEdit(it) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { openEdit(null) }

        lifecycleScope.launch {
            repo.allAlarms.collectLatest { list ->
                adapter.submitList(list)
                val hasItems = list.isNotEmpty()
                binding.recycler.visibility = if (hasItems) android.view.View.VISIBLE else android.view.View.GONE
                binding.emptyView.visibility = if (hasItems) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun openEdit(alarm: Alarm?) {
        val intent = Intent(this, AlarmEditActivity::class.java)
        alarm?.let { intent.putExtra("alarmId", it.id) }
        startActivity(intent)
    }
}
