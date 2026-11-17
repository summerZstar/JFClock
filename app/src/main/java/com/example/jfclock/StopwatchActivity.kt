package com.example.jfclock

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.jfclock.databinding.ActivityStopwatchBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 秒表：大字号读数 + 计次（单圈/累计）+ 清零。
 *
 * 时长以 SystemClock.elapsedRealtime() 为基准并持久化（[StopwatchStore]），
 * 因此熄屏、切到别的 App、页面被回收后再回来，读数依然连续。
 */
class StopwatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStopwatchBinding
    private var ticker: Job? = null
    private var lapRowCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStopwatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyStatusBarAppearance(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSwStart.setOnClickListener {
            if (StopwatchStore.isRunning(this)) StopwatchStore.pause(this) else StopwatchStore.start(this)
            render()
        }
        binding.btnSwLap.setOnClickListener {
            if (StopwatchStore.isRunning(this)) {
                StopwatchStore.lap(this)
                renderLaps()
            }
        }
        binding.btnSwReset.setOnClickListener {
            StopwatchStore.reset(this)
            lapRowCount = -1
            render()
        }

        startTicker()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** 50ms 一次：百分秒要跳得动，又不必贴着帧率跑。 */
    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (true) {
                renderTime()
                if (StopwatchStore.laps(this@StopwatchActivity).size != lapRowCount) renderLaps()
                delay(50)
            }
        }
    }

    private fun render() {
        renderTime()
        renderLaps()
    }

    private fun renderTime() {
        val running = StopwatchStore.isRunning(this)
        val elapsed = StopwatchStore.elapsed(this)
        binding.tvSwTime.text = formatSw(elapsed)
        binding.tvSwStatus.text = getString(
            when {
                running -> R.string.sw_state_running
                elapsed > 0 -> R.string.sw_state_paused
                else -> R.string.sw_state_idle
            }
        )
        binding.btnSwStart.setText(
            when {
                running -> R.string.sw_pause
                elapsed > 0 -> R.string.sw_resume
                else -> R.string.sw_start
            }
        )
        binding.btnSwLap.isEnabled = running
        binding.btnSwReset.isEnabled = running || elapsed > 0
        binding.btnSwLap.alpha = if (running) 1f else 0.4f
        binding.btnSwReset.alpha =
            if (running || elapsed > 0) 1f else 0.4f
    }

    /** 计次列表：最新一次在最上面，同时给出单圈与累计。 */
    private fun renderLaps() {
        val container = binding.lapContainer
        val laps = StopwatchStore.laps(this)
        lapRowCount = laps.size
        container.removeAllViews()
        if (laps.isEmpty()) return

        // 累计值 -> 单圈值
        val splits = laps.mapIndexed { i, total -> total - if (i == 0) 0L else laps[i - 1] }
        val best = splits.withIndex().minByOrNull { it.value }?.index ?: -1
        val worst = splits.withIndex().maxByOrNull { it.value }?.index ?: -1
        val markBest = laps.size >= 3 && best != worst

        for (i in laps.indices.reversed()) {
            container.addView(
                lapRow(
                    getString(R.string.sw_lap_fmt, i + 1) +
                            if (markBest && i == best) " · ${getString(R.string.sw_lap_best)}"
                            else if (markBest && i == worst) " · ${getString(R.string.sw_lap_worst)}"
                            else "",
                    formatSw(splits[i]),
                    formatSw(laps[i]),
                    highlight = i == laps.lastIndex && StopwatchStore.isRunning(this)
                )
            )
        }
    }

    private fun lapRow(name: String, split: String, total: String, highlight: Boolean): LinearLayout {
        val color = ContextCompat.getColor(
            this,
            if (highlight) R.color.light_accent else R.color.light_text_primary
        )
        val secondary = ContextCompat.getColor(this, R.color.light_text_secondary)
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
            addView(label(name, 1.1f, secondary, Gravity.START))
            addView(label(split, 1f, color, Gravity.END))
            addView(label(total, 1f, color, Gravity.END))
        }
    }

    private fun label(text: String, weight: Float, color: Int, gravity: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    /** MM:SS.CC，超过一小时补上小时位。 */
    private fun formatSw(ms: Long): String {
        val cs = ms / 10
        val s = cs / 100
        val m = s / 60
        val h = m / 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%02d", h, m % 60, s % 60, cs % 100)
        } else {
            String.format(Locale.US, "%02d:%02d.%02d", m, s % 60, cs % 100)
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        ticker = null
        super.onDestroy()
    }
}
