package dev.netmerge.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** The scale's top: the smallest of these that leaves the needle some room. */
private val scales = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0)
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
