package com.example.jfclock

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 自绘滚轮选择器，用来替掉系统 [android.widget.NumberPicker]。
 *
 * 系统 NumberPicker 手感差的地方：拖动几乎不带惯性、松手吸附很硬、
 * 上下项与中间项明暗差别固定，看起来像表格而不是 ColorOS 那种顺滑轮盘。
 * 这里自己实现：
 *  - 手指位移直接映射为浮点位置，松手用 VelocityTracker 估速做惯性滚动；
 *  - 惯性结束后 ease-out 吸附到整格；
 *  - 每跨过一格给一次轻触觉（CLOCK_TICK），中间画两条分隔线；
 *  - 离中心越远的字越淡越小，做出景深；
 *  - 轻点上下某格可以直接滚过去（系统控件只能靠拖）。
 *
 * 对外 API 刻意与 NumberPicker 对齐（minValue / maxValue / value /
 * wrapSelectorWheel / setFormatter / setOnValueChangedListener），替换时改动最小。
 */
class WheelPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnValueChangeListener {
        fun onValueChange(picker: WheelPicker, oldValue: Int, newValue: Int)
    }

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var minVal = 0
    private var maxVal = 59
    private var wrapWheel = true
    private var rows = 3
    private var itemHeight = 22f * density * 1.7f
    private var itemTextSize = 22f * density
    private var normalColor = ContextCompat.getColor(context, R.color.light_text_secondary)
    private var selectedColor = ContextCompat.getColor(context, R.color.light_text_primary)
    private var lineColor = ContextCompat.getColor(context, R.color.light_divider)
    private var formatter: (Int) -> String = { it.toString() }
    private var listener: OnValueChangeListener? = null

    /** 浮点位置，单位「格」；中心那格即当前值，循环模式下可以一直增大/减小。 */
    private var position = 0f

    private var animator: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var downPosition = 0f
    private var moved = false
    private var lastValue = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var widest = 0f

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.WheelPicker)
            minVal = a.getInt(R.styleable.WheelPicker_wheelMinValue, minVal)
            maxVal = a.getInt(R.styleable.WheelPicker_wheelMaxValue, maxVal)
            wrapWheel = a.getBoolean(R.styleable.WheelPicker_wheelWrapSelectorWheel, wrapWheel)
            rows = a.getInt(R.styleable.WheelPicker_wheelVisibleCount, rows)
            itemTextSize = a.getDimension(R.styleable.WheelPicker_wheelItemTextSize, itemTextSize)
            itemHeight = a.getDimension(R.styleable.WheelPicker_wheelItemHeight, itemTextSize * 1.7f)
            normalColor = a.getColor(R.styleable.WheelPicker_wheelTextColor, normalColor)
            selectedColor = a.getColor(R.styleable.WheelPicker_wheelSelectedTextColor, selectedColor)
            lineColor = a.getColor(R.styleable.WheelPicker_wheelSelectorColor, lineColor)
            position = (a.getInt(R.styleable.WheelPicker_wheelValue, minVal) - minVal).toFloat()
            a.recycle()
        }
        if (rows < 3) rows = 3
        if (rows % 2 == 0) rows += 1
        lastValue = valueAt(position)
        clampPosition()
        measureWidest()
    }

    var minValue: Int
        get() = minVal
        set(v) {
            minVal = v
            if (maxVal < minVal) maxVal = minVal
            onRangeChanged()
        }

    var maxValue: Int
        get() = maxVal
        set(v) {
            maxVal = v
            if (minVal > maxVal) minVal = maxVal
            onRangeChanged()
        }

    var wrapSelectorWheel: Boolean
        get() = wrapWheel
        set(v) {
            wrapWheel = v
            onRangeChanged()
        }

    /** 中间行之外上下各看到的行数由高度决定，这里给出期望高度（dp 由调用方控制）。 */
    var visibleCount: Int
        get() = rows
        set(v) {
            rows = if (v < 3) 3 else if (v % 2 == 0) v + 1 else v
            requestLayout()
            invalidate()
        }

    var value: Int
        get() = valueAt(position)
        set(v) = jumpTo(v)

    fun setFormatter(f: (Int) -> String) {
        formatter = f
        measureWidest()
        requestLayout()
    }

    /** 代码里创建（对话框那些）时统一字号，行高跟着走。 */
    fun setTextSizeSp(sp: Float) {
        itemTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
        )
        itemHeight = itemTextSize * 1.7f
        onRangeChanged()
    }

    fun setOnValueChangedListener(l: OnValueChangeListener?) {
        listener = l
    }

    /** 带动画滚到某个值（快捷加减按钮用）。 */
    fun animateTo(v: Int) {
        val target = nearestIndexFor(v)
        if (abs(target - position) < 0.001f) return
        runAnimation(target)
    }

    private fun count(): Int = (maxVal - minVal + 1).coerceAtLeast(1)

    private fun valueAt(pos: Float): Int {
        val index = pos.roundToInt()
        return if (wrapWheel) floorMod(index, count()) + minVal
        else index.coerceIn(0, count() - 1) + minVal
    }

    private fun floorMod(a: Int, b: Int): Int {
        val r = a % b
        return if (r < 0) r + b else r
    }

    /** 目标值对应的浮点位置；循环模式下选离当前位置最近的那一圈，避免转大半圈。 */
    private fun nearestIndexFor(v: Int): Float {
        val slot = (v - minVal).coerceIn(0, count() - 1)
        if (!wrapWheel) return slot.toFloat()
        val n = count()
        val base = floor(position)
        var delta = slot - base.toInt()
        delta %= n
        if (delta > n / 2) delta -= n
        if (delta < -(n / 2)) delta += n
        return base + delta
    }

    private fun jumpTo(v: Int) {
        animator?.cancel()
        position = nearestIndexFor(v)
        clampPosition()
        notifyIfChanged()
        invalidate()
    }

    private fun onRangeChanged() {
        clampPosition()
        measureWidest()
        requestLayout()
        invalidate()
    }

    private fun clampPosition() {
        if (!wrapWheel) position = position.coerceIn(0f, (count() - 1).toFloat())
    }

    private fun notifyIfChanged() {
        val v = valueAt(position)
        if (v == lastValue) return
        val old = lastValue
        lastValue = v
        listener?.onValueChange(this, old, v)
    }

    private fun tickIfChanged() {
        val v = valueAt(position)
        if (v == lastValue) return
        notifyIfChanged()
        if (isHapticFeedbackEnabled) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    // ---- 尺寸与绘制 ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (widest * 1.5f + 12f * density)
            .coerceAtLeast(56f * density).toInt()
        val desiredHeight = (itemHeight * rows).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun measureWidest() {
        widest = 0f
        textPaint.textSize = itemTextSize
        for (i in 0 until count()) {
            val w = textPaint.measureText(formatter(i + minVal))
            if (w > widest) widest = w
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (widest <= 0f) measureWidest()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = itemHeight / 2f
        val stroke = 1.1f * density

        linePaint.color = lineColor
        canvas.drawRect(0f, cy - half - stroke, width.toFloat(), cy - half, linePaint)
        canvas.drawRect(0f, cy + half, width.toFloat(), cy + half + stroke, linePaint)

        val reach = rows / 2f + 0.5f
        val center = floor(position)
        for (step in -(rows).toInt()..rows) {
            val index = (center + step).toInt()
            val offset = index - position
            if (abs(offset) > reach) continue
            val shown = if (wrapWheel) {
                floorMod(index, count()) + minVal
            } else {
                if (index < 0 || index > count() - 1) continue
                index + minVal
            }
            val ratio = (abs(offset) / reach).coerceIn(0f, 1f)
            textPaint.textSize = itemTextSize * (1f - 0.2f * ratio)
            textPaint.color = if (abs(offset) < 0.5f) selectedColor else normalColor
            textPaint.alpha = (255 * (1f - 0.78f * ratio)).toInt().coerceIn(30, 255)
            canvas.drawText(
                formatter(shown),
                cx,
                cy + offset * itemHeight - (textPaint.ascent() + textPaint.descent()) / 2f,
                textPaint
            )
        }
    }

    // ---- 手势 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(event)
                downY = event.y
                downPosition = position
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (!moved && abs(event.y - downY) < touchSlop) return true
                moved = true
                position = if (wrapWheel) {
                    downPosition + (downY - event.y) / itemHeight
                } else {
                    (downPosition + (downY - event.y) / itemHeight)
                        .coerceIn(0f, (count() - 1).toFloat())
                }
                tickIfChanged()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000, maxFlingVelocity)
                val velocityY = tracker?.getYVelocity() ?: 0f
                tracker?.recycle()
                tracker = null
                parent?.requestDisallowInterceptTouchEvent(false)

                if (!moved) {
                    // 轻点：把指尖那一格滚到中间
                    val steps = ((event.y - height / 2f) / itemHeight).roundToInt()
                    if (abs(steps) >= 1) runAnimation(position + steps) else runAnimation(position.roundToInt().toFloat())
                    performClick()
                    return true
                }
                glide(-(velocityY / itemHeight))
            }
        }
        return true
    }

    /** 松手后按速度滚一段，再吸附到整格。 */
    private fun glide(itemsPerSecond: Float) {
        val speed = itemsPerSecond.coerceIn(-MAX_FLING_ITEMS, MAX_FLING_ITEMS)
        val target = position + speed * MOMENTUM_SECONDS
        val snapped = if (wrapWheel) {
            target.roundToInt().toFloat()
        } else {
            target.roundToInt().coerceIn(0, count() - 1).toFloat()
        }
        runAnimation(snapped)
    }

    private fun runAnimation(target: Float) {
        val from = position
        val to = if (wrapWheel) target else target.coerceIn(0f, (count() - 1).toFloat())
        val distance = abs(to - from)
        if (distance < 0.001f) {
            notifyIfChanged()
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (MIN_ANIM_MS + distance * PER_ITEM_MS)
                .coerceIn(MIN_ANIM_MS, MAX_ANIM_MS).toLong()
            addUpdateListener {
                position = from + (to - from) * easeOutCubic(it.animatedFraction)
                tickIfChanged()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    position = to
                    clampPosition()
                    tickIfChanged()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** 惯性持续时间（秒），决定同样的速度能滚多远 */
        private const val MOMENTUM_SECONDS = 0.6f
        private const val MAX_FLING_ITEMS = 26f
        private const val MIN_ANIM_MS = 130f
        private const val PER_ITEM_MS = 30f
        private const val MAX_ANIM_MS = 640f
    }
}
