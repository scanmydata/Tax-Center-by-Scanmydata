package gr.scanmydata.taxcenter.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Υλοποίηση του `BrowserPage` contract πάνω σε πραγματικό WebView.
 *
 * Χρειάζεται μόνο για το `aade-enfia`: το ETAK της ΑΑΔΕ κάνει Oracle ADF loopback
 * και F5 BIG-IP client-verification, που υπολογίζονται από JavaScript της
 * σελίδας. Ένας πραγματικός browser τα ικανοποιεί φυσιολογικά — **δεν
 * πλαστογραφείται κανένα cookie**, ούτε εδώ ούτε στον desktop runner.
 *
 * Ασφάλεια: αυτό το WebView φορτώνει σελίδες τρίτων, οπότε **δεν έχει κανένα
 * `@JavascriptInterface`**. Το Kotlin παίρνει αποτελέσματα αποκλειστικά από το
 * callback του `evaluateJavascript`. Ο JS host (που έχει το `__bridge`) είναι
 * χωριστό WebView που δεν πλοηγείται ποτέ.
 *
 * Ο χρήστης βλέπει τη σελίδα όταν ο [container] είναι διαθέσιμος — απαραίτητο
 * για OTP/CAPTCHA, που **δεν παρακάμπτονται**.
 */
