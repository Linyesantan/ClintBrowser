package com.jhaiian.clint.automation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import com.jhaiian.clint.BuildConfig
import com.jhaiian.clint.R
import com.jhaiian.clint.browser.MainActivity
import com.jhaiian.clint.browser.delegates.closeTabsWithUndo
import com.jhaiian.clint.browser.delegates.focusTabById
import com.jhaiian.clint.browser.delegates.formatUrl
import com.jhaiian.clint.browser.delegates.loadUrl
import com.jhaiian.clint.browser.delegates.openNewTab
import com.jhaiian.clint.tabs.BrowserTab
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Executes automation commands against the browser UI on the main thread. */
class AutomationCommandExecutor {
    private val main = Handler(Looper.getMainLooper())

    private class ApiException(val status: Int, message: String) : Exception(message)

    fun execute(context: Context, request: AutomationRequest): AutomationResponse {
        return try {
            route(context, request)
        } catch (e: ApiException) {
            AutomationResponse.error(e.status, e.message ?: "error")
        } catch (e: TimeoutException) {
            AutomationResponse.error(504, e.message ?: "timeout")
        }
    }

    private fun route(context: Context, r: AutomationRequest): AutomationResponse {
        return when (r.path) {
            "/ping" -> ok().put("app", "Clint").put("auth", if (r.authenticated) "ok" else "required").json()
            "/", "/status" -> ok()
                .put("app", "Clint")
                .put("version", BuildConfig.VERSION_NAME)
                .put("windowOpen", RemoteAutomation.activity() != null)
                .json()
            "/tabs" -> tabs()
            "/open" -> open(context, r)
            "/eval" -> eval(r)
            "/click" -> click(r)
            "/type" -> type(r)
            "/text" -> text(r)
            "/screenshot" -> screenshot(r)
            "/back" -> navigate(r) { wv -> if (wv.canGoBack()) { wv.goBack(); true } else false }
            "/forward" -> navigate(r) { wv -> if (wv.canGoForward()) { wv.goForward(); true } else false }
            "/reload" -> navigate(r) { wv -> wv.reload(); true }
            "/activate" -> activate(r)
            "/close" -> close(r)
            else -> throw ApiException(404, "unknown endpoint: ${r.path}")
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun ok() = JSONObject().put("ok", true)
    private fun JSONObject.json() = AutomationResponse.json(this)

    private fun requireActivity(): MainActivity =
        RemoteAutomation.activity() ?: throw ApiException(503, "browser window is not open")

    @Suppress("UNCHECKED_CAST")
    private fun <T> onMain(timeoutMs: Long = 10_000, block: (complete: (T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        main.post {
            try {
                block { value -> result.set(value); latch.countDown() }
            } catch (t: Throwable) {
                error.set(t); latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException("timed out waiting for the browser")
        error.get()?.let { throw it }
        return result.get() as T
    }

    private fun MainActivity.findTab(tabId: String?): BrowserTab {
        if (!tabId.isNullOrEmpty()) {
            return tabManager.tabs.firstOrNull { it.id == tabId } ?: throw ApiException(404, "no tab with id $tabId")
        }
        return tabManager.activeTab ?: throw ApiException(409, "no active tab")
    }

    private fun tabJson(activity: MainActivity, tab: BrowserTab, index: Int) = JSONObject()
        .put("id", tab.id)
        .put("index", index)
        .put("title", tab.title)
        .put("url", tab.webView.url ?: tab.url)
        .put("active", tab.id == activity.tabManager.activeTab?.id)
        .put("incognito", tab.isIncognito)
        .put("profileId", tab.profileId)
        .put("groupId", tab.groupId ?: JSONObject.NULL)

    private fun runJs(tabId: String?, js: String, timeoutMs: Long = 15_000): Any? {
        val activity = requireActivity()
        val raw: String? = onMain(timeoutMs) { complete ->
            val tab = activity.findTab(tabId)
            tab.webView.evaluateJavascript(js) { value -> complete(value) }
        }
        return parseJsValue(raw)
    }

    private fun parseJsValue(raw: String?): Any? {
        if (raw == null) return JSONObject.NULL
        return try { JSONArray("[$raw]").get(0) } catch (_: Throwable) { raw }
    }

    private fun requireParam(r: AutomationRequest, name: String): String =
        r.param(name)?.takeIf { it.isNotEmpty() } ?: throw ApiException(400, "missing \"$name\"")

    // ---- endpoints -----------------------------------------------------------------------

    private fun tabs(): AutomationResponse {
        val activity = requireActivity()
        val list: JSONArray = onMain { complete ->
            val arr = JSONArray()
            activity.tabManager.tabs.forEachIndexed { i, tab -> arr.put(tabJson(activity, tab, i)) }
            complete(arr)
        }
        return ok().put("tabs", list).json()
    }

    private fun open(context: Context, r: AutomationRequest): AutomationResponse {
        val url = requireParam(r, "url")
        val newTab = r.param("newTab")?.toBooleanStrictOrNull() ?: false
        val activity = RemoteAutomation.activity()
        if (activity == null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ok().put("launched", true).put("url", url).json()
        }
        val tab: JSONObject = onMain { complete ->
            if (newTab) activity.openNewTab(isIncognito = false, url = activity.formatUrl(url))
            else activity.loadUrl(url)
            val active = activity.tabManager.activeTab ?: throw ApiException(409, "no active tab")
            complete(tabJson(activity, active, activity.tabManager.activeIndex))
        }
        return ok().put("tab", tab).json()
    }

    private fun eval(r: AutomationRequest): AutomationResponse {
        val js = requireParam(r, "js")
        val timeout = (r.param("timeoutMs")?.toLongOrNull() ?: 15_000L).coerceIn(1_000L, 60_000L)
        val result = runJs(r.param("tabId"), js, timeout)
        return ok().put("result", result ?: JSONObject.NULL).json()
    }

    private fun click(r: AutomationRequest): AutomationResponse {
        val selector = JSONObject.quote(requireParam(r, "selector"))
        val js = """
            (function(){
              var e = document.querySelector($selector);
              if (!e) return {ok:false, error:'element not found'};
              try { e.scrollIntoView({block:'center', inline:'center'}); } catch (x) {}
              if (e.focus) e.focus();
              e.click();
              return {ok:true, tag:e.tagName, text:(e.innerText||e.value||'').slice(0,200)};
            })()
        """.trimIndent()
        return elementResult(runJs(r.param("tabId"), js))
    }

    private fun type(r: AutomationRequest): AutomationResponse {
        val selector = JSONObject.quote(requireParam(r, "selector"))
        val text = JSONObject.quote(r.param("text") ?: "")
        val submit = r.param("submit")?.toBooleanStrictOrNull() ?: false
        val js = """
            (function(){
              var e = document.querySelector($selector);
              if (!e) return {ok:false, error:'element not found'};
              if (e.focus) e.focus();
              var t = $text;
              if (e.isContentEditable) { e.textContent = t; }
              else {
                var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e), 'value');
                if (d && d.set) d.set.call(e, t); else e.value = t;
              }
              e.dispatchEvent(new Event('input', {bubbles:true}));
              e.dispatchEvent(new Event('change', {bubbles:true}));
              if ($submit) {
                var f = e.form;
                if (f) { if (f.requestSubmit) f.requestSubmit(); else f.submit(); }
                else {
                  ['keydown','keypress','keyup'].forEach(function(n){
                    e.dispatchEvent(new KeyboardEvent(n, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
                  });
                }
              }
              return {ok:true, tag:e.tagName};
            })()
        """.trimIndent()
        return elementResult(runJs(r.param("tabId"), js))
    }

    private fun elementResult(value: Any?): AutomationResponse {
        val obj = value as? JSONObject ?: return ok().put("result", value ?: JSONObject.NULL).json()
        return if (obj.optBoolean("ok")) AutomationResponse.json(obj)
        else AutomationResponse.json(obj, 404)
    }

    private fun text(r: AutomationRequest): AutomationResponse {
        val js = "(function(){return {title:document.title, url:location.href, text:(document.body?document.body.innerText:'')};})()"
        val value = runJs(r.param("tabId"), js) as? JSONObject ?: throw ApiException(409, "page did not return text")
        return ok().put("title", value.optString("title")).put("url", value.optString("url"))
            .put("text", value.optString("text")).json()
    }

    private fun screenshot(r: AutomationRequest): AutomationResponse {
        val activity = requireActivity()
        val scale = (r.param("scale")?.toFloatOrNull() ?: 1f).coerceIn(0.1f, 1f)
        val bitmap: Bitmap = onMain(15_000) { complete ->
            val wv = activity.findTab(r.param("tabId")).webView
            if (!wv.isAttachedToWindow || wv.width <= 0 || wv.height <= 0) {
                throw ApiException(409, "tab is not visible; activate it first")
            }
            val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            val location = IntArray(2)
            wv.getLocationInWindow(location)
            val rect = Rect(location[0], location[1], location[0] + wv.width, location[1] + wv.height)
            try {
                PixelCopy.request(activity.window, rect, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) complete(bmp) else complete(drawToBitmap(wv, bmp))
                }, main)
            } catch (_: Throwable) {
                complete(drawToBitmap(wv, bmp))
            }
        }
        val output = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        val stream = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return AutomationResponse.png(stream.toByteArray())
    }

    private fun drawToBitmap(wv: WebView, bmp: Bitmap): Bitmap {
        val canvas = Canvas(bmp)
        canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
        wv.draw(canvas)
        return bmp
    }

    private fun navigate(r: AutomationRequest, action: (WebView) -> Boolean): AutomationResponse {
        val activity = requireActivity()
        val result: JSONObject = onMain { complete ->
            val tab = activity.findTab(r.param("tabId"))
            val done = action(tab.webView)
            complete(JSONObject().put("ok", done).put("tabId", tab.id)
                .put("canGoBack", tab.webView.canGoBack()).put("canGoForward", tab.webView.canGoForward())
                .apply { if (!done) put("error", "no history in that direction") })
        }
        return AutomationResponse.json(result, if (result.optBoolean("ok")) 200 else 409)
    }

    private fun activate(r: AutomationRequest): AutomationResponse {
        val tabId = requireParam(r, "tabId")
        val activity = requireActivity()
        val tab: JSONObject = onMain { complete ->
            val target = activity.findTab(tabId)
            activity.focusTabById(target.id)
            complete(tabJson(activity, target, activity.tabManager.tabs.indexOf(target)))
        }
        return ok().put("tab", tab).json()
    }

    private fun close(r: AutomationRequest): AutomationResponse {
        val activity = requireActivity()
        val closedId: String = onMain { complete ->
            val tab = activity.findTab(r.param("tabId"))
            val message = activity.resources.getQuantityString(R.plurals.tab_menu_closed_tabs, 1, 1)
            activity.closeTabsWithUndo(listOf(tab.id), message)
            complete(tab.id)
        }
        return ok().put("closed", closedId).put("remaining", onMain<Int> { it(activity.tabManager.tabs.size) }).json()
    }
}
