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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(live: Live, starting: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val wifiColor = scheme.primary
    val mobileColor = scheme.secondary
    val on = live.running || starting || live.paused
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NetMerge", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        starting -> "Starting…"
                        live.paused -> "Paused to save battery (${live.battery}%). Starts again at ${live.resumeAt}%."
                        live.running -> "Wi-Fi and mobile data working together"
                        live.nets.all { it.connected } -> "Wi-Fi and mobile data are ready"
                        live.nets.none { it.connected } -> "No network"
                        // mobile data asleep while on Wi-Fi (most phones): merging wakes it
                        live.nets[0].connected && live.mobileDataOn -> "Ready: merging wakes mobile data"
                        live.nets[0].connected -> "Turn on mobile data to merge"
                        else -> "Connect to Wi-Fi to merge"
                    },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            MergeSwitch(on, starting, onToggle)
        }
        // a newer version: download opens it in the browser, which installs it over this one
        val newer = dev.netmerge.android.UpdateState.newer.value
        if (newer != null && !dev.netmerge.android.UpdateState.dismissed.value) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Spacer(Modifier.height(12.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = scheme.onPrimaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text("NetMerge ${newer.name} is ready" + if (newer.beta) " (beta)" else "",
                            style = MaterialTheme.typography.titleSmall, color = scheme.onPrimaryContainer)
                    }
                    Text("Download opens it in your browser; tap the download to install it over this version. Your settings stay.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { dev.netmerge.android.UpdateState.dismissed.value = true }) { Text("Later") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { dev.netmerge.android.Updates.download(ctx, newer) }) { Text("Download") }
                    }
                }
            }
        }
        live.problem?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = scheme.errorContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.onErrorContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // the dial: combined download, the inner ring split by what each network carries
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val max = gaugeMax(maxOf(live.peak, live.totalDown, 8.0))
                DialGauge(
                    mbps = if (live.running) live.totalDown else null, max = max,
                    segments = if (live.running) listOf(Segment(live.nets[0].part.toFloat(), wifiColor), Segment(live.nets[1].part.toFloat(), mobileColor)) else emptyList(),
                    needle = scheme.onSurface, modifier = Modifier.fillMaxWidth(0.92f),
                )
                Text(
                    when {
                        starting -> "Starting"
                        live.paused -> "Paused"
                        !live.running -> "Off"
                        live.totalMbps < 0.1 -> "Idle"
                        live.totalUp > live.totalDown -> "Uploading"
                        else -> "Downloading"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("scale 0–${max.toInt()} Mbps", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (!on) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onToggle, modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)) {
                        Icon(Icons.Rounded.PowerSettingsNew, null); Spacer(Modifier.width(8.dp))
                        Text("Start merging", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        // the numbers, two to a row
        val tiles = listOf(
            Triple(Icons.Rounded.ArrowUpward, "Upload", (if (live.running) speedText(live.totalUp) else "—") to "Mbps · combined, right now"),
            Triple(Icons.Rounded.Speed, "Peak speed", (if (live.running) speedText(live.peak) else "—") to "Mbps · since merging started"),
            Triple(Icons.Rounded.Download, "Downloaded", (if (live.running) mbText(live.sessionBytes) else "—") to "all networks, since merging started"),
            Triple(Icons.Rounded.Schedule, "Running for", (if (live.running) durationText(live.runningSecs) else "—") to "${live.nets.count { it.inMerge }} of 2 networks in use"),
            Triple(Icons.Rounded.DataUsage, "Mobile data today", mbText(live.mobileToday) to (if (live.overLimit) "daily limit reached" else "used by merging")),
            Triple(Icons.Rounded.Shield, "Ads blocked today", (if (live.blockOn) "%,d".format(live.blockedToday) else "Off") to "ads & trackers"),
        )
        for (pair in tiles.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((icon, label, value) in pair) StatTile(icon, label, value.first, value.second, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("Your networks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text("Live speed of each network, last 60 seconds", style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        for (n in live.nets) {
            NetworkCard(n, live.running, n.name == "mobile" && live.mobileDataOn, live.netHistory[n.name] ?: emptyList(),
                if (n.name == "wifi") wifiColor else mobileColor)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

fun durationText(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60}m ${s % 60}s"
    else -> "${s / 3600}h ${s % 3600 / 60}m"
}

/** The merging switch, as on the desktop app: a pill with the state and a switch. */
@Composable
private fun MergeSwitch(on: Boolean, busy: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) Brand.Teal.copy(alpha = 0.16f) else scheme.surfaceContainerHigh, tween(300), label = "pill")
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onToggle).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = if (on) "Stop merging" else "Start merging" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (busy) "Starting" else if (on) "Merging on" else "Merging off", style = MaterialTheme.typography.labelLarge,
            color = if (on) scheme.secondary else scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = scheme.secondary, checkedThumbColor = scheme.onSecondary))
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, caption: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, lineHeight = 15.sp, maxLines = 2)
        }
    }
}

@Composable
private fun Sparkline(points: List<Double>, upPoints: List<Double>, modifier: Modifier, line: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val max = maxOf(points.maxOrNull() ?: 0.0, upPoints.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        // upload: a thin amber line under the download's filled one
        if (upPoints.size >= 2) {
            val stepU = size.width / 59f
            val u0 = size.width - stepU * (upPoints.size - 1)
            val up = Path()
            upPoints.forEachIndexed { i, v ->
                val x = u0 + i * stepU
                val y = size.height - (v / max * size.height * 0.9).toFloat() - 2f
                if (i == 0) up.moveTo(x, y) else up.lineTo(x, y)
            }
            drawPath(up, Brand.Amber, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
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
private fun NetworkCard(n: NetLive, running: Boolean, onButAsleep: Boolean, history: List<Double>, color: Color) {
    val scheme = MaterialTheme.colorScheme
    val part by animateFloatAsState(n.part.toFloat(), tween(700), label = "part")
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (n.connected) color else scheme.outlineVariant))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(if (n.name == "wifi") Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt, null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(if (n.name == "wifi") "Wi-Fi" else "Mobile data", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val (label, dot) = when {
                    !n.connected && onButAsleep -> "Asleep" to scheme.outline
                    !n.connected -> "Not connected" to scheme.error
                    !running -> "Online" to color
                    !n.inMerge -> "Online · not in use" to scheme.outline
                    n.mbps < 0.1 -> "Online · idle" to color
                    else -> "Merging" to color
                }
                Row(Modifier.clip(RoundedCornerShape(50)).background(dot.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = dot)
                }
            }
            if (!n.connected && onButAsleep) {
                Text("Mobile data is on; the phone keeps it asleep while Wi-Fi is used. Merging wakes it.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            if (running && n.inMerge) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(speedText(n.down), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace))
                    Text(" Mbps", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ArrowUpward, "Upload", tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp).padding(bottom = 2.dp))
                    Text(speedText(n.up) + " Mbps", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Sparkline(history, emptyList(), Modifier.fillMaxWidth().height(44.dp), color)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share of traffic", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("${(n.part * 100).toInt()}%", style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { part }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = color, trackColor = scheme.surfaceVariant, drawStopIndicator = {},
                )
            }
        }
    }
}

