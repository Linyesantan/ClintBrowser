package com.jhaiian.clint.automation

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.jhaiian.clint.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Outbound relay mode: keeps a WebSocket open to a user-chosen relay server and executes the
 * same JSON commands the HTTP API accepts. Every command must carry the access token.
 *
 * Command:  {"id":"1","method":"GET","path":"/tabs","body":{},"token":"<token>"}
 * Reply:    {"id":"1","status":200,"contentType":"application/json","body":{...}}
 *           (binary results such as screenshots come back as "bodyBase64")
 */
class AutomationRelayClient(
    val url: String,
    private val tokenProvider: () -> String,
    private val handler: (AutomationRequest) -> AutomationResponse,
    private val onState: (RemoteAutomation.RelayState, String?) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val worker = Executors.newFixedThreadPool(2)
    private val retryHandler = Handler(Looper.getMainLooper())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    private var backoffMs = 3_000L

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        retryHandler.removeCallbacksAndMessages(null)
        try { socket?.close(1000, "disabled") } catch (_: Throwable) {}
        socket = null
        worker.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        if (stopped) return
        onState(RemoteAutomation.RelayState.CONNECTING, null)
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${tokenProvider()}")
                .header("X-Clint-Client", "clint/${BuildConfig.VERSION_NAME}")
                .build()
        } catch (e: Throwable) {
            onState(RemoteAutomation.RelayState.FAILED, e.message ?: "invalid url")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 3_000L
                onState(RemoteAutomation.RelayState.CONNECTED, null)
                webSocket.send(JSONObject().put("type", "hello").put("client", "clint")
                    .put("version", BuildConfig.VERSION_NAME).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    worker.execute { handleMessage(webSocket, text) }
                } catch (_: Throwable) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                onState(RemoteAutomation.RelayState.FAILED, "closed ($code)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                val message = response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName)
                onState(RemoteAutomation.RelayState.FAILED, message)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        retryHandler.postDelayed({ connect() }, delay)
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (_: Throwable) { return }
        if (msg.optString("type") == "ping") {
            webSocket.send(JSONObject().put("type", "pong").toString()); return
        }
        val id = msg.opt("id") ?: return
        val reply = JSONObject().put("id", id)
        val authenticated = tokenMatches(msg.optString("token", ""), tokenProvider())
        val response = if (!authenticated) {
            AutomationResponse.error(401, "unauthorized: missing or wrong token")
        } else {
            val path = msg.optString("path", "/").substringBefore('?').trimEnd('/').ifEmpty { "/" }
            val body = msg.optJSONObject("body") ?: JSONObject()
            val query = HashMap<String, String>()
            msg.optJSONObject("query")?.let { q -> q.keys().forEach { k -> query[k] = q.optString(k) } }
            handler(AutomationRequest(msg.optString("method", "GET").uppercase(), path, query, emptyMap(), body, true))
        }
        reply.put("status", response.status).put("contentType", response.contentType)
        if (response.contentType.startsWith("application/json")) {
            val raw = String(response.bytes, Charsets.UTF_8)
            reply.put("body", try { JSONArray("[$raw]").get(0) } catch (_: Throwable) { raw })
        } else {
            reply.put("bodyBase64", Base64.encodeToString(response.bytes, Base64.NO_WRAP))
        }
        webSocket.send(reply.toString())
    }
}
