package dev.netmerge.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Shown once, at the first start: what NetMerge does and what Android will ask. */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(scheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallMerge, null, tint = scheme.onPrimary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Wi-Fi and mobile data,\ntogether", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Point(Icons.Rounded.Speed, "Faster downloads",
            "Each new connection goes out on Wi-Fi or mobile data, more of them on the faster one.")
        Point(Icons.Rounded.Shield, "Fewer ads and trackers",
            "Ad, tracker and scam sites are blocked for every app while NetMerge is on.")
        Point(Icons.Rounded.DataUsage, "You stay in control",
            "See what each network carries, set a daily limit for mobile data, leave apps out.")
        Point(Icons.Rounded.VpnKey, "Android will ask once",
            "NetMerge works as a VPN on your phone. Your traffic goes straight out on your own networks, never through us.")
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var diag by remember { mutableStateOf(dev.netmerge.android.Diagnostics.enabled(ctx)) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                diag = !diag; dev.netmerge.android.Diagnostics.setEnabled(ctx, diag)
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = diag, onCheckedChange = { diag = it; dev.netmerge.android.Diagnostics.setEnabled(ctx, it) })
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Send diagnostics to help fix bugs", style = MaterialTheme.typography.titleSmall)
                Text("NetMerge's own log, settings, phone model and crashes, on Wi-Fi. Never the sites or apps you use. You can turn it off in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, text: String) {
    Row(Modifier.padding(vertical = 10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
