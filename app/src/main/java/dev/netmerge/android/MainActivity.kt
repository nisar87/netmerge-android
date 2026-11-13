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
import androidx.compose.material.icons.rounded.Speed
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
    /** A speed test asked for from outside (test scripts: --ez speedtest true; result in files/speedtest.json). */
    private val speedTest = mutableStateOf(false)
    private val checkUpdates = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.init(this)
        // a waiting crash report, or the log every few hours on Wi-Fi (test scripts: --ez senddiagnostics true sends now)
        Diagnostics.maybeSend(this, force = intent.getBooleanExtra("senddiagnostics", false))
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 3)
        if (savedInstanceState == null && intent.hasExtra("merge")) pending += Intent(intent)
        tabRequest.value = intent.getStringExtra("tab")
        if (intent.getBooleanExtra("speedtest", false)) { tabRequest.value = "test"; speedTest.value = true }
        checkUpdates.value = intent.getBooleanExtra("checkupdates", false)
        setContent { NetMergeTheme { App(pending, tabRequest, speedTest, checkUpdates) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("merge")) pending += Intent(intent)
        intent.getStringExtra("tab")?.let { tabRequest.value = it }
        if (intent.getBooleanExtra("speedtest", false)) { tabRequest.value = "test"; speedTest.value = true }
        if (intent.getBooleanExtra("checkupdates", false)) checkUpdates.value = true
        if (intent.getBooleanExtra("senddiagnostics", false)) Diagnostics.maybeSend(this, force = true)
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Test("Speed test", Icons.Rounded.Speed), Block("Blocking", Icons.Rounded.Shield), Settings("Settings", Icons.Rounded.Settings)
}

@Composable
private fun App(pending: SnapshotStateList<Intent>, tabRequest: MutableState<String?>, speedTest: MutableState<Boolean>, checkUpdates: MutableState<Boolean>) {
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
    LaunchedEffect(starting) {
        if (starting) {
            delay(30_000)
            if (starting) EventLog.add(ctx, "ui: merging did not come up within 30 s${MergeVpnService.problem?.let { ": $it" } ?: ""}")
            starting = false
        }
    }

    fun service(extras: Intent?) = Intent(ctx, MergeVpnService::class.java).apply {
        extras?.extras?.let { putExtras(it) }
        removeExtra("merge")
    }
    fun launch(extras: Intent?) {
        if (ctx.logged("start the merging service") { ctx.startForegroundService(service(extras)) } != null) starting = true
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        EventLog.add(ctx, "ui: VPN permission ${if (r.resultCode == android.app.Activity.RESULT_OK) "given" else "refused"}")
        if (r.resultCode == android.app.Activity.RESULT_OK) launch(startExtras)
    }
    fun start(extras: Intent?) {
        startExtras = extras
        val ask = ctx.logged("check the VPN permission") { VpnService.prepare(ctx) }
        EventLog.add(ctx, "ui: merge on asked${if (extras != null) " (from outside)" else ""}${if (ask != null) ", Android asks for the VPN permission" else ""}")
        if (ask != null) ctx.logged("ask for the VPN permission") { consent.launch(ask) } else launch(extras)
    }
    fun stop() {
        EventLog.add(ctx, "ui: merge off asked")
        ctx.logged("stop the merging service") { ctx.startService(Intent(ctx, MergeVpnService::class.java).setAction(MergeVpnService.ACTION_STOP)) }
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
        when (tabRequest.value) { "home" -> tab = Tab.Home; "test" -> tab = Tab.Test; "block" -> tab = Tab.Block; "settings" -> tab = Tab.Settings }
        tabRequest.value = null
    }
    // a newer version: checked by itself at most once a day (tests: --ez checkupdates true, result in files/updates.json)
    LaunchedEffect(checkUpdates.value) {
        if (!Updates.checkedToday(ctx) || checkUpdates.value) withContext(Dispatchers.IO) { UpdateState.run(ctx) }
        checkUpdates.value = false
    }
    LaunchedEffect(speedTest.value) {
        if (speedTest.value) { speedTest.value = false; runSpeedTest(ctx) }
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
                    icon = { Icon(t.icon, null) }, label = { Text(t.label, maxLines = 1) },
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
                        Tab.Test -> SpeedTestScreen(live.running)
                        Tab.Block -> BlockingScreen(live)
                        Tab.Settings -> SettingsScreen(live, openApps = { appsOpen = true })
                    }
                }
            }
        }
    }
}
