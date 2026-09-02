package gr.scanmydata.taxcenter.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Τρέχει μια διαδικασία του runner on-device.
 *
 * Ο host είναι ένα **κρυφό WebView που δεν πλοηγείται ποτέ**: μένει σε
 * `about:blank` και εκτελεί μόνο τα δικά μας assets. Χρειάζεται ξεχωριστό
 * WebView από αυτό που βλέπει ο χρήστης, γιατί το `page.goto()` ενός config
 * καταστρέφει το JS context της σελίδας — αν έτρεχε εκεί το config, θα
 * αυτοκτονούσε στο πρώτο βήμα.
 *
 * Δεν υπάρχει `@JavascriptInterface` στο ορατό WebView· μόνο εδώ.
 */
class JsHost(
    private val context: Context,
    private val assets: EngineAssets = EngineAssets(context),
    private val browserHost: BrowserPageHost? = null,
) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)

    /** Το αποτέλεσμα μιας εκτέλεσης, όπως το επιστρέφει το `runner.js`. */
    data class RunResult(
        val ok: Boolean,
        val reason: String,
        val files: List<String>,
        val out: String?,
        val durationMs: Long,
        val log: List<String>,
    )

    /**
     * Εκτελεί το config [configId] με τα [inputs] και γράφει τα αρχεία στο [outDir].
     *
     * Δεν πετάει για αποτυχία της διαδικασίας — επιστρέφει `ok=false` με λόγο,
     * ακριβώς όπως ο desktop runner, ώστε μια μαζική λήψη να συνεχίζει στον
     * επόμενο πελάτη.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(
        configId: String,
        inputs: Map<String, String>,
        outDir: File,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RunResult {
        val logLines = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = CompletableDeferred<String>()
        val files = FileBridge(outDir)

        val callbacks = object : NativeBridge.JsCallbacks {
            override fun resolve(callId: String, json: String?) = post {
                evaluate(it, "__resolve", callId, json ?: "null")
            }

            override fun reject(callId: String, message: String) = post {
                evaluate(it, "__reject", callId, message)
            }

            override fun finish(callId: String, resultJson: String) {
                done.complete(resultJson)
            }

            override fun pageCall(callId: String, requestJson: String) {
                val host = browserHost
                if (host == null) {
                    reject(
                        callId,
                        "Η διαδικασία «$configId» χρειάζεται πραγματικό browser, " +
                            "που δεν είναι διαθέσιμος σε αυτή την εκτέλεση.",
                    )
                } else {
                    host.handle(callId, requestJson, this)
                }
            }
        }

        val bridge = NativeBridge(
            http = HttpBridge(),
            files = files,
            assets = assets,
            io = io,
            callbacks = callbacks,
            logSink = { logLines.add(it) },
        )

        return withContext(Dispatchers.Main) {
            val web = WebView(context)
            webView = web
            try {
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    // Ο host δεν φορτώνει ποτέ απομακρυσμένο περιεχόμενο.
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                web.addJavascriptInterface(bridge, "__bridge")
                web.loadDataWithBaseURL(null, "<html><head></head><body></body></html>", "text/html", "utf-8", null)

                // Το loadDataWithBaseURL είναι ασύγχρονο· περιμένουμε να στηθεί
                // το document πριν εγχύσουμε κώδικα.
                awaitReady(web)

                evaluateRaw(web, assets.shims)
                evaluateRaw(web, assets.runner)

                val payload = JSONObject().apply {
                    inputs.forEach { (k, v) -> put(k, v) }
                }.toString()

                // Η JS πλευρά δουλεύει σε σχετικές διαδρομές: το FileBridge έχει
                // ήδη ρίζα το outDir. Αν περνούσαμε απόλυτη διαδρομή, το
                // FileBridge.resolve θα την ξανα-ρίζωνε κάτω από το outDir και τα
                // αρχεία θα κατέληγαν σε outDir/data/user/0/…
                evaluate(web, "__runConfig", "run", configId, payload, ".")

                val json = try {
                    withTimeout(timeoutMs) { done.await() }
                } catch (e: TimeoutCancellationException) {
                    JSONObject()
                        .put("ok", false)
                        .put("reason", "Λήξη χρόνου μετά από ${timeoutMs / 1000}s")
                        .toString()
                }
                parse(json, logLines)
            } finally {
                webView = null
                web.removeJavascriptInterface("__bridge")
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
    }

    private var webView: WebView? = null

    private fun post(block: (WebView) -> Unit) {
        main.post { webView?.let(block) }
    }

    /** Καλεί μια global JS συνάρτηση με ασφαλώς quoted string ορίσματα. */
    private fun evaluate(web: WebView, fn: String, vararg args: String) {
        val quoted = args.joinToString(",") { JSONObject.quote(it) }
        web.evaluateJavascript("$fn($quoted);", null)
    }

    private fun evaluateRaw(web: WebView, source: String) {
        web.evaluateJavascript(source, null)
    }

    private suspend fun awaitReady(web: WebView) {
        val ready = CompletableDeferred<Boolean>()
        fun probe(attempt: Int) {
            web.evaluateJavascript("document.readyState") { value ->
                if (value != null && value.contains("complete") || attempt > 60) {
                    ready.complete(true)
                } else {
                    main.postDelayed({ probe(attempt + 1) }, 25)
                }
            }
        }
        probe(0)
        ready.await()
    }

    private fun parse(json: String, logLines: List<String>): RunResult = try {
        val o = JSONObject(json)
        val arr: JSONArray = o.optJSONArray("files") ?: JSONArray()
        RunResult(
            ok = o.optBoolean("ok"),
            reason = o.optString("reason"),
            files = (0 until arr.length()).map { arr.getString(it) },
            out = o.optJSONObject("out")?.toString(),
            durationMs = o.optLong("durationMs"),
            log = logLines.toList(),
        )
    } catch (e: Exception) {
        RunResult(false, "Μη αναγνώσιμο αποτέλεσμα: ${e.message}", emptyList(), null, 0, logLines.toList())
    }

    fun shutdown() {
        io.shutdown()
    }

    /** Χειριστής των `page.*` κλήσεων — υλοποιείται από το WebViewBrowserPage. */
    interface BrowserPageHost {
        fun handle(callId: String, requestJson: String, callbacks: NativeBridge.JsCallbacks)
    }

    companion object {
        /** Μερικές διαδικασίες κατεβάζουν δεκάδες PDF (π.χ. aade-general-forms). */
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
