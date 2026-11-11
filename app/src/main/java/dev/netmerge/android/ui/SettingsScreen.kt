package dev.netmerge.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.netmerge.android.DualNetworks
import dev.netmerge.android.Excluded
import dev.netmerge.android.MergeVpnService
import dev.netmerge.android.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(live: Live, openApps: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var onDemand by remember { mutableStateOf(Settings.onDemand(ctx)) }
    var limit by remember { mutableStateOf(Settings.limitMB(ctx)) }
    var excluded by remember { mutableStateOf(Excluded.get(ctx).size) }
    var check by remember { mutableStateOf("") }
    var batteryStop by remember { mutableStateOf(Settings.batteryStop(ctx)) }
    var batteryResume by remember { mutableStateOf(Settings.batteryResume(ctx)) }
    // a running (or paused) merge looks at the battery again at once
    fun batteryChanged() {
        if (live.running || live.paused) runCatching {
            ctx.startService(Intent(ctx, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_CHECK))
        }
    }
    LaunchedEffect(Unit) { excluded = Excluded.get(ctx).size }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Section("Mobile data")
        Group {
            SwitchRow(Icons.Rounded.BatterySaver, "Only when it helps",
                "Mobile data is let go after a quiet minute and comes back for big downloads. Saves battery; a download already running gains less.",
                onDemand) { onDemand = it; Settings.setOnDemand(ctx, it) }
            HorizontalDivider(color = scheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DataUsage, null, tint = scheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Daily limit", style = MaterialTheme.typography.titleSmall)
                        Text("Mobile data merging may use per day. Used today: ${mbText(live.mobileToday)}",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in Settings.limits) {
                        FilterChip(selected = limit == l, onClick = { limit = l; Settings.setLimitMB(ctx, l) },
                            label = { Text(if (l == 0) "None" else if (l < 1000) "$l MB" else "${l / 1000} GB") })
                    }
                }
            }
        }

        Section("Battery")
        Group {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BatteryAlert, null, tint = scheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Pause when battery is low", style = MaterialTheme.typography.titleSmall)
                        Text("Merging stops at this level; apps keep using the phone's normal network.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in Settings.batteryStops) {
                        FilterChip(selected = batteryStop == l, onClick = {
                            batteryStop = l; Settings.setBatteryStop(ctx, l)
                            if (l != 0 && batteryResume < l + 5) { // the resume level must stay above the stop level
                                batteryResume = Settings.batteryResumes.first { it >= l + 5 }; Settings.setBatteryResume(ctx, batteryResume)
                            }
                            batteryChanged()
                        }, label = { Text(if (l == 0) "Never" else "$l%") })
                    }
                }
            }
            if (batteryStop != 0) {
                HorizontalDivider(color = scheme.outlineVariant)
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BatteryChargingFull, null, tint = scheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Start again when charged to", style = MaterialTheme.typography.titleSmall)
                            Text(if (live.paused) "Paused now: battery ${live.battery}%." else "Merging comes back by itself at this level.",
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in Settings.batteryResumes.filter { it >= batteryStop + 5 }) {
                            FilterChip(selected = batteryResume == l, onClick = {
                                batteryResume = l; Settings.setBatteryResume(ctx, l); batteryChanged()
                            }, label = { Text("$l%") })
                        }
                    }
                }
            }
        }

        Section("Start")
        Group {
            NavRow(Icons.Rounded.PowerSettingsNew, "Start automatically",
                "Opens Android's VPN settings: tap the gear next to NetMerge and turn on \"Always-on VPN\". NetMerge then starts with the phone.") {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }

        Section("Apps")
        Group {
            NavRow(Icons.Rounded.Apps, "Apps left out of merging",
                if (excluded == 0) "None. Use it for apps that do not work with a VPN." else "$excluded app${if (excluded == 1) "" else "s"}",
                openApps)
        }

        Section("Check")
        Group {
            NavRow(Icons.Rounded.NetworkCheck, "Check both networks",
                check.ifEmpty { "Opens a connection on Wi-Fi and on mobile data and shows how fast each answers" }) {
                check = "Checking…"
                scope.launch {
                    check = withContext(Dispatchers.IO) {
                        val n = DualNetworks(ctx); n.start(); n.awaitBoth(15_000); val r = n.report(); n.stop(); r
                    }
                }
            }
        }

        Section("About")
        Group {
            Column(Modifier.padding(16.dp)) {
                Text("How merging works", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "NetMerge runs a VPN on the phone that sends each new connection out on Wi-Fi or mobile data, more " +
                        "of them on the faster one. Downloads, app stores, browsers and streaming open many connections " +
                        "and get faster; one single connection cannot be faster than one network. Mobile data used " +
                        "while merging counts against your plan. Nothing leaves the phone except your own traffic.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                Text("Version $v", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), content = content)
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class AppItem(val pkg: String, val label: String, val icon: Drawable?)

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var left by remember { mutableStateOf(Excluded.get(ctx)) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
                .filter { it.pkg != ctx.packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Apps left out of merging", style = MaterialTheme.typography.titleLarge)
        }
        Text("Ticked apps use the phone's normal network, as if NetMerge were off. Applies the next time merging starts.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(20.dp, 12.dp), singleLine = true,
            placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val shown = list.filter { query.isBlank() || it.label.contains(query, true) }.sortedByDescending { it.pkg in left }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(shown, key = { it.pkg }) { a ->
                    val checked = a.pkg in left
                    Row(Modifier.fillMaxWidth().clickable {
                        Excluded.set(ctx, a.pkg, !checked); left = Excluded.get(ctx)
                    }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val bmp = remember(a.pkg) { a.icon?.toBitmap(96, 96)?.asImageBitmap() }
                        if (bmp != null) Image(bmp, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Checkbox(checked = checked, onCheckedChange = { Excluded.set(ctx, a.pkg, it); left = Excluded.get(ctx) })
                    }
                }
            }
        }
    }
}
