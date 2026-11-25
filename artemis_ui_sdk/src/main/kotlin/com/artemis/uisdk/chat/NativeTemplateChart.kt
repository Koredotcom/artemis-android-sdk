package com.artemis.uisdk.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.artemis.uisdk.ArtemisThemeConfig
import com.artemis.uisdk.R
import com.artemis.uisdk.artemisColor
import kotlin.math.abs

internal data class TemplateChartPoint(val label: String, val value: Double, val color: String?)

/** Signed domains and scaling prevent negative-only/large-number charts from drawing outside bounds. */
@SuppressLint("ViewConstructor")
internal class NativeTemplateChart(context: Context, private val type: String,
    private val points: List<TemplateChartPoint>, private val theme: ArtemisThemeConfig) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Same series order as the web SDK chart: indigo, green, amber, red, then extra hues for longer series.
    private val palette = listOf(
        0xFF4F46E5.toInt(), 0xFF45956D.toInt(), 0xFFCA7C2F.toInt(), 0xFFCA3A31.toInt(),
        0xFF8B5CF6.toInt(), 0xFF0EA5E9.toInt(), 0xFFEC4899.toInt(), 0xFF64748B.toInt(),
    )
    private val pieBounds = RectF()
    private val barBounds = RectF()
    private val linePath = Path()
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10); textAlign = Paint.Align.CENTER }
    private var axisLabels = emptyList<String>()
    private val pointColors = points.indices.map { index -> runCatching { Color.parseColor(points[index].color) }.getOrDefault(palette[index % palette.size]) }
    private val sweeps = TemplateContracts.pieSweeps(points.map { it.value })
    private val magnitude = points.maxOf { abs(it.value) }.takeIf { it > 0 } ?: 1.0
    private val normalized = points.map { it.value / magnitude }
    private val low = minOf(0.0, normalized.minOrNull() ?: 0.0)
    private val high = maxOf(0.0, normalized.maxOrNull() ?: 1.0)
    private val range = (high - low).takeIf { it > 0 } ?: 1.0
    init { contentDescription = points.joinToString("; ") { "${it.label}: ${it.value}" }; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    private fun dp(n: Int) = n * resources.displayMetrics.density
    private fun color(index: Int) = pointColors[index]
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val slot = ((w - dp(16)) / points.size.coerceAtLeast(1)).coerceAtLeast(1f)
        axisLabels = points.map { android.text.TextUtils.ellipsize(it.label, labelPaint, slot, android.text.TextUtils.TruncateAt.END).toString() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        paint.style = Paint.Style.FILL
        if (type == "pie") {
            val diameter = minOf(width, height).toFloat() - dp(8)
            pieBounds.set((width - diameter) / 2, (height - diameter) / 2, (width + diameter) / 2, (height + diameter) / 2)
            var start = -90f
            sweeps.forEachIndexed { index, sweep -> paint.color = color(index); canvas.drawArc(pieBounds, start, sweep, true, paint); start += sweep }
            if (sweeps.all { it == 0f }) { paint.color = context.artemisColor(theme, R.color.artemis_border); canvas.drawOval(pieBounds, paint) }
            return
        }
        val left = dp(8); val right = width - dp(8); val top = dp(10); val bottom = height - dp(24)
        fun y(value: Double) = bottom - ((value - low) / range * (bottom - top)).toFloat()
        val zero = y(0.0)
        // Web draws no axis line; keep the zero line only when bars or points go below zero.
        if (low < 0) {
            paint.color = context.artemisColor(theme, R.color.artemis_border); paint.strokeWidth = dp(1)
            canvas.drawLine(left, zero, right, zero, paint)
        }
        val slot = (right - left) / points.size
        if (type == "line") {
            linePath.reset()
            normalized.forEachIndexed { index, value ->
                val x = left + slot * (index + .5f)
                if (index == 0) linePath.moveTo(x, y(value)) else linePath.lineTo(x, y(value))
            }
            paint.color = context.artemisColor(theme, R.color.artemis_primary); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2)
            canvas.drawPath(linePath, paint); paint.style = Paint.Style.FILL
            normalized.forEachIndexed { index, value -> paint.color = color(index); canvas.drawCircle(left + slot * (index + .5f), y(value), dp(3), paint) }
        } else normalized.forEachIndexed { index, value ->
            paint.color = color(index)
            // Web bars: ~10% gap on each side of the slot and softly rounded corners.
            val inset = slot * .1f
            barBounds.set(left + slot * index + inset, minOf(zero, y(value)), left + slot * (index + 1) - inset, maxOf(zero, y(value)))
            canvas.drawRoundRect(barBounds, dp(3), dp(3), paint)
        }
        // Full labels and values are in contentDescription; draw a compact axis for small series.
        if (points.size <= 8) {
            labelPaint.color = context.artemisColor(theme, R.color.artemis_muted)
            axisLabels.forEachIndexed { index, caption -> canvas.drawText(caption, left + slot * (index + .5f), height - dp(5), labelPaint) }
        }
    }
}
