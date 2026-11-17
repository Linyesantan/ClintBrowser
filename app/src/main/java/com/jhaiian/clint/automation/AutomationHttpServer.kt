package com.jhaiian.clint.automation

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AutomationRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: JSONObject,
    /** True when the transport already verified the token. */
    val authenticated: Boolean
) {
    fun param(name: String): String? =
        if (body.has(name) && !body.isNull(name)) body.get(name).toString() else query[name]
}

class AutomationResponse(val status: Int, val contentType: String, val bytes: ByteArray) {
    companion object {
        fun json(obj: JSONObject, status: Int = 200) =
            AutomationResponse(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

        fun error(status: Int, message: String) =
            json(JSONObject().put("ok", false).put("error", message), status)

        fun png(bytes: ByteArray) = AutomationResponse(200, "image/png", bytes)
    }
}

internal fun tokenMatches(provided: String?, expected: String): Boolean {
    if (provided.isNullOrEmpty() || expected.isEmpty()) return false
    return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

/**
 * Minimal HTTP/1.1 server (one request per connection) for the automation API.
 *
 * Every connection gets its own worker thread right away (no hidden queue), so a slow command
 * never delays /ping or /status. Commands that touch the browser are limited to
 * [MAX_COMMANDS] at a time; a request that cannot get a slot within [COMMAND_WAIT_MS] or whose
 * client already hung up is dropped, and a full pool answers 503 instead of queuing silently.
 */
class AutomationHttpServer(
    val port: Int,
    val loopbackOnly: Boolean,
    private val handler: (AutomationRequest) -> AutomationResponse
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    // Recreated on every start() so stop()/start() (toggling the switch) always gets a live pool.
    @Volatile private var pool: ThreadPoolExecutor? = null
    private val commandSlots = Semaphore(MAX_COMMANDS, true)

    fun start(onResult: (String?) -> Unit) {
        running = true
        val workers = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue())
        pool?.shutdownNow()
        pool = workers
        Thread({
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    val address = if (loopbackOnly) InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
                    else InetSocketAddress(port)
                    bind(address, BACKLOG)
                }
            } catch (e: Throwable) {
                running = false
                onResult(e.message ?: e.javaClass.simpleName)
                return@Thread
            }
            serverSocket = ss
            if (!running) { try { ss.close() } catch (_: Throwable) {}; return@Thread }
            onResult(null)
            while (running) {
                val socket = try { ss.accept() } catch (_: Throwable) { break }
                try {
                    workers.execute { serve(socket) }
                } catch (_: RejectedExecutionException) {
                    rejectBusy(socket)
                } catch (_: Throwable) {
                    try { socket.close() } catch (_: Throwable) {}
                }
            }
        }, "clint-automation-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        pool?.shutdownNow()
        pool = null
    }

    /** Every worker is busy: answer 503 right away instead of letting the connection hang. */
    private fun rejectBusy(socket: Socket) {
        try {
            socket.soTimeout = 1_000
            write(socket, AutomationResponse.error(503, "busy: too many open connections, retry shortly"))
            socket.shutdownOutput()
        } catch (_: Throwable) {
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    /** True when the client has already closed its side (the request body was read in full). */
    private fun clientGone(socket: Socket): Boolean {
        val previous = socket.soTimeout
        return try {
            socket.soTimeout = 1
            socket.getInputStream().read() < 0
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Throwable) {
            true
        } finally {
            try { socket.soTimeout = previous } catch (_: Throwable) {}
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.firstOrNull()?.split(" ") ?: return
                if (requestLine.size < 2) return
                val method = requestLine[0].uppercase()
                val target = requestLine[1]
                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val path = target.substringBefore('?').trimEnd('/').ifEmpty { "/" }
                val query = parseQuery(target.substringAfter('?', ""))
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length > MAX_BODY) {
                    write(s, AutomationResponse.error(413, "body too large")); return
                }
                val bodyBytes = if (length > 0) readFully(input, length) else ByteArray(0)

                if (method == "OPTIONS") {
                    write(s, AutomationResponse(204, "text/plain", ByteArray(0))); return
                }
                val bearer = headers["authorization"]?.let {
                    if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null
                }
                val authenticated = tokenMatches(bearer ?: query["token"], RemoteAutomation.currentToken)
                if (!authenticated && path != "/ping") {
                    Thread.sleep(300)
                    write(s, AutomationResponse.error(401, "unauthorized: missing or wrong token")); return
                }
                val body = try {
                    val text = String(bodyBytes, Charsets.UTF_8).trim()
                    if (text.startsWith("{")) JSONObject(text) else JSONObject()
                } catch (_: Throwable) {
                    write(s, AutomationResponse.error(400, "body must be a JSON object")); return
                }
                val request = AutomationRequest(method, path, query, headers, body, authenticated)
                // Liveness/status never touch the browser: answer them without waiting for a slot.
                if (path in LIGHT_PATHS) {
                    write(s, handler(request)); return
                }
                if (!commandSlots.tryAcquire(COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    write(s, AutomationResponse.error(503, "busy: $MAX_COMMANDS commands already running, retry shortly"))
                    return
                }
                try {
                    if (clientGone(s)) return
                    write(s, handler(request))
                } finally {
                    commandSlots.release()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return String(buffer.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
        return null
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun write(socket: Socket, response: AutomationResponse) {
        val reason = when (response.status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 503 -> "Service Unavailable"
            504 -> "Gateway Timeout"; else -> if (response.status < 400) "OK" else "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(response.bytes)
        out.flush()
    }

    private companion object {
        const val MAX_HEAD = 64 * 1024
        const val MAX_BODY = 4 * 1024 * 1024
        const val BACKLOG = 50
        const val MAX_CONNECTIONS = 16
        const val MAX_COMMANDS = 4
        const val COMMAND_WAIT_MS = 5_000L
        val LIGHT_PATHS = setOf("/ping", "/status", "/")
    }
}
