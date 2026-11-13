package dev.netmerge.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.netmerge.android.DualNetworks
import dev.netmerge.android.SpeedTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The speed test, kept while the app runs (switching tabs does not lose it). */
object SpeedTestRun {
    val state = mutableStateOf(SpeedTest.State())
    val max = mutableStateOf(100.0)
}

/** Runs the test on a background thread; also writes files/speedtest.json for test scripts. */
suspend fun runSpeedTest(ctx: Context) = withContext(Dispatchers.IO) {
    if (SpeedTestRun.state.value.running) return@withContext
    SpeedTestRun.state.value = SpeedTest.State(running = true, phase = "Finding Wi-Fi and mobile data")
    dev.netmerge.android.EventLog.add(ctx, "speed test: begun (merging ${if (dev.netmerge.android.MergeVpnService.running) "on" else "off"})")
    SpeedTestRun.max.value = 100.0
    val n = DualNetworks(ctx)
    n.start()
    n.awaitBoth(10_000)
    // many phones keep mobile data asleep while on Wi-Fi: waking it and checking it works can take 20 s or more
    // (seen on a Galaxy S26 Ultra: 10 s was not enough)
    val until = android.os.SystemClock.elapsedRealtime() + 30_000
    while (android.os.SystemClock.elapsedRealtime() < until &&
        !(n.usable(DualNetworks.Kind.WIFI) && n.usable(DualNetworks.Kind.MOBILE))) {
        SpeedTestRun.state.value = SpeedTest.State(running = true, phase = "Waking mobile data")
        Thread.sleep(500)
    }
    val wifi = n.network(DualNetworks.Kind.WIFI)?.takeIf { n.usable(DualNetworks.Kind.WIFI) }
    val mobile = n.network(DualNetworks.Kind.MOBILE)?.takeIf { n.usable(DualNetworks.Kind.MOBILE) }
    try {
        if (wifi == null && mobile == null) {
            SpeedTestRun.state.value = SpeedTest.State(phase = "No network to test")
            dev.netmerge.android.EventLog.add(ctx, "speed test: no network to test")
            return@withContext
        }
        SpeedTest.run(wifi, mobile) { s ->
            // the scale only grows during a test, so the needle does not jump when it changes
            val m = gaugeMax(maxOf(s.now, s.rows.maxOfOrNull { maxOf(it.down, it.up) } ?: 0.0))
            if (m > SpeedTestRun.max.value) SpeedTestRun.max.value = m
            SpeedTestRun.state.value = s
        }
    } finally {
        n.stop()
    }
    if (mobile == null && wifi != null) {
        // say why there is no merged result, rather than leaving it out
        val st = SpeedTestRun.state.value
        SpeedTestRun.state.value = st.copy(rows = st.rows + SpeedTest.Row("Mobile data", error = "did not come up: is it on?"))
    }
    val s = SpeedTestRun.state.value
    dev.netmerge.android.EventLog.add(ctx, "speed test: " + s.rows.joinToString("; ") {
        "${it.label} %.1f down %.1f up".format(it.down, it.up) + (it.error?.let { e -> " ($e)" } ?: "")
    } + "; mobile data used %.0f MB".format(s.mobileMB) + (dev.netmerge.android.SpeedTest.lastError?.let { "; last error: ${it.take(150)}" } ?: ""))
    runCatching {
        val rows = JSONArray()
        for (r in s.rows) rows.put(JSONObject().put("label", r.label).put("down", r.down).put("up", r.up).put("error", r.error ?: ""))
        java.io.File(ctx.filesDir, "speedtest.json").writeText(
            JSONObject().put("done", s.done).put("rows", rows).put("mobileMB", s.mobileMB).put("at", System.currentTimeMillis()).toString())
    }
}

@Composable
fun SpeedTestScreen(merging: Boolean) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val s by SpeedTestRun.state
    val max by SpeedTestRun.max
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Speed test", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth())
        Text(
            "Tests Wi-Fi alone, mobile data alone, then both together, with ${SpeedTest.CONNS} connections at once: " +
                "enough to show the full merged speed. About a minute.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        val color = if (s.upload) Brand.Amber else scheme.primary
        val shown = if (s.running) s.now else (s.rows.lastOrNull()?.down ?: 0.0)
        DialGauge(
            mbps = if (s.running || s.rows.isNotEmpty()) shown else null, max = max,
            segments = if (s.running || s.rows.isNotEmpty()) listOf(Segment(1f, color)) else emptyList(),
            needle = scheme.onSurface, modifier = Modifier.fillMaxWidth(0.9f),
            eyebrow = when {
                s.running && s.upload -> "UPLOAD"
                s.running -> "DOWNLOAD"
                s.rows.isNotEmpty() -> when (s.rows.last().label) { "Both together" -> "MERGED"; "Mobile data" -> "MOBILE"; else -> "WI-FI" }
                else -> "READY"
            },
            unit = if (s.running && s.upload) "Mbps up" else "Mbps down",
        )
        Spacer(Modifier.height(4.dp))
        if (s.running) {
            Text(s.phase, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), drawStopIndicator = {})
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { SpeedTest.cancel() }) { Text("Stop") }
        } else {
            if (s.phase.isNotEmpty() && !s.done) Text(s.phase, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { scope.launch { runSpeedTest(ctx) } }, modifier = Modifier.heightIn(min = 52.dp).fillMaxWidth(0.85f)) {
                Icon(Icons.Rounded.Speed, null); Spacer(Modifier.width(8.dp))
                Text(if (s.rows.isEmpty()) "Start test" else "Test again", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            }
        }
        if (s.rows.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            val top = s.rows.maxOf { maxOf(it.down, it.up) }.coerceAtLeast(1.0)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    for (r in s.rows) ResultRow(r, top)
                    val alone = s.rows.filter { it.label != "Both together" }
                    val both = s.rows.firstOrNull { it.label == "Both together" }
                    val best = alone.maxOfOrNull { it.down } ?: 0.0
                    if (both != null && best > 0) {
                        HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
                        val gain = (both.down / best - 1) * 100
                        Text(
                            if (gain >= 5) "Merging adds about %.0f%% to your faster network's download.".format(gain)
                            else "Merging adds little now: one network is much slower than the other, or both share one line.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (s.mobileMB > 0) Text("This test used %.0f MB of mobile data.".format(s.mobileMB),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        if (merging) {
            Spacer(Modifier.height(12.dp))
            Text("Merging is on: apps using the internet now share the networks with the test and lower its numbers.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultRow(r: SpeedTest.Row, top: Double) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (r.label) { "Wi-Fi" -> Icons.Rounded.Wifi; "Mobile data" -> Icons.Rounded.SignalCellularAlt; else -> Icons.Rounded.AllInclusive },
                null, tint = scheme.primary, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(r.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (r.error != null) Text(r.error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
        Bar(Icons.Rounded.ArrowDownward, r.down, top, scheme.primary)
        Bar(Icons.Rounded.ArrowUpward, r.up, top, Brand.Amber)
    }
}

@Composable
private fun Bar(icon: androidx.compose.ui.graphics.vector.ImageVector, v: Double, top: Double, color: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((v / top).toFloat().coerceIn(0f, 1f)).clip(RoundedCornerShape(5.dp)).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Text("${speedText(v)} Mbps", style = MaterialTheme.typography.bodyMedium.merge(Numbers), modifier = Modifier.widthIn(min = 84.dp))
    }
}
