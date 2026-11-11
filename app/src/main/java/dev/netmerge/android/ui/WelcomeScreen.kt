package dev.netmerge.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
            "NetMerge works as a VPN on your phone. Nothing is sent to us or anyone else: your traffic goes straight out on your own networks.")
        Spacer(Modifier.height(28.dp))
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
