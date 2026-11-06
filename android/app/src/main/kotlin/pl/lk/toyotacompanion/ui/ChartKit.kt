package pl.lk.toyotacompanion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Colours that carry meaning on every screen, independent of the wallpaper palette: green = good / battery,
 * amber = middling, red = bad, blue = neutral / distance, teal and violet = extra series.
 */
object Palette {
    val Green = Color(0xFF2E9E5B)
    val Amber = Color(0xFFF2A100)
    val Red = Color(0xFFE5392F)
    val Blue = Color(0xFF2F7DE1)
    val Teal = Color(0xFF14A3A8)
    val Violet = Color(0xFF7B5BE0)
    val Orange = Color(0xFFF26B21)
    val Pink = Color(0xFFD6458B)

    /** Series colours in a fixed order, so the same category keeps its colour. */
    val Series = listOf(Blue, Green, Orange, Violet, Teal, Pink, Amber, Red)

    /** Good → bad for a score 0..1 (1 = best). */
    fun score(value: Float): Color = when {
        value >= 0.66f -> Green
        value >= 0.4f -> Amber
        else -> Red
    }
}

/** One vertical bar of [BarChart]: [value] drives the height, [top] is printed above it, [label] below. */
data class ChartBar(val value: Float, val label: String, val top: String? = null, val color: Color? = null)

/**
 * Vertical bars drawn on a canvas with a faint baseline grid; [max] fixes the axis (default: the tallest bar). A value of
 * zero or less draws no bar, only the baseline. A screen reader reads each column (top text and label) as one item.
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    max: Float? = null,
    height: androidx.compose.ui.unit.Dp = 96.dp,
) {
    if (bars.isEmpty()) return
    val axis = (max ?: bars.maxOf { it.value }).coerceAtLeast(0.0001f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (bar in bars) {
            val barColor = bar.color ?: color
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                if (bar.top != null) Text(bar.top, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
                    if (bar.value <= 0f) return@Canvas
                    // A small positive value keeps a visible stub; zero is no bar at all.
                    val h = size.height * (bar.value / axis).coerceIn(0.02f, 1f)
                    val w = size.width * 0.7f
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.55f)), startY = size.height - h, endY = size.height),
                        topLeft = Offset((size.width - w) / 2f, size.height - h),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(8f, 8f),
                    )
                }
                Text(bar.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** A line over [values] with a soft fill beneath it and a dot on the last point; at least two points to draw. */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: androidx.compose.ui.unit.Dp = 72.dp,
) {
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val pad = 8f
        val stepX = (size.width - 2 * pad) / (values.size - 1)
        fun point(i: Int) = Offset(pad + i * stepX, pad + (size.height - 2 * pad) * (1f - (values[i] - lo) / span))
        val line = Path().apply { values.indices.forEach { if (it == 0) moveTo(point(it).x, point(it).y) else lineTo(point(it).x, point(it).y) } }
        val fill = Path().apply {
            addPath(line)
            lineTo(point(values.lastIndex).x, size.height)
            lineTo(point(0).x, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
        drawCircle(color, radius = 8f, center = point(values.lastIndex))
    }
}

/** A ring with [fraction] (0..1) of it filled and [center] text inside - an EV share, an eco score. */
@Composable
fun RingGauge(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 88.dp,
    center: @Composable () -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = this.size.minDimension * 0.13f, cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arc = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = stroke)
        }
        center()
    }
}

/**
 * A label with a value on the right and a thin coloured bar under it, [fraction] of the full width (none for zero).
 * The value text takes a readable tone of [color]; the bar keeps the palette colour.
 */
@Composable
fun MeterRow(label: String, value: String, fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelLarge, color = readable(color))
        }
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            drawRoundRect(track, size = size, cornerRadius = CornerRadius(size.height / 2))
            if (fraction > 0f) {
                drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0.02f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
            }
        }
    }
}

/** A coloured round badge with an emoji, for list rows. Decorative: a screen reader skips it, the row text says it all. */
@Composable
fun EmojiBadge(emoji: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(40.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(50)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { Text(emoji, style = MaterialTheme.typography.titleMedium) }
}

/** A small number tile: [value] big in a readable tone of [color] on its tint, [caption] under it. Put several in a [Row] with weight. */
@Composable
fun StatTile(value: String, caption: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = readable(color, tint = color.copy(alpha = 0.12f)))
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A tinted strip with a section title and an emoji, to open a group of rows inside a long list. */
@Composable
fun SectionHeader(emoji: String, text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EmojiBadge(emoji, color)
        Text(text, style = MaterialTheme.typography.titleMedium, color = readable(color))
    }
}

/**
 * The tank level over time: a line of [levels] (percent, oldest first, any spacing) with a dot where a refuel happened
 * ([refuelTimes], epoch millis). The x axis is time, so gaps between readings look like gaps. [description] is what a
 * screen reader says for the whole chart (it has no text of its own).
 */
@Composable
fun FuelLevelChart(
    levels: List<Pair<Long, Int>>,
    refuelTimes: List<Long>,
    modifier: Modifier = Modifier,
    color: Color = Palette.Blue,
    dot: Color = Palette.Orange,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    description: String? = null,
) {
    if (levels.size < 2) return
    val grid = MaterialTheme.colorScheme.outlineVariant
    val t0 = levels.first().first
    val span = (levels.last().first - t0).coerceAtLeast(1L).toFloat()
    val described = if (description == null) modifier else modifier.semantics { contentDescription = description }
    Canvas(modifier = described.fillMaxWidth().height(height)) {
        val pad = 10f
        fun x(t: Long) = pad + (size.width - 2 * pad) * ((t - t0) / span).coerceIn(0f, 1f)
        fun y(pct: Int) = pad + (size.height - 2 * pad) * (1f - pct.coerceIn(0, 100) / 100f)
        for (g in listOf(0, 25, 50, 75, 100)) drawLine(grid, Offset(pad, y(g)), Offset(size.width - pad, y(g)), strokeWidth = if (g == 0) 2f else 1f)
        val line = Path().apply { levels.forEachIndexed { i, (t, pct) -> if (i == 0) moveTo(x(t), y(pct)) else lineTo(x(t), y(pct)) } }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(levels.last().first), y(0))
            lineTo(x(levels.first().first), y(0))
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        for (t in refuelTimes) {
            if (t < t0 || t > levels.last().first) continue
            // The dot sits on the line: the first reading at or after the refuel.
            val reading = levels.firstOrNull { it.first >= t } ?: levels.last()
            drawCircle(Color.White, radius = 12f, center = Offset(x(t), y(reading.second)))
            drawCircle(dot, radius = 8f, center = Offset(x(t), y(reading.second)))
        }
    }
}
