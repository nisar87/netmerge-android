package dev.netmerge.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

const val TAG = "NetMergeSpike"

/**
 * Keeps Wi-Fi and mobile data up at the same time and lets a connection go out on either one.
 *
 * Android normally keeps only one network "in use": when Wi-Fi is connected, mobile data is torn down after a while.
 * Asking for a network with requestNetwork keeps it up for as long as the request stands. Each Network then gives
 * sockets bound to it (Network.socketFactory), which is what NetMerge does on Windows by binding to an adapter.
 */
class DualNetworks(context: Context, private val onChange: (() -> Unit)? = null) {
    enum class Kind(val transport: Int) { WIFI(NetworkCapabilities.TRANSPORT_WIFI), MOBILE(NetworkCapabilities.TRANSPORT_CELLULAR) }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val ctx = context.applicationContext
    private fun log(what: String) = EventLog.add(ctx, "net: $what")
    private val networks = ConcurrentHashMap<Kind, Network>()
    private val callbacks = ConcurrentHashMap<Kind, ConnectivityManager.NetworkCallback>()
    private val validated = ConcurrentHashMap<Kind, Boolean>() // Android checked that it reaches the internet

    fun start() {
        for (kind in Kind.values()) hold(kind, true)
    }

    /** Whether Android is asked to keep this network up (requested) right now. */
    fun held(kind: Kind): Boolean = callbacks.containsKey(kind)

    /**
     * Asks Android to keep a network up (true) or lets it go (false). Letting mobile data go when merging has nothing
     * to gain saves battery: the phone then keeps only its normal network.
     */
    @Synchronized
    fun hold(kind: Kind, on: Boolean) {
        if (!on) {
            val cb = callbacks.remove(kind) ?: return
            ctx.logged("let go of $kind") { cm.unregisterNetworkCallback(cb) }
            validated.remove(kind)
            if (networks.remove(kind) != null) onChange?.invoke()
            log("$kind let go")
            return
        }
        if (callbacks.containsKey(kind)) return
        run {
            val request = NetworkRequest.Builder()
                .addTransportType(kind.transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (callbacks[kind] !== this) return // released meanwhile
                    networks[kind] = network
                    Log.i(TAG, "$kind available: ${describe(network)}")
                    log("$kind up (${cm.getLinkProperties(network)?.interfaceName ?: "?"})")
                    onChange?.invoke()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (callbacks[kind] !== this) return // released meanwhile
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (networks[kind] == network && validated.put(kind, ok) != ok) {
                        Log.i(TAG, "$kind ${if (ok) "has internet" else "has no internet (not validated)"}")
                        log("$kind ${if (ok) "reaches the internet" else "does not reach the internet (Android's check failed)"}" +
                            (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) ", sign-in page waiting" else "") +
                            ", ${caps.linkDownstreamBandwidthKbps / 1000} Mbps down by Android's estimate")
                        onChange?.invoke()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    if (callbacks[kind] !== this) return // released meanwhile
                    if (networks[kind] == network) onChange?.invoke()
                }

                override fun onUnavailable() {
                    log("$kind not available")
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (callbacks[kind] !== this) return
                    log("$kind ${if (blocked) "blocked by Android (data saver or background limits)" else "no longer blocked"}")
                }

                override fun onLost(network: Network) {
                    if (callbacks[kind] !== this) return // released meanwhile
                    val was = networks.remove(kind, network)
                    validated.remove(kind)
                    Log.i(TAG, "$kind lost")
                    log("$kind lost${if (was) "" else " (an older one)"}")
                    onChange?.invoke()
                }
            }
            // registered before asking: Android may answer at once (on another thread) when the network is up
            callbacks[kind] = cb
            try {
                cm.requestNetwork(request, cb)
                log("$kind asked for")
            } catch (e: Exception) {
                // e.g. too many requests open: without this the network silently never comes
                callbacks.remove(kind)
                EventLog.error(ctx, "ask Android for $kind", e)
            }
        }
    }

    fun stop() {
        callbacks.values.forEach { ctx.logged("let go of a network") { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
        networks.clear()
    }

    fun network(kind: Kind): Network? = networks[kind]

    /** Waits until both networks are up (true) or the time runs out (false). */
    fun awaitBoth(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (networks.size == Kind.values().size) return true
            SystemClock.sleep(200)
        }
        return networks.size == Kind.values().size
    }

    fun linkProperties(network: Network): LinkProperties? = cm.getLinkProperties(network)

    fun describe(network: Network): String {
        val lp = cm.getLinkProperties(network)
        return "${lp?.interfaceName} ${lp?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" }}"
    }

    /** The networks for the engine: name, Android handle, and whether each reaches IPv4 / IPv6 addresses. */
    fun engineJson(): String = Kind.values().filter { usable(it) }.mapNotNull { kind ->
        val n = networks[kind] ?: return@mapNotNull null
        val addrs = cm.getLinkProperties(n)?.linkAddresses?.map { it.address } ?: emptyList()
        val v4 = addrs.any { it is java.net.Inet4Address }
        val v6 = addrs.any { it is java.net.Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress && (it.address[0].toInt() and 0xfe) != 0xfc }
        """{"name":"${kind.name.lowercase()}","handle":${n.networkHandle},"v4":$v4,"v6":$v6}"""
    }.joinToString(",", "[", "]")

    /**
     * Whether merging should send connections to this network: it must reach the internet (Android validated it; a
     * hotel Wi-Fi waiting for its sign-in page or a router without internet does not). When no network is validated
     * (yet), all are used, so the phone is never left without a way out.
     */
    fun usable(kind: Kind): Boolean {
        if (networks[kind] == null) return false
        val anyValidated = networks.keys.any { validated[it] == true }
        return !anyValidated || validated[kind] == true
    }

    fun all(): List<Network> = networks.values.toList()

    fun vpnActive(): Boolean = cm.allNetworks.any {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    data class Probe(val ok: Boolean, val status: Int, val ms: Long, val local: InetAddress?, val onNetwork: Boolean, val error: String?)

    /**
     * Opens one connection over the given network to a "generate 204" page, like NetMerge's health check, and checks
     * that the connection's own address belongs to that network (proof that it really left through it).
     */
    fun probe(network: Network, host: String = "connectivitycheck.gstatic.com", path: String = "/generate_204"): Probe {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            network.socketFactory.createSocket().use { s ->
                s.soTimeout = 8000
                val addr = network.getByName(host)
                s.connect(InetSocketAddress(addr, 80), 8000)
                s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: ""
                val status = line.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                val local = s.localAddress
                val mine = linkProperties(network)?.linkAddresses?.any { it.address == local } == true
                Probe(status in 200..399, status, SystemClock.elapsedRealtime() - t0, local, mine, null)
            }
        } catch (e: Exception) {
            Probe(false, 0, SystemClock.elapsedRealtime() - t0, null, false, e.toString())
        }
    }

    /** One line per network, for the screen and the log. */
    fun report(): String = buildString {
        for (kind in Kind.values()) {
            val n = networks[kind]
            if (n == null) {
                appendLine("$kind: not up")
                continue
            }
            val p = probe(n)
            appendLine("$kind: ${describe(n)} -> ${if (p.ok) "OK ${p.status}" else "FAILED ${p.error ?: p.status}"} in ${p.ms} ms, left from ${p.local?.hostAddress} (${if (p.onNetwork) "its own address" else "NOT its address"})")
        }
        append("VPN active: ${vpnActive()}")
    }.also { Log.i(TAG, it) }
}
