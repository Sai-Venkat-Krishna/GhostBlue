package com.saivenkatkrishna.ghostblue.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** One bar, or one stack of segments, with an optional axis label under it. */
data class ChartBar(val segments: List<Pair<Color, Double>>, val label: String? = null) {
    val total: Double get() = segments.sumOf { max(0.0, it.second) }
}

// Room for the value labels on the left, and for the axis labels (and unit) underneath.
private val AxisWidth = 44.dp
private val LabelHeight = 22.dp

/** Rounds [value] up to 1, 2, 2.5 or 5 times a power of ten, for axis ticks. */
fun niceAxisMaximum(value: Double): Double {
    if (value <= 0 || !value.isFinite()) return 1.0
    val exponent = floor(log10(value))
    val base = 10.0.pow(exponent)
    val fraction = value / base
    val nice = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { fraction <= it + 1e-9 }
    return nice * base
}

/** Four or five steps, whichever lands on round values (5 → 0, 1, 2…; 20 → 0, 5, 10…). */
private fun tickCount(maximum: Double): Int =
    listOf(4, 5, 2).firstOrNull { val step = maximum / it * 10; kotlin.math.abs(step - kotlin.math.round(step)) < 1e-6 } ?: 4

private fun DrawScope.drawYAxis(measurer: TextMeasurer, style: TextStyle, maximum: Double, plotHeight: Float, gridColor: Color, unit: String?) {
    val axisWidth = AxisWidth.toPx()
    val ticks = tickCount(maximum)
    for (tick in 0..ticks) {
        val value = maximum * tick / ticks
        val y = plotHeight - plotHeight * tick / ticks
        drawLine(gridColor, Offset(axisWidth, y), Offset(size.width, y), strokeWidth = 1f,
            pathEffect = if (tick == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val text = if (maximum < 10) Format.oneDecimal(value) else Format.fraction(value, 0)
        val layout = measurer.measure(text, style)
        drawText(layout, topLeft = Offset(axisWidth - layout.size.width - 6.dp.toPx(), (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
    }
}

/**
 * Bars (stacked when a bar has several segments) on a zero-based axis.
 * Tapping a bar selects it and dims the others, as the iOS charts do.
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: (Int?) -> Unit = {},
    yMaximum: Double? = null,
    unit: String? = null,
    height: Dp = 180.dp,
    description: String = "Bar chart",
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maximum = niceAxisMaximum(yMaximum ?: (bars.maxOfOrNull { it.total } ?: 0.0))
    unit?.let { Text(it, style = labelStyle) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(bars) {
                detectTapGestures { offset ->
                    if (bars.isEmpty()) return@detectTapGestures
                    val slot = (size.width - AxisWidth.toPx()) / bars.size
                    val index = ((offset.x - AxisWidth.toPx()) / slot).toInt()
                    onSelect(if (index in bars.indices && index != selectedIndex) index else null)
                }
            },
    ) {
        val plotHeight = size.height - LabelHeight.toPx()
        drawYAxis(measurer, labelStyle, maximum, plotHeight, gridColor, unit)
        if (bars.isEmpty()) return@Canvas
        val slot = (size.width - AxisWidth.toPx()) / bars.size
        val barWidth = (slot * 0.62f).coerceAtMost(36.dp.toPx())
        bars.forEachIndexed { index, bar ->
            val alpha = if (selectedIndex == null || selectedIndex == index) 1f else 0.38f
            val left = AxisWidth.toPx() + slot * index + (slot - barWidth) / 2
            var top = plotHeight
            val visible = bar.segments.filter { it.second > 0 }
            visible.forEachIndexed { segmentIndex, (color, value) ->
                val segmentHeight = (value / maximum * plotHeight).toFloat()
                top -= segmentHeight
                val isTop = segmentIndex == visible.lastIndex
                val radius = if (isTop) CornerRadius(barWidth / 4, barWidth / 4) else CornerRadius.Zero
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.7f)), startY = top, endY = top + segmentHeight),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, segmentHeight + if (isTop) 0f else 0.5f),
                    cornerRadius = radius,
                    alpha = alpha,
                )
            }
            bar.label?.let { label ->
                val layout = measurer.measure(label, labelStyle)
                val x = (AxisWidth.toPx() + slot * index + slot / 2 - layout.size.width / 2f)
                    .coerceIn(AxisWidth.toPx(), size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, plotHeight + 4f))
            }
        }
    }
}

/**
 * A line over an area, with a point per value: the efficiency trend.
 * Tapping selects the nearest point.
 */
@Composable
fun LineChart(
    values: List<Double>,
    labels: List<String?>,
    color: Color,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: (Int?) -> Unit = {},
    unit: String? = null,
    height: Dp = 190.dp,
    description: String = "Line chart",
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val maximum = niceAxisMaximum((values.maxOrNull() ?: 0.0) * 1.1)
    unit?.let { Text(it, style = labelStyle) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values) {
                detectTapGestures { offset ->
                    if (values.isEmpty()) return@detectTapGestures
                    val step = if (values.size > 1) (size.width - AxisWidth.toPx() - 16f) / (values.size - 1) else 0f
                    val index = if (step == 0f) 0 else ((offset.x - AxisWidth.toPx() - 8f) / step).let { kotlin.math.round(it).toInt() }
                    onSelect(index.coerceIn(values.indices).takeIf { it != selectedIndex })
                }
            },
    ) {
        val plotHeight = size.height - LabelHeight.toPx()
        drawYAxis(measurer, labelStyle, maximum, plotHeight, gridColor, unit)
        if (values.isEmpty()) return@Canvas
        val left = AxisWidth.toPx() + 8f
        val width = size.width - left - 8f
        fun point(index: Int): Offset {
            val x = if (values.size == 1) left + width / 2 else left + width * index / (values.size - 1)
            return Offset(x, plotHeight - (values[index] / maximum * plotHeight).toFloat())
        }
        val line = Path().apply {
            values.indices.forEach { if (it == 0) moveTo(point(it).x, point(it).y) else lineTo(point(it).x, point(it).y) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(point(values.lastIndex).x, plotHeight)
            lineTo(point(0).x, plotHeight)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)), endY = plotHeight))
        drawPath(line, color, style = Stroke(width = 3.dp.toPx()))
        values.indices.forEach { index ->
            val selected = index == selectedIndex
            drawCircle(color, radius = if (selected) 6.dp.toPx() else 4.dp.toPx(), center = point(index))
            drawCircle(surface, radius = if (selected) 3.dp.toPx() else 2.dp.toPx(), center = point(index))
            labels.getOrNull(index)?.let { label ->
                val layout = measurer.measure(label, labelStyle)
                val x = (point(index).x - layout.size.width / 2f).coerceIn(AxisWidth.toPx(), size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, plotHeight + 4f))
            }
        }
        selectedIndex?.takeIf { it in values.indices }?.let { index ->
            drawLine(color.copy(alpha = 0.5f), Offset(point(index).x, 0f), Offset(point(index).x, plotHeight), strokeWidth = 1.5f)
        }
    }
}

/** At most [maximum] evenly spaced label positions for [count] items, always including the last. */
fun labelIndices(count: Int, maximum: Int): Set<Int> {
    if (count <= 0) return emptySet()
    if (count <= maximum) return (0 until count).toSet()
    val stride = max(1, ceil((count - 1).toDouble() / (maximum - 1)).toInt())
    return ((0 until count step stride) + (count - 1)).toSet()
}
