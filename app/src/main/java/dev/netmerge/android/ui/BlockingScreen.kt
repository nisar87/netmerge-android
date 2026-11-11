package dev.netmerge.android.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.netmerge.android.Blocklists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BlockingScreen(live: Live) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(Blocklists.on(ctx)) }
    var packs by remember { mutableStateOf(Blocklists.packs.associate { it.id to Blocklists.packOn(ctx, it.id) }) }
    var allowed by remember { mutableStateOf(Blocklists.allowed(ctx)) }
    var updating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun applyNow() = scope.launch {
        val n = withContext(Dispatchers.IO) { runCatching { Blocklists.apply(ctx) }.getOrDefault(0) }
        note = if (on) "%,d sites on the lists".format(n) else "Blocking is off"
    }
    fun update(force: Boolean) = scope.launch {
        updating = true
        note = "Downloading lists…"
        val ok = withContext(Dispatchers.IO) { runCatching { Blocklists.refresh(ctx, force) }.getOrDefault(false) }
        updating = false
        if (!ok && force) note = "Could not download the lists; the ones on the phone stay in use"
        applyNow()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Text("Ad blocking", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text("Ads, trackers and harmful sites are stopped for every app, while NetMerge is on.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = if (on) Brand.Teal else scheme.outline, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Block ads & trackers", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (!live.running) "Starts when NetMerge is on"
                            else if (on) "%,d blocked of %,d look-ups".format(live.blocked, live.asked) else "Off",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = { on = it; Blocklists.setOn(ctx, it); applyNow() })
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Lists", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }
        items(Blocklists.packs) { p ->
            val checked = packs[p.id] == true
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(p.about, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        val t = Blocklists.updated(ctx, p.id)
                        if (checked) Text(
                            if (t == 0L) "Not downloaded yet" else "Updated " + DateUtils.getRelativeTimeSpanString(t),
                            style = MaterialTheme.typography.labelSmall, color = scheme.primary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = checked, enabled = on, onCheckedChange = {
                        Blocklists.setPack(ctx, p.id, it); packs = packs + (p.id to it)
                        if (it) update(false) else applyNow()
                    })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { update(true) }, enabled = on && !updating) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Update lists now")
                }
                Spacer(Modifier.width(12.dp))
                if (updating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Text("Recently blocked", style = MaterialTheme.typography.titleMedium)
            Text("If an app or site stops working, allow what it needs.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (live.recent.isEmpty()) Text(if (live.running && on) "Nothing blocked yet" else "—",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        items(live.recent.filter { it.first !in allowed }.take(30), key = { "r-" + it.first }) { (name, times) ->
            NameRow(name, if (times == 1) "once" else "$times times", "Allow") {
                Blocklists.allow(ctx, name, true); allowed = Blocklists.allowed(ctx); applyNow()
            }
        }
        if (allowed.isNotEmpty()) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("Always allowed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(allowed.sorted(), key = { "a-$it" }) { name ->
                NameRow(name, "never blocked", "Remove") {
                    Blocklists.allow(ctx, name, false); allowed = Blocklists.allowed(ctx); applyNow()
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text(
                "If Private DNS (Settings → Connections → More connection settings) is set to a specific provider, apps " +
                    "ask that provider directly and ads are not blocked. \"Automatic\" or \"Off\" works.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NameRow(name: String, detail: String, action: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        TextButton(onClick = onAction) { Text(action) }
    }
    HorizontalDivider(color = scheme.outlineVariant)
}
