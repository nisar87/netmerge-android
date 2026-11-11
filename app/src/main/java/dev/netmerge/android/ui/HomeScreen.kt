package dev.netmerge.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(live: Live, starting: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NetMerge", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            StatusChip(live, starting)
        }
        Spacer(Modifier.height(28.dp))
        PowerButton(on = live.running, busy = starting, onClick = onToggle)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (live.running) speedText(live.totalMbps) else "—",
                style = MaterialTheme.typography.displayLarge.merge(Numbers),
            )
            Text(" Mbps", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(
            when {
                starting -> "Starting…"
                live.running -> "Both networks working together"
                live.nets.all { it.connected } -> "Wi-Fi and mobile data are ready"
                live.nets.none { it.connected } -> "No network"
                else -> "Turn on both Wi-Fi and mobile data to merge"
            },
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Sparkline(live.history, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(16.dp))
        for (n in live.nets) {
            NetworkCard(n, live.running)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStat(Icons.Rounded.DataUsage, "Mobile data by merging today",
                mbText(live.mobileToday) + if (live.overLimit) " · limit reached" else "", Modifier.weight(1f))
            MiniStat(Icons.Rounded.Shield, "Ads & trackers blocked",
                if (live.blockOn) "%,d".format(live.blocked) else "Off", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusChip(live: Live, starting: Boolean) {
    val (label, color) = when {
        starting -> "Starting" to Brand.Amber
        live.running -> "On" to Brand.Teal
        else -> "Off" to MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun PowerButton(on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (on) scheme.primary else scheme.surfaceContainerHighest, tween(400), label = "fill")
    val icon by animateColorAsState(if (on) scheme.onPrimary else scheme.onSurfaceVariant, tween(400), label = "icon")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ring")
    val spin by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "spin")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        if (on) {
            Box(Modifier.size(170.dp).scale(ring).clip(CircleShape).background(scheme.primary.copy(alpha = 0.12f)))
            Box(Modifier.size(170.dp).scale(1f + (ring - 1f) / 2).clip(CircleShape).background(scheme.primary.copy(alpha = 0.10f)))
        }
        if (busy) {
            Canvas(Modifier.size(184.dp)) {
                drawArc(Brand.Amber, spin, 90f, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(164.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(fill.copy(alpha = 0.92f), fill)))
                .clickable(onClick = onClick)
                .semantics { contentDescription = if (on) "Stop merging" else "Start merging" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = icon, modifier = Modifier.size(56.dp))
                Text(if (on) "Merging" else "Tap to merge", color = icon, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Sparkline(points: List<Double>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val max = (points.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val stepX = size.width / 59f
        val x0 = size.width - stepX * (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = x0 + i * stepX
            val y = size.height - (v / max * size.height * 0.9).toFloat() - 2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fillPath = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(x0, size.height); close() }
        drawPath(fillPath, Brush.verticalGradient(listOf(line.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun NetworkCard(n: NetLive, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val color = if (n.name == "wifi") scheme.primary else scheme.secondary
    val part by animateFloatAsState(n.part.toFloat(), tween(700), label = "part")
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(if (n.name == "wifi") Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt, null, tint = color)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (n.name == "wifi") "Wi-Fi" else "Mobile data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !n.connected -> "Not connected"
                            !running -> "Connected"
                            !n.inMerge -> "Connected, not in use"
                            else -> "${(n.share * 100).toInt()}% of new connections"
                        },
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                if (running && n.inMerge) {
                    Text(speedText(n.mbps), style = MaterialTheme.typography.titleLarge.merge(Numbers))
                    Text(" Mbps", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (running) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { part }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = color, trackColor = scheme.surfaceVariant, drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.merge(Numbers), fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, lineHeight = 16.sp)
        }
    }
}
