package gr.scanmydata.taxcenter.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
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
 * **Κρυφό εξ ορισμού.** Ο χρήστης δεν έχει λόγο να βλέπει μια σελίδα που
 * χειρίζεται μόνη της· βλέπει το αποτέλεσμα. Το WebView μπαίνει στην οθόνη
 * **μόνο** όταν η σελίδα ζητά κάτι που πρέπει να κάνει άνθρωπος — κωδικό μιας
 * χρήσης ή CAPTCHA — ή όταν το ζητήσει ρητά ο χρήστης.
 *
 * Αυτό δεν είναι παράκαμψη: το αντίθετο. Ο κώδικας **αναγνωρίζει** ότι
 * χρειάζεται άνθρωπος και τον φέρνει μπροστά. Ό,τι δεν αναγνωριστεί καταλήγει
 * σε timeout, που είναι ορατή αποτυχία — όχι σιωπηλή απόπειρα παράκαμψης.
 */
class WebViewBrowserPage(
    private val context: Context,
    private val assets: EngineAssets,
    /**
     * Ο φάκελος της τρέχουσας εκτέλεσης. Λαμβάνεται ως συνάρτηση επειδή μία
     * παρτίδα λήψης αλλάζει φάκελο σε κάθε πελάτη, ενώ το BrowserPage host ζει
     * όσο η παρτίδα.
     */
    private val downloadRoot: () -> File,
    /** Πού μπαίνει το WebView για να το δει ο χρήστης. null = εκτός οθόνης. */
    private val container: (() -> ViewGroup?)? = null,
    private val logSink: (String) -> Unit = {},
    /** Ειδοποιεί ότι η σελίδα χρειάζεται τον χρήστη (OTP/CAPTCHA). */
    private val onNeedsUser: (Boolean) -> Unit = {},
) : JsHost.BrowserPageHost {

    /**
     * Είναι το WebView στην οθόνη;
     *
     * Ξεκινά **κλειστό**. Ανοίγει μόνο από τον ανιχνευτή OTP/CAPTCHA ή από το
     * [reveal] που πατά ο χρήστης, και μένει ανοιχτό μέχρι το τέλος της
     * διαδικασίας: αν χρειάστηκε άνθρωπος μία φορά, θα ξαναχρειαστεί.
     */
    @Volatile
    private var userVisible = false

    /** Ο χρήστης ζήτησε να δει τη σελίδα. */
    fun reveal() {
        main.post {
            userVisible = true
            pages.keys.lastOrNull()?.let(::attach)
            onNeedsUser(true)
        }
    }

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

            // Καθαρίζουμε ό,τι έμεινε από προηγούμενο popup, ώστε το awaitPopup
            // να μη δει παλιό handle και επιστρέψει αμέσως λάθος σελίδα.
            "armPopup" -> {
                page.popupHandle = null
                cb.resolve(callId, "{}")
            }

            "awaitPopup" -> waitFor(req.optLong("timeout", 60_000), { page.popupHandle != null }) { ok ->
                if (ok) cb.resolve(callId, JSONObject().put("handle", page.popupHandle).toString())
                else cb.reject(callId, "δεν άνοιξε popup μέσα στον χρόνο")
            }

            "armDownload" -> {
                page.downloadDest = resolveDest(req.getString("dest"))
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
                    // Ο Referer διαβάζεται εδώ, όσο είμαστε στο main thread.
                    val referer = page.web.url
                    // **Πρώτα μέσα από τη σελίδα.**
                    //
                    // Το ETAK απαντά με σελίδα ADF loopback: ένα HTML που θέτει
                    // βραχύβιο cookie (30 δευτ.) με JavaScript και ξαναζητά το
                    // ίδιο URL. Ο πραγματικός browser το εκτελεί και δεν το
                    // βλέπει καν ο χρήστης· ένα σκέτο GET —είτε από OkHttp είτε
                    // από `fetch`— παίρνει πίσω το loopback και τέλος. Γι' αυτό
                    // κατέβαιναν 8 KB HTML αντί για PDF.
                    fetchInPage(page, pending.url) { bytes, error ->
                        if (bytes != null && acceptable(dest, bytes)) {
                            finishDownload(callId, cb, dest, bytes, pending.suggested)
                        } else {
                            // Εφεδρεία: μερικές πύλες δίνουν απλό σύνδεσμο που
                            // δουλεύει και εκτός σελίδας.
                            logSink("[browser] η λήψη μέσα από τη σελίδα δεν έδωσε PDF — δοκιμή με OkHttp")
                            Thread {
                                val direct = fetchBytes(pending.url, pending.userAgent, referer)
                                main.post {
                                    if (direct != null && acceptable(dest, direct)) {
                                        finishDownload(callId, cb, dest, direct, pending.suggested)
                                    } else {
                                        val detail = buildString {
                                            append("η πύλη δεν επέστρεψε αρχείο PDF")
                                            error?.let { append(" · σελίδα: ").append(it) }
                                            if (error == null) {
                                                append(" · σελίδα: ").append(describeBody(bytes))
                                            }
                                            append(" · άμεσα: ").append(describeBody(direct))
                                        }
                                        logSink("[browser] ✗ $detail")
                                        cb.reject(callId, detail)
                                    }
                                }
                            }.start()
                        }
                    }
                }
            }

            else -> cb.reject(callId, "άγνωστη ενέργεια: $op")
        }
    }

    /**
     * Το config δίνει τη διαδρομή προορισμού όπως θα την έδινε στον desktop
     * runner: **σχετική**, γιατί εκεί το `http.dlDir` είναι ο φάκελος της
     * εκτέλεσης. Ένα σκέτο `File(dest)` θα την έλυνε ως προς το CWD της
     * διεργασίας — δηλαδή `/`, όπου η εφαρμογή δεν έχει δικαίωμα εγγραφής, και
     * το PDF θα χανόταν σιωπηλά.
     *
     * Ίδιοι κανόνες με το [FileBridge]: κάθε τμήμα καθαρίζεται και το
     * αποτέλεσμα πρέπει να μένει κάτω από τη ρίζα.
     */
    private fun resolveDest(dest: String): File {
        val root = downloadRoot().apply { mkdirs() }
        val target = dest.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .map(FileBridge::sanitiseSegment)
            .fold(root) { acc, seg -> File(acc, seg) }
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw SecurityException("διαδρομή λήψης εκτός ρίζας: $dest")
        }
        return target
    }

    // ------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
                // Χρειάζεται άνθρωπος; Τότε — και μόνο τότε — βγαίνει στην οθόνη.
                view.evaluateJavascript(NEEDS_USER_JS) { answer ->
                    if (answer == "true" && !userVisible) {
                        logSink("[browser] η σελίδα ζητά τον χρήστη — εμφάνιση")
                        reveal()
                    }
                }
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
        if (!userVisible) return
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
    /**
     * Τι ήρθε αντί για PDF — για το αρχείο διαγνωστικών.
     *
     * Χωρίς αυτό, η αποτυχία λέει μόνο «δεν επέστρεψε PDF» και δεν υπάρχει
     * τρόπος να ξεχωρίσεις σελίδα σύνδεσης από σφάλμα εφαρμογής από άδειο
     * αποτέλεσμα. Κόβεται στους 200 χαρακτήρες και περνά από τον [Redactor].
     */
    private fun describeBody(bytes: ByteArray?): String {
        if (bytes == null) return "καμία απάντηση"
        if (bytes.isEmpty()) return "άδεια απάντηση"
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .take(200)
        return "${bytes.size} bytes · " + Redactor.scrub(head)
    }

    /** Κατεβάζει με OkHttp, με τα cookies **και** τον Referer της σελίδας. */
    private fun fetchBytes(url: String, userAgent: String?, referer: String?): ByteArray? = try {
        val cookies = CookieManager.getInstance().getCookie(url)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent ?: UA)
            .header("Accept", "application/pdf,application/octet-stream,*/*")
            .apply {
                if (!cookies.isNullOrBlank()) header("Cookie", cookies)
                if (!referer.isNullOrBlank() && referer != "about:blank") header("Referer", referer)
            }
            .build()
        http.newCall(req).execute().use { res ->
            if (res.isSuccessful) res.body?.bytes() else null
        }
    } catch (e: Exception) {
        logSink("[browser] άμεση λήψη απέτυχε: ${e.message}")
        null
    }

    /**
     * Είναι αυτό που ζητήσαμε, ή σελίδα σφάλματος με κατάληξη .pdf;
     *
     * Ο engine το ελέγχει ήδη παντού αλλού με τα magic bytes `%PDF` — πολλά
     * endpoints της ΑΑΔΕ στέλνουν `application/octet-stream` και το
     * Content-Type δεν λέει τίποτα. Στη διαδρομή του WebView ο έλεγχος έλειπε,
     * και γι' αυτό κατέληγαν στη συσκευή αρχεία 8 KB που άνοιγαν σε λευκό.
     */
    private fun acceptable(dest: File, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (!dest.name.endsWith(".pdf", ignoreCase = true)) return true
        if (bytes.size < 5) return false
        return String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF"
    }

    private fun finishDownload(
        callId: String,
        cb: NativeBridge.JsCallbacks,
        dest: File,
        bytes: ByteArray,
        suggested: String,
    ) {
        try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            logSink("[browser] ✅ ${dest.name} (${bytes.size} b)")
            cb.resolve(callId, JSONObject().put("filename", suggested).toString())
        } catch (e: Exception) {
            cb.reject(callId, "δεν γράφτηκε το αρχείο: ${e.message}")
        }
    }

    /**
     * Δεύτερη προσπάθεια **μέσα από τη σελίδα**.
     *
     * Το `fetch` της ίδιας της σελίδας μοιράζεται συνεδρία, cookies και origin
     * με το ADF, οπότε πετυχαίνει εκεί που ένα εξωτερικό αίτημα γυρίζει
     * ανακατεύθυνση σε σελίδα σφάλματος.
     *
     * Το αποτέλεσμα αφήνεται σε `window` και διαβάζεται με polling: αυτό το
     * WebView φορτώνει σελίδες τρίτων και **δεν έχει κανένα
     * `@JavascriptInterface`** — ο κανόνας δεν χαλαρώνει για μια ευκολία.
     */
    private fun fetchInPage(page: Page, url: String, done: (ByteArray?, String?) -> Unit) {
        page.web.evaluateJavascript(loopbackAwareFetch(url), null)

        val deadline = System.currentTimeMillis() + IN_PAGE_TIMEOUT_MS
        fun probe() {
            page.web.evaluateJavascript("window.__smdDl") { raw ->
                val value = if (raw == null || raw == "null") null else {
                    runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
                }
                when {
                    value == null ->
                        if (System.currentTimeMillis() >= deadline) {
                            done(null, "λήξη χρόνου")
                        } else {
                            main.postDelayed(::probe, POLL_MS)
                        }
                    value.startsWith("ERR:") -> done(null, value.removePrefix("ERR:"))
                    else -> done(
                        runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull(),
                        null,
                    )
                }
            }
        }
        main.postDelayed(::probe, POLL_MS)
    }

    private fun guessName(url: String, contentDisposition: String?): String {
        Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentDisposition.orEmpty())
            ?.groupValues?.get(1)
            ?.let { return java.net.URLDecoder.decode(it, "UTF-8") }
        return url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
    }

    /**
     * Κατέβασμα μέσα από τη σελίδα, που **ακολουθεί το ADF loopback**.
     *
     * Το Oracle ADF δεν σερβίρει το αρχείο στο πρώτο αίτημα. Απαντά με ένα
     * μικρό HTML (`AdfLoopbackUtils`) που θέτει με JavaScript ένα cookie
     * τριάντα δευτερολέπτων και ξαναζητά το ίδιο URL. Ο πραγματικός browser το
     * κάνει αυτόματα και ο χρήστης δεν το βλέπει ποτέ· ένα σκέτο GET όμως
     * σταματά εκεί — και ακριβώς αυτές τις 8 KB HTML σώζαμε ως «PDF».
     *
     * Εδώ το loopback **εκτελείται** αντί να αναλυθεί: τα scripts της
     * απάντησης τρέχουν όπως θα έτρεχαν στη σελίδα, με το `document.cookie` να
     * είναι το πραγματικό (ίδιο origin), και μόνο η `location` σκιάζεται ώστε
     * η τελική ανακατεύθυνση να μην παρασύρει τη σελίδα μας. Μετά ξαναζητιέται
     * το αρχείο, έως τρεις φορές.
     *
     * Δεν πλαστογραφείται τίποτα: το cookie το παράγει ο ίδιος ο κώδικας της
     * ΑΑΔΕ, μέσα στη δική της σελίδα.
     */
    private fun loopbackAwareFetch(url: String): String = """
        (function () {
          window.__smdDl = null;
          var target = ${jsStr(url)};
          var hops = 0;

          function looksLoopback(text) {
            return text.indexOf('AdfLoopbackUtils') >= 0 ||
                   (text.indexOf('_afrLoop') >= 0 && text.indexOf('<html') >= 0);
          }

          function runLoopback(html) {
            var re = /<script[^>]*>([\s\S]*?)<\/script>/gi, m, code = '';
            while ((m = re.exec(html)) !== null) code += m[1] + '\n';
            if (!code) return false;
            var a = document.createElement('a');
            a.href = target;
            var noop = function () {};
            var inert = {
              replace: noop, assign: noop, reload: noop,
              href: a.href, search: a.search, hash: a.hash,
              pathname: a.pathname, host: a.host, hostname: a.hostname,
              protocol: a.protocol, port: a.port, origin: a.origin
            };
            try {
              new Function('location', 'window', 'self', 'top', 'parent', code)(
                inert, { location: inert }, { location: inert },
                { location: inert }, { location: inert });
            } catch (e) {
              // Τα loopback scripts τελειώνουν με ανακατεύθυνση που εδώ δεν
              // γίνεται· το cookie έχει ήδη τεθεί πριν πεταχτεί το σφάλμα.
            }
            return true;
          }

          function attempt() {
            hops++;
            fetch(target, { credentials: 'include', redirect: 'follow' })
              .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.arrayBuffer();
              })
              .then(function (buf) {
                var b = new Uint8Array(buf);
                var pdf = b.length > 4 && b[0] === 0x25 && b[1] === 0x50 &&
                          b[2] === 0x44 && b[3] === 0x46;
                if (!pdf && hops < 4) {
                  var text = '';
                  try { text = new TextDecoder('utf-8').decode(b); } catch (e) { text = ''; }
                  if (looksLoopback(text) && runLoopback(text)) {
                    setTimeout(attempt, 150);
                    return;
                  }
                }
                var s = '', CH = 0x8000;
                for (var i = 0; i < b.length; i += CH) {
                  s += String.fromCharCode.apply(null, b.subarray(i, i + CH));
                }
                window.__smdDl = btoa(s);
              })
              .catch(function (e) {
                window.__smdDl = 'ERR:' + (e && e.message ? e.message : e);
              });
          }

          attempt();
        })();
    """.trimIndent()

    private fun value(v: Any?): String = JSONObject().put("value", v).toString()

    private fun jsStr(s: String): String = JSONObject.quote(s)

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val POLL_MS = 150L
        const val QUIET_PERIOD_MS = 400L
        const val IN_PAGE_TIMEOUT_MS = 90_000L

        /**
         * Χρειάζεται άνθρωπος αυτή η σελίδα;
         *
         * Ψάχνει CAPTCHA και πεδία κωδικού μιας χρήσης. Ένα ψευδώς θετικό
         * απλώς δείχνει τη σελίδα — ακίνδυνο. Ένα ψευδώς αρνητικό καταλήγει σε
         * timeout, που φαίνεται. Η ασυμμετρία είναι σκόπιμη.
         */
        const val NEEDS_USER_JS = """
            (function () {
              try {
                if (document.querySelector(
                  'iframe[src*="recaptcha"], iframe[src*="hcaptcha"], .g-recaptcha, #captcha, [id*="captcha" i]'
                )) return true;
                if (document.querySelector(
                  'input[autocomplete="one-time-code"], input[name*="otp" i], input[id*="otp" i]'
                )) return true;
                var t = document.body ? (document.body.innerText || '') : '';
                return /κωδικ[όο]ς μιας χρ[ήη]σης|μιας χρ[ήη]σης|one[- ]time code/i.test(t);
              } catch (e) { return false; }
            })()
        """
    }
}
