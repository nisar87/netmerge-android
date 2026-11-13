package dev.netmerge.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** The scale's top: the smallest of these that leaves the needle some room. */
// tops that divide into four round quarters (2.5, 5, 7.5, 10 / 25, 50, 75, 100 / ...)
private val scales = listOf(10.0, 20.0, 40.0, 100.0, 200.0, 400.0, 1000.0, 2000.0, 4000.0)
fun gaugeMax(mbps: Double): Double = scales.firstOrNull { it >= mbps * 1.15 } ?: scales.last()

/**
 * A speedometer: a 240° arc with ticks, a coloured part up to the speed, a needle and the number in the middle.
 * max: the scale's top (see gaugeMax; keep it steady while a test runs so the needle does not jump with the scale).
 */
@Composable
fun Gauge(mbps: Double, max: Double, caption: String, color: Color, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val v by animateFloatAsState((mbps / max).coerceIn(0.0, 1.0).toFloat(), tween(450), label = "needle")
    val measurer = rememberTextMeasurer()
    val tickColor = scheme.onSurfaceVariant
    val track = scheme.surfaceContainerHighest
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = tickColor, fontSize = 11.sp)
    Box(modifier.aspectRatio(1.15f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val start = 150f
            val sweep = 240f
            val stroke = size.minDimension * 0.075f
            val r = size.minDimension / 2 - stroke
            val c = Offset(size.width / 2, size.height / 2 + size.minDimension * 0.06f)
            val box = Size(r * 2, r * 2)
            val topLeft = Offset(c.x - r, c.y - r)
            drawArc(track, start, sweep, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color), c),
                start, sweep * v, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // ticks and their numbers (0, a quarter, half, three quarters, top)
            for (i in 0..20) {
                val a = Math.toRadians((start + sweep * i / 20).toDouble())
                val major = i % 5 == 0
                val r1 = r - stroke * 0.9f
                val r2 = r1 - (if (major) stroke * 0.9f else stroke * 0.45f)
                drawLine(tickColor.copy(alpha = if (major) 0.8f else 0.35f),
                    Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx())
                if (major) {
                    val n = max * i / 20
                    val t = measurer.measure(if (n >= 1000) "${(n / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}G" else n.toInt().toString(), labelStyle)
                    val rl = r2 - stroke * 1.1f
                    drawText(t, topLeft = Offset(c.x + rl * cos(a).toFloat() - t.size.width / 2, c.y + rl * sin(a).toFloat() - t.size.height / 2))
                }
            }
            // the needle
            val a = Math.toRadians((start + sweep * v).toDouble())
            val tip = r - stroke * 1.2f
            drawLine(color, c, Offset(c.x + tip * cos(a).toFloat(), c.y + tip * sin(a).toFloat()), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color, stroke * 0.55f, c)
            drawCircle(scheme.surface, stroke * 0.25f, c)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speedText(mbps), style = MaterialTheme.typography.displayMedium.merge(Numbers))
            Text("Mbps · $caption", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
    }
}

/** A part of the dial's inner ring: one network's share of the traffic right now. */
data class Segment(val part: Float, val color: Color)

/**
 * The Home dial, after the desktop app's: an outer ring of ticks with the scale around it, a needle, and an inner ring
 * split by what each network carries; the combined download in the middle.
 *
 * Its text is sized from the dial, not from the phone's font size: with a large font (or Samsung's screen zoom) the
 * numbers grew while the dial did not, and they overlapped the ring (seen on a Galaxy S25 Ultra).
 */
@Composable
fun DialGauge(mbps: Double?, max: Double, segments: List<Segment>, needle: Color, modifier: Modifier = Modifier, eyebrow: String = "COMBINED", unit: String = "Mbps down") {
    val scheme = MaterialTheme.colorScheme
    val v by animateFloatAsState(((mbps ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat(), tween(600), label = "dial")
    val measurer = rememberTextMeasurer()
    val tick = scheme.onSurfaceVariant
    val disc = scheme.surfaceContainerHigh
    val track = scheme.surfaceContainerHighest
    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // sizes as parts of the dial's width; inside, 1 sp = 1 dp whatever the phone's font size
        val w = minOf(maxWidth, maxHeight).value
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density, 1f)) {
            val labelStyle = MaterialTheme.typography.labelMedium.copy(color = tick, fontFamily = FontFamily.Monospace,
                fontSize = (w * 0.040f).sp, lineHeight = (w * 0.046f).sp)
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val R = size.minDimension / 2 * 0.80f      // the dial
                val start = 135f
                val sweep = 270f
                drawCircle(disc, R, c)
                // ticks: 60 small, a big one at each quarter, with the scale outside the dial
                for (i in 0..60) {
                    val a = Math.toRadians((start + sweep * i / 60).toDouble())
                    val major = i % 15 == 0
                    val r1 = R * 0.94f
                    val r2 = if (major) R * 0.80f else if (i % 5 == 0) R * 0.86f else R * 0.89f
                    drawLine(tick.copy(alpha = if (major) 0.9f else 0.45f),
                        Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                        Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                        strokeWidth = if (major) 2.5.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round)
                    if (major) {
                        val n = max * i / 60
                        val txt = when { n >= 1000 -> "${(n / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }}G"; n % 1.0 == 0.0 -> n.toInt().toString(); else -> "%.1f".format(n) }
                        val t = measurer.measure(txt, labelStyle)
                        // just outside the dial: the label's centre a little further out for wider labels
                        val rl = R * 1.04f + maxOf(t.size.width, t.size.height) / 2f
                        drawText(t, topLeft = Offset(c.x + rl * cos(a).toFloat() - t.size.width / 2, c.y + rl * sin(a).toFloat() - t.size.height / 2))
                    }
                }
                // inner ring: each network's part of the traffic, a small gap between parts
                val ri = R * 0.62f
                val sw0 = R * 0.07f
                val box = Size(ri * 2, ri * 2)
                val tl = Offset(c.x - ri, c.y - ri)
                drawCircle(track, ri, c, style = Stroke(sw0))
                val shown = segments.filter { it.part > 0.01f }
                var at = -90f
                for (sg in shown) {
                    val sw = 360f * sg.part
                    val gap = if (shown.size > 1) 4f else 0f
                    drawArc(sg.color, at + gap / 2, (sw - gap).coerceAtLeast(1f), false, tl, box, style = Stroke(sw0, cap = StrokeCap.Butt))
                    at += sw
                }
                // the needle: from the inner ring out to the ticks, a knob at its end
                val a = Math.toRadians((start + sweep * v).toDouble())
                val p1 = Offset(c.x + ri * 1.12f * cos(a).toFloat(), c.y + ri * 1.12f * sin(a).toFloat())
                val p2 = Offset(c.x + R * 0.86f * cos(a).toFloat(), c.y + R * 0.86f * sin(a).toFloat())
                drawLine(needle, p1, p2, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(needle, R * 0.045f, p2)
            }
            // the middle: fits inside the inner ring (diameter about 0.9 of the dial's half-width)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(eyebrow, color = scheme.onSurfaceVariant, maxLines = 1,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = (w * 0.006f).sp, fontSize = (w * 0.036f).sp, lineHeight = (w * 0.044f).sp))
                Text(if (mbps == null) "—" else speedText(mbps), maxLines = 1,
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        fontSize = (w * 0.125f).sp, lineHeight = (w * 0.14f).sp))
                Text(unit, color = scheme.onSurfaceVariant, maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (w * 0.040f).sp, lineHeight = (w * 0.048f).sp))
            }
        }
    }
}
