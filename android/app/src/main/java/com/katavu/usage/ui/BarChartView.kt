package com.katavu.usage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.katavu.usage.R

/** A thin bar per day; height scaled to the busiest day. No labels, just the shape of activity. */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values: List<Int> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.green_leaf)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.green_leaf_light)
    }
    private val rect = RectF()

    fun setValues(values: List<Int>) {
        this.values = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val n = values.size
        val gap = height * 0.06f
        val slot = width.toFloat() / n
        val barW = (slot - gap).coerceAtLeast(2f)
        val radius = barW / 2f
        val baseline = height.toFloat()

        values.forEachIndexed { i, v ->
            val left = i * slot + (slot - barW) / 2f
            // Faint track so empty days still read as "a day".
            rect.set(left, height * 0.85f, left + barW, baseline)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            if (v > 0) {
                val h = (height * 0.9f) * (v.toFloat() / max)
                rect.set(left, baseline - h, left + barW, baseline)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
        }
    }
}
