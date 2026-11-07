package com.jhaiian.clint.automation

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.preference.PreferenceManager
import com.jhaiian.clint.BuildConfig
import com.jhaiian.clint.browser.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Built-in remote automation: a token-protected HTTP API served from inside the app,
 * plus an optional outbound WebSocket relay client and an optional CDP (WebView debugging) switch.
 * Everything is OFF by default.
 */
object RemoteAutomation {
    const val PREF_ENABLED = "remote_automation_enabled"
    const val PREF_TOKEN = "remote_automation_token"
    const val PREF_PORT = "remote_automation_port"
    const val PREF_LOOPBACK_ONLY = "remote_automation_loopback_only"
    const val PREF_RELAY_URL = "remote_automation_relay_url"
    const val PREF_CDP = "remote_automation_cdp"
    const val DEFAULT_PORT = 9333

    enum class ServerState { STOPPED, STARTING, RUNNING, FAILED }
    enum class RelayState { DISABLED, CONNECTING, CONNECTED, FAILED }

    data class Status(
        val server: ServerState = ServerState.STOPPED,
        val port: Int = DEFAULT_PORT,
        val loopbackOnly: Boolean = false,
        val serverError: String? = null,
        val relay: RelayState = RelayState.DISABLED,
        val relayError: String? = null,
        val lastCommand: String? = null,
        val lastCommandAt: Long = 0L,
        val lastCommandOk: Boolean = true,
        val commandCount: Int = 0
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    @Volatile private var activityRef: WeakReference<MainActivity>? = null
    @Volatile internal var currentToken: String = ""
    private var server: AutomationHttpServer? = null
    private var relay: AutomationRelayClient? = null
    private var lastCdpApplied: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    internal val executor = AutomationCommandExecutor()

    fun init(context: Context) {
        try { apply(context) } catch (_: Throwable) {}
    }

    fun attach(activity: MainActivity) { activityRef = WeakReference(activity) }

    fun detach(activity: MainActivity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    internal fun activity(): MainActivity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    fun prefs(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    fun isEnabled(context: Context) = prefs(context).getBoolean(PREF_ENABLED, false)
    fun port(context: Context) = prefs(context).getInt(PREF_PORT, DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    fun loopbackOnly(context: Context) = prefs(context).getBoolean(PREF_LOOPBACK_ONLY, false)
    fun relayUrl(context: Context) = prefs(context).getString(PREF_RELAY_URL, "")?.trim().orEmpty()
    fun cdpEnabled(context: Context) = prefs(context).getBoolean(PREF_CDP, false)

    fun token(context: Context): String = prefs(context).getString(PREF_TOKEN, null).orEmpty()

    fun ensureToken(context: Context): String {
        val existing = token(context)
        if (existing.isNotEmpty()) return existing
        return generateToken(context)
    }

    private fun generateToken(context: Context): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(PREF_TOKEN, token).commit()
        currentToken = token
        return token
    }

    fun regenerateToken(context: Context): String {
        val token = generateToken(context)
        // Reconnect the relay so it presents the new token.
        relay?.let { it.stop(); relay = null }
        apply(context)
        return token
    }

    /** Re-reads every preference and starts/stops the server, relay and CDP accordingly. */
    @Synchronized
    fun apply(context: Context) {
        val c = context.applicationContext
        applyCdp(c)
        if (!isEnabled(c)) {
            stopAll()
            return
        }
        currentToken = ensureToken(c)
        val port = port(c)
        val loopback = loopbackOnly(c)
        val existing = server
        if (existing == null || existing.port != port || existing.loopbackOnly != loopback ||
            _status.value.server == ServerState.FAILED) {
            existing?.stop()
            val s = AutomationHttpServer(port, loopback) { request -> handle(c, request) }
            server = s
            updateStatus { it.copy(server = ServerState.STARTING, port = port, loopbackOnly = loopback, serverError = null) }
            s.start { error ->
                updateStatus {
                    if (error == null) it.copy(server = ServerState.RUNNING, serverError = null)
                    else it.copy(server = ServerState.FAILED, serverError = error)
                }
            }
        }
        val url = relayUrl(c)
        val r = relay
        if (url.isEmpty()) {
            r?.stop(); relay = null
            updateStatus { it.copy(relay = RelayState.DISABLED, relayError = null) }
        } else if (r == null || r.url != url) {
            r?.stop()
            relay = AutomationRelayClient(url, { currentToken }, { request -> handle(c, request) }) { state, error ->
                updateStatus { it.copy(relay = state, relayError = error) }
            }.also { it.start() }
        }
    }

    private fun stopAll() {
        server?.stop(); server = null
        relay?.stop(); relay = null
        updateStatus { it.copy(server = ServerState.STOPPED, serverError = null, relay = RelayState.DISABLED, relayError = null) }
    }

    private fun applyCdp(context: Context) {
        val desired = (isEnabled(context) && cdpEnabled(context)) || BuildConfig.DEBUG
        // Avoid touching WebView at startup when nothing would change (default is off).
        if (lastCdpApplied == null && !desired) { lastCdpApplied = false; return }
        if (lastCdpApplied == desired) return
        lastCdpApplied = desired
        val run = Runnable { try { WebView.setWebContentsDebuggingEnabled(desired) } catch (_: Throwable) {} }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    internal fun handle(context: Context, request: AutomationRequest): AutomationResponse {
        val response = try {
            executor.execute(context, request)
        } catch (e: Throwable) {
            AutomationResponse.error(500, e.message ?: e.javaClass.simpleName)
        }
        if (request.path != "/ping") {
            val label = "${request.method} ${request.path}"
            updateStatus {
                it.copy(lastCommand = label, lastCommandAt = System.currentTimeMillis(),
                    lastCommandOk = response.status in 200..299, commandCount = it.commandCount + 1)
            }
        }
        return response
    }

    internal fun updateStatus(transform: (Status) -> Status) {
        synchronized(_status) { _status.value = transform(_status.value) }
    }

    /** LAN IPv4 addresses of this device (Wi-Fi first). */
    fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() } }
            .filter { it.isNotEmpty() }
            .distinct()
    } catch (_: Throwable) { emptyList() }
}
