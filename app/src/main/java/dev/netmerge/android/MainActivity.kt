package dev.netmerge.android

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.netmerge.android.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The app's screens: Home (start/stop, live speed per network), Ad blocking, Settings.
 * Tests start and stop merging with: adb shell am start -n dev.netmerge.android/.MainActivity --ez merge true|false
 * (and extras mtu, ondemand, limitmb, exclude, include, blocking).
 */
class MainActivity : ComponentActivity() {
    /** A start asked for by a test (or the tile) that still waits for the screen to be ready. */
    private val pending = mutableStateListOf<Intent>() // in order: a stop and a start sent together both count
    /** A tab asked for from outside ("home", "block", "settings"; used for screenshots). */
    private val tabRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
        if (savedInstanceState == null && intent.hasExtra("merge")) pending += Intent(intent)
        tabRequest.value = intent.getStringExtra("tab")
        setContent { NetMergeTheme { App(pending, tabRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("merge")) pending += Intent(intent)
        intent.getStringExtra("tab")?.let { tabRequest.value = it }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Block("Ad blocking", Icons.Rounded.Shield), Settings("Settings", Icons.Rounded.Settings)
}

@Composable
private fun App(pending: SnapshotStateList<Intent>, tabRequest: MutableState<String?>) {
    val ctx = LocalContext.current
    val reader = remember { LiveReader(ctx) }
    var live by remember { mutableStateOf(Live()) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var appsOpen by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var startExtras by remember { mutableStateOf<Intent?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                live = withContext(Dispatchers.Default) { reader.read() }
                if (live.running) starting = false
                delay(1000)
            }
        }
    }
    LaunchedEffect(starting) { if (starting) { delay(30_000); starting = false } }

    fun service(extras: Intent?) = Intent(ctx, MergeVpnService::class.java).apply {
        extras?.extras?.let { putExtras(it) }
        removeExtra("merge")
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            ctx.startForegroundService(service(startExtras)); starting = true
        }
    }
    fun start(extras: Intent?) {
        startExtras = extras
        val ask = VpnService.prepare(ctx)
        if (ask != null) consent.launch(ask) else { ctx.startForegroundService(service(extras)); starting = true }
    }
    fun stop() {
        ctx.startService(Intent(ctx, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP))
        starting = false
    }
    // a start or stop asked for from outside (tests, the quick settings tile)
    LaunchedEffect(pending.size) {
        while (pending.isNotEmpty()) {
            val i = pending.removeAt(0)
            if (i.getBooleanExtra("merge", false)) start(i) else stop()
        }
    }

    LaunchedEffect(tabRequest.value) {
        when (tabRequest.value) { "home" -> tab = Tab.Home; "block" -> tab = Tab.Block; "settings" -> tab = Tab.Settings }
        tabRequest.value = null
    }
    // the first start shows a short welcome (tests skip it: they start with an extra)
    val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var welcomed by remember { mutableStateOf(prefs.getBoolean("welcomed", false)) }
    if (!welcomed && pending.isEmpty() && tabRequest.value == null) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            WelcomeScreen { prefs.edit().putBoolean("welcomed", true).apply(); welcomed = true }
        }
        return
    }
    if (!welcomed) { prefs.edit().putBoolean("welcomed", true).apply(); welcomed = true }
    BackHandler(enabled = appsOpen) { appsOpen = false }
    Scaffold(
        bottomBar = {
            if (!appsOpen) NavigationBar {
                for (t in Tab.entries) NavigationBarItem(
                    selected = tab == t, onClick = { tab = t },
                    icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).statusBarsPadding()) {
            if (appsOpen) {
                AppsScreen(onBack = { appsOpen = false })
            } else {
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        Tab.Home -> HomeScreen(live, starting) { if (live.running || live.paused) stop() else start(null) }
                        Tab.Block -> BlockingScreen(live)
                        Tab.Settings -> SettingsScreen(live, openApps = { appsOpen = true })
                    }
                }
            }
        }
    }
}