class WebViewBrowserPage(
    private val context: Context,
    private val assets: EngineAssets,
    private val downloadRoot: File,
    /** Πού μπαίνει το WebView για να το δει ο χρήστης. null = εκτός οθόνης. */
    private val container: (() -> ViewGroup?)? = null,
    private val logSink: (String) -> Unit = {},
) : JsHost.BrowserPageHost {

    private val main = Handler(Looper.getMainLooper())
    private val pages = HashMap<String, Page>()
    private val seq = AtomicInteger(0)

    private val http = OkHttpClient.Builder()
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Ό,τι δίνει το DownloadListener· η πραγματική λήψη γίνεται με OkHttp. */
    private class PendingDownload(val url: String, val userAgent: String?, val suggested: String)

    private class Page(val web: WebView) {
        var popupHandle: String? = null
        var pendingDownload: PendingDownload? = null
        var downloadDest: File? = null
    }

    override fun handle(callId: String, requestJson: String, callbacks: NativeBridge.JsCallbacks) {
        main.post {
            try {
                dispatch(callId, JSONObject(requestJson), callbacks)
            } catch (e: Throwable) {
                callbacks.reject(callId, e.message ?: e.toString())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun dispatch(callId: String, req: JSONObject, cb: NativeBridge.JsCallbacks) {
        val op = req.getString("op")
        if (op == "open") {
            val handle = "w" + seq.incrementAndGet()
            pages[handle] = Page(newWebView())
            attach(handle)
            cb.resolve(callId, JSONObject().put("handle", handle).toString())
            return
        }
        if (op == "renderPdf") {
            // Ο μόνος καταναλωτής (aade-debts, 2η σελίδα δόσεων) ελέγχει το ok
            // και υποβαθμίζεται μόνος του.
            cb.resolve(callId, JSONObject().put("ok", false)
                .put("reason", "HTML->PDF δεν υποστηρίζεται σε αυτή την έκδοση").toString())
            return
        }

        val handle = req.optString("handle")
        val page = pages[handle] ?: run {
            cb.reject(callId, "άγνωστο page handle: $handle")
            return
        }

        when (op) {
            "close" -> {
                destroy(handle)
                cb.resolve(callId, "{}")
            }

            "goto" -> {
                val url = req.getString("url")
                logSink("[browser] goto $url")
                navigate(page, url, req.optLong("timeout", 90_000)) { err ->
                    if (err != null) cb.reject(callId, err)
                    else cb.resolve(callId, JSONObject().put("url", page.web.url ?: url).toString())
                }
            }

            // Το WebView δεν έχει 'networkidle'. Περιμένουμε «ησυχία»: readyState
            // complete ΚΑΙ κανένα ενεργό XHR/fetch (τα μετράει το page-helper).
            "waitLoad" -> awaitQuiet(page, req.optLong("timeout", 30_000)) {
                cb.resolve(callId, "{}")
            }

            "sleep" -> main.postDelayed({ cb.resolve(callId, "{}") }, req.optLong("ms", 0))

            "url" -> cb.resolve(callId, value(page.web.url ?: ""))
            "title" -> evalPage(page, "__page.title()", callId, cb)
            "content" -> evalPage(page, "__page.content()", callId, cb)
            "count" -> evalPage(page, "__page.count(${jsStr(req.getString("sel"))})", callId, cb)
            "text" -> evalPage(page, "__page.text(${jsStr(req.getString("sel"))})", callId, cb)
            "attr" -> evalPage(
                page,
                "__page.attr(${jsStr(req.getString("sel"))},${jsStr(req.getString("name"))})",
                callId, cb,
            )
            "options" -> evalPage(page, "__page.options(${jsStr(req.getString("sel"))})", callId, cb)
            "fill" -> evalPage(
                page,
                "__page.fill(${jsStr(req.getString("sel"))},${jsStr(req.getString("value"))})",
                callId, cb,
            )
            "click" -> evalPage(page, "__page.click(${jsStr(req.getString("sel"))})", callId, cb)
            "selectByValue" -> evalPage(
                page,
                "__page.selectByValue(${jsStr(req.getString("sel"))},${jsStr(req.getString("value"))})",
                callId, cb,
            )
            "selectByLabel" -> evalPage(
                page,
                "__page.selectByLabel(${jsStr(req.getString("sel"))},${jsStr(req.getString("label"))})",
                callId, cb,
            )

            "clickNav" -> {
                val before = page.web.url
                evalRaw(page, "__page.click(${jsStr(req.getString("sel"))})") {
                    // Δεν περιμένουμε σίγουρη πλοήγηση: κάποια ADF κουμπιά κάνουν
                    // μόνο AJAX. Περιμένουμε ησυχία, όπως και ο desktop.
                    awaitQuiet(page, req.optLong("timeout", 30_000)) {
                        logSink("[browser] clickNav ${before} -> ${page.web.url}")
                        cb.resolve(callId, "{}")
                    }
                }
            }

            "evaluate" -> {
                val fn = req.getString("fn")
                val arg = if (req.isNull("arg")) "null" else JSONObject.quote(req.optString("arg"))
                evalPage(page, "($fn)(JSON.parse($arg))", callId, cb)
            }

            "armPopup" -> {
                page.popupHandle = null
                page.pendingPopup = null
                cb.resolve(callId, "{}")
            }

            "awaitPopup" -> waitFor(req.optLong("timeout", 60_000), { page.popupHandle != null }) { ok ->
                if (ok) cb.resolve(callId, JSONObject().put("handle", page.popupHandle).toString())
                else cb.reject(callId, "δεν άνοιξε popup μέσα στον χρόνο")
            }

            "armDownload" -> {
                page.downloadDest = File(req.getString("dest"))
                page.pendingDownload = null
                cb.resolve(callId, "{}")
            }

            "awaitDownload" -> waitFor(req.optLong("timeout", 120_000), { page.pendingDownload != null }) { ok ->
                val pending = page.pendingDownload
                val dest = page.downloadDest
                page.pendingDownload = null
                if (!ok || pending == null || dest == null) {
                    cb.reject(
                        callId,
                        "δεν ξεκίνησε λήψη μέσα στον χρόνο. Κάποια κουμπιά ADF στέλνουν POST " +
                            "και το DownloadListener δεν ενεργοποιείται πάντα.",
                    )
                } else {
                    // Η λήψη γίνεται εκτός main thread· η απάντηση επιστρέφει σε αυτό.
                    Thread {
                        val err = fetchToFile(pending.url, pending.userAgent, dest)
                        main.post {
                            if (err != null) {
                                cb.reject(callId, err)
                            } else {
                                cb.resolve(callId, JSONObject().put("filename", pending.suggested).toString())
                            }
                        }
                    }.start()
                }
            }

            else -> cb.reject(callId, "άγνωστη ενέργεια: $op")
        }
    }

    // ------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(assets.pageHelper, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false // όλα μέσα στο ίδιο WebView
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val handle = "w" + seq.incrementAndGet()
                val child = newWebView()
                pages[handle] = Page(child)
                attach(handle)
                // Το popup αποδίδεται στη σελίδα που το άνοιξε.
                pages.values.firstOrNull { it.web === view }?.popupHandle = handle
                logSink("[browser] popup -> $handle")
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val owner = pages.values.firstOrNull { it.web === web } ?: return@setDownloadListener
            val dest = owner.downloadDest
            if (dest == null) {
                logSink("[browser] αγνοήθηκε λήψη χωρίς armDownload: $url")
                return@setDownloadListener
            }
            val suggested = guessName(url, contentDisposition)
            owner.pendingDownload = PendingDownload(url, userAgent, suggested)
            logSink("[browser] λήψη: $suggested ($mimeType) -> ${dest.name}")
        }
        return web
    }

    private fun attach(handle: String) {
        val holder = container?.invoke() ?: return
        val web = pages[handle]?.web ?: return
        holder.removeAllViews()
        holder.addView(
            web,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun destroy(handle: String) {
        val page = pages.remove(handle) ?: return
        (page.web.parent as? ViewGroup)?.removeView(page.web)
        page.web.stopLoading()
        page.web.loadUrl("about:blank")
        page.web.destroy()
    }

    /** Κλείνει ό,τι έμεινε ανοιχτό — καλείται όταν τελειώσει η διαδικασία. */
    fun shutdown() {
        main.post { pages.keys.toList().forEach(::destroy) }
    }

    // ------------------------------------------------------------ βοηθητικά

    private fun navigate(page: Page, url: String, timeout: Long, done: (String?) -> Unit) {
        var finished = false
        val client = page.web.webViewClient
        page.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                view.evaluateJavascript(assets.pageHelper, null)
                if (!finished) {
                    finished = true
                    page.web.webViewClient = client
                    done(null)
                }
            }
        }
        page.web.loadUrl(url)
        main.postDelayed({
            if (!finished) {
                finished = true
                page.web.webViewClient = client
                // Δεν είναι σφάλμα: κάποιες σελίδες κρατούν ανοιχτά αιτήματα.
                done(null)
            }
        }, timeout)
    }

    private fun awaitQuiet(page: Page, timeout: Long, done: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeout
        var quietSince = 0L
        fun probe() {
            page.web.evaluateJavascript("(function(){try{return !!(window.__page&&__page.quiet());}catch(e){return false;}})()") { v ->
                val quiet = v == "true"
                val now = System.currentTimeMillis()
                if (quiet) {
                    if (quietSince == 0L) quietSince = now
                    // Μικρή περίοδος ησυχίας, ώστε να μη νομίσουμε ότι τελείωσε
                    // ανάμεσα σε δύο διαδοχικά AJAX του PrimeFaces.
                    if (now - quietSince >= QUIET_PERIOD_MS) { done(); return@evaluateJavascript }
                } else {
                    quietSince = 0L
                }
                if (now >= deadline) done() else main.postDelayed(::probe, POLL_MS)
            }
        }
        probe()
    }

    private fun waitFor(timeout: Long, cond: () -> Boolean, done: (Boolean) -> Unit) {
        val deadline = System.currentTimeMillis() + timeout
        fun probe() {
            if (cond()) { done(true); return }
            if (System.currentTimeMillis() >= deadline) { done(false); return }
            main.postDelayed(::probe, POLL_MS)
        }
        probe()
    }

    /** Τρέχει έκφραση στη σελίδα και απαντά στον JS host. */
    private fun evalPage(page: Page, expr: String, callId: String, cb: NativeBridge.JsCallbacks) {
        evalRaw(page, expr) { json ->
            val o = runCatching { JSONObject(json) }.getOrNull()
            if (o == null) {
                cb.reject(callId, "μη αναγνώσιμη απάντηση σελίδας")
            } else if (o.optBoolean("ok")) {
                cb.resolve(callId, JSONObject().put("value", o.opt("value")).toString())
            } else {
                cb.reject(callId, o.optString("error", "σφάλμα σελίδας"))
            }
        }
    }

    private fun evalRaw(page: Page, expr: String, done: (String) -> Unit) {
        // Το αποτέλεσμα γυρίζει ως JSON του αντικειμένου — γι' αυτό επιστρέφουμε
        // object και όχι string, ώστε να μη χρειάζεται διπλό JSON.parse.
        val wrapped =
            "(function(){try{return {ok:true,value:($expr)};}" +
                "catch(e){return {ok:false,error:String(e&&e.message?e.message:e)};}})()"
        page.web.evaluateJavascript(wrapped) { done(it ?: "null") }
    }

    /**
     * Κατεβάζει το αρχείο με OkHttp, περνώντας τα cookies του WebView.
     *
     * Το `DownloadListener` δίνει μόνο URL — η συνεδρία ζει στο `CookieManager`.
     */
    private fun fetchToFile(url: String, userAgent: String?, dest: File): String? = try {
        val cookies = CookieManager.getInstance().getCookie(url)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent ?: UA)
            .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                "λήψη απέτυχε: HTTP ${res.code}"
            } else {
                dest.parentFile?.mkdirs()
                res.body?.byteStream()?.use { input -> dest.outputStream().use(input::copyTo) }
                null
            }
        }
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    private fun guessName(url: String, contentDisposition: String?): String {
        Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentDisposition.orEmpty())
            ?.groupValues?.get(1)
            ?.let { return java.net.URLDecoder.decode(it, "UTF-8") }
        return url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
    }

    private fun value(v: Any?): String = JSONObject().put("value", v).toString()

    private fun jsStr(s: String): String = JSONObject.quote(s)

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val POLL_MS = 150L
        const val QUIET_PERIOD_MS = 400L
    }
}
