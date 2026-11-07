package com.jhaiian.clint.cloud

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class WebDavException(val kind: Kind, val httpCode: Int = 0, val detail: String? = null) : Exception(detail ?: kind.name) {
    enum class Kind { CONFIG, AUTH, NETWORK, SERVER }
}

data class WebDavEntry(
    val name: String,
    val href: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/** Minimal WebDAV client: PROPFIND, MKCOL, PUT, GET, DELETE with HTTP basic auth. */
class WebDavClient(config: WebDavSettings.Config) {

    private val baseUrl: HttpUrl = normalizeBaseUrl(config.serverUrl)
        ?: throw WebDavException(WebDavException.Kind.CONFIG, detail = "invalid url")
    private val authHeader = Credentials.basic(config.username, config.password, Charsets.UTF_8)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Checks that the server answers and accepts the credentials. */
    fun testConnection() {
        propfind(baseUrl, depth = 0).close()
    }

    /** Creates every folder in [path] (e.g. "Clint/backups/") that does not exist yet. */
    fun ensureDirectory(path: String) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current += "$segment/"
            val url = resolve(current)
            val request = newRequest(url).method("MKCOL", null).build()
            execute(request).use { response ->
                when (response.code) {
                    200, 201, 204, 301, 302, 405 -> Unit // created, or already exists
                    else -> throwForStatus(response)
                }
            }
        }
    }

    fun upload(path: String, file: File, contentType: String = "application/octet-stream") {
        val request = newRequest(resolve(path)).put(file.asRequestBody(contentType.toMediaType())).build()
        execute(request).use { response ->
            if (!response.isSuccessful) throwForStatus(response)
        }
    }

    /** Lists the direct children of the folder at [path]. */
    fun list(path: String): List<WebDavEntry> {
        val dirUrl = resolve(path)
        val body = propfind(dirUrl, depth = 1).use { response -> response.body.string() }
        val dirPath = dirUrl.encodedPath.trimEnd('/')
        return parseMultiStatus(body).filter { entry ->
            val entryPath = (entry.href.toHttpUrlOrNull()?.encodedPath ?: entry.href).trimEnd('/')
            entryPath != dirPath && entry.name.isNotEmpty()
        }
    }

    fun delete(href: String) {
        val url = baseUrl.resolve(href) ?: throw WebDavException(WebDavException.Kind.CONFIG, detail = href)
        val request = newRequest(url).delete().build()
        execute(request).use { response ->
            if (!response.isSuccessful && response.code != 404) throwForStatus(response)
        }
    }

    fun download(href: String, destination: File) {
        val url = baseUrl.resolve(href) ?: throw WebDavException(WebDavException.Kind.CONFIG, detail = href)
        val request = newRequest(url).get().build()
        execute(request).use { response ->
            if (!response.isSuccessful) throwForStatus(response)
            try {
                response.body.byteStream().use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                throw WebDavException(WebDavException.Kind.NETWORK, detail = describeNetworkError(e))
            }
        }
    }

    private fun propfind(url: HttpUrl, depth: Int): Response {
        val request = newRequest(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        val response = execute(request)
        if (response.code != 207 && response.code != 200) {
            response.use { throwForStatus(it) }
        }
        return response
    }

    private fun resolve(path: String): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()

    private fun newRequest(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", authHeader)

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw WebDavException(WebDavException.Kind.NETWORK, detail = describeNetworkError(e))
    }

    private fun throwForStatus(response: Response): Nothing {
        val code = response.code
        if (code == 401 || code == 403) throw WebDavException(WebDavException.Kind.AUTH, code)
        throw WebDavException(WebDavException.Kind.SERVER, code, response.message.ifBlank { null })
    }

    private fun parseMultiStatus(xml: String): List<WebDavEntry> {
        val entries = mutableListOf<WebDavEntry>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        var href: String? = null
        var size = 0L
        var modified = 0L
        var isDir = false
        var inResponse = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "response" -> { inResponse = true; href = null; size = 0L; modified = 0L; isDir = false }
                    "href" -> if (inResponse && href == null) href = parser.nextText().trim()
                    "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> if (inResponse) modified = parseHttpDate(parser.nextText().trim())
                    "collection" -> if (inResponse) isDir = true
                }
                XmlPullParser.END_TAG -> if (parser.name.lowercase(Locale.ROOT) == "response" && inResponse) {
                    inResponse = false
                    val h = href
                    if (h != null) entries.add(WebDavEntry(nameFromHref(h), h, size, modified, isDir))
                }
            }
            event = parser.next()
        }
        return entries
    }

    companion object {
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"

        fun normalizeBaseUrl(raw: String): HttpUrl? {
            var value = raw.trim()
            if (value.isEmpty()) return null
            if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
                value = "https://$value"
            }
            if (!value.endsWith("/")) value += "/"
            return value.toHttpUrlOrNull()
        }

        private fun nameFromHref(href: String): String {
            val path = href.toHttpUrlOrNull()?.encodedPath ?: href
            val last = path.trimEnd('/').substringAfterLast('/')
            return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        }

        private fun parseHttpDate(value: String): Long = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
        }.getOrDefault(0L)

        fun describeNetworkError(e: IOException): String = when (e) {
            is java.net.UnknownHostException -> "UNKNOWN_HOST"
            is java.net.SocketTimeoutException -> "TIMEOUT"
            is java.net.ConnectException -> "CONNECT"
            is javax.net.ssl.SSLException -> "TLS"
            else -> e.javaClass.simpleName
        }
    }
}
