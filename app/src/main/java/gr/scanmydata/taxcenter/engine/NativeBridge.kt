package gr.scanmydata.taxcenter.engine

import android.webkit.JavascriptInterface
import java.util.concurrent.ExecutorService

/**
 * Το μοναδικό αντικείμενο που βλέπει η JavaScript: `__bridge`.
 *
 * Όλες οι μέθοδοι καλούνται από το **νήμα της JavaScript** του WebView, όχι από
 * το UI thread. Οι σύγχρονες (αρχεία, log) εκτελούνται επιτόπου· οι ασύγχρονες
 * (HTTP, browser) φεύγουν σε executor και απαντούν αργότερα μέσω
 * [JsCallbacks.resolve] / [JsCallbacks.reject], που κάνουν post στο UI thread —
 * το `evaluateJavascript` επιτρέπεται μόνο εκεί.
 *
 * ΠΡΟΣΟΧΗ ασφάλειας: κάθε public μέθοδος με [JavascriptInterface] είναι
 * προσβάσιμη από ΟΠΟΙΟΔΗΠΟΤΕ JS τρέχει σε αυτό το WebView. Γι' αυτό ο host
 * WebView δεν πλοηγείται ποτέ σε απομακρυσμένη σελίδα — μένει σε `about:blank`
 * και εκτελεί μόνο τα δικά μας assets. Το ορατό WebView (σελίδες ΑΑΔΕ/ΕΦΚΑ)
 * είναι ΞΕΧΩΡΙΣΤΟ και δεν έχει κανένα JavascriptInterface.
 */
class NativeBridge(
    private val http: HttpBridge,
    private val files: FileBridge,
    private val assets: EngineAssets,
    private val io: ExecutorService,
    private val callbacks: JsCallbacks,
    private val logSink: (String) -> Unit,
) {

    /** Καλείται από το `runner.js` όταν τελειώσει μια διαδικασία. */
    interface JsCallbacks {
        fun resolve(callId: String, json: String?)
        fun reject(callId: String, message: String)
        fun finish(callId: String, resultJson: String)
        fun pageCall(callId: String, requestJson: String)
    }

    // ------------------------------------------------------------------ HTTP

    @JavascriptInterface
    fun httpRequest(callId: String, requestJson: String) {
        io.execute {
            try {
                callbacks.resolve(callId, http.execute(requestJson))
            } catch (e: Throwable) {
                // Ο engine περιμένει σφάλμα δικτύου ως rejection του fetch.
                callbacks.reject(callId, e.message ?: e.toString())
            }
        }
    }

    // -------------------------------------------------------------- WebView

    @JavascriptInterface
    fun pageCall(callId: String, requestJson: String) {
        callbacks.pageCall(callId, requestJson)
    }

    // --------------------------------------------------------------- αρχεία

    @JavascriptInterface
    fun fileWrite(path: String, dataB64: String, append: Boolean): String =
        files.write(path, dataB64, append)

    @JavascriptInterface
    fun fileRead(path: String): String? = files.read(path)

    @JavascriptInterface
    fun fileExists(path: String): String = files.exists(path)

    @JavascriptInterface
    fun fileSize(path: String): String = files.size(path)

    @JavascriptInterface
    fun mkdirs(path: String): String = files.mkdirs(path)

    // ----------------------------------------------------------------- λοιπά

    /**
     * Το `console.log` και το `http.log` του engine.
     *
     * Το κείμενο περνά ΠΑΝΤΑ από τον [Redactor]: ο engine λογάρει URL και
     * ενίοτε form πεδία, και ένας κωδικός TAXISnet που ξέφυγε σε log ξέφυγε
     * για πάντα.
     */
    @JavascriptInterface
    fun log(line: String): String {
        logSink(Redactor.scrub(line))
        return ""
    }

    /** Πηγαίος κώδικας module από τα assets, για το `require` του shims.js. */
    @JavascriptInterface
    fun moduleSource(name: String): String? = assets.moduleSource(name)

    @JavascriptInterface
    fun finish(callId: String, resultJson: String) {
        callbacks.finish(callId, resultJson)
    }
}
