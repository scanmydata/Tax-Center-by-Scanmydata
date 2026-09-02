package gr.scanmydata.taxcenter.engine

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Η μεταφορά HTTP για τον JS engine.
 *
 * Ο engine (`hyper-http.js`, αυτούσιος από τον runner) κρατά **δικό του** cookie
 * jar και βάζει μόνος του την κεφαλίδα `Cookie` σε κάθε αίτημα. Άρα εδώ ο OkHttp
 * δεν πρέπει να διαχειρίζεται καθόλου cookies — αλλιώς θα προσέθετε δεύτερα,
 * αντικρουόμενα. Ο σκόπιμα «χαλαρός» κανόνας του runner (cookies του gsis.gr
 * ταξιδεύουν προς e-efka.gov.gr για να δουλέψει το SSO) μένει στη JS πλευρά,
 * ακριβώς όπως στον desktop.
 *
 * Τρία πράγματα είναι load-bearing και δεν αλλάζουν:
 *
 *  1. **Κανένα auto-redirect.** Ο engine κυνηγά μόνος του τα 3xx και τα JSF
 *     partial-redirects, γιατί κρίνει `InvalidCredentials` από το ΤΕΛΙΚΟ URL.
 *  2. **Οι κεφαλίδες περνούν αυτούσιες**, ιδίως το
 *     `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`. Χωρίς το
 *     charset, ο JSF server του e-ΕΦΚΑ γυρίζει τα ελληνικά ως `?`. Γι' αυτό το
 *     σώμα χτίζεται με `toRequestBody(mediaType)` και όχι με `FormBody`, που θα
 *     έγραφε δικό του Content-Type χωρίς charset.
 *  3. **Όλα τα `Set-Cookie` επιστρέφονται ακέραια** ως λίστα.
 */
class HttpBridge(
    private val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
) {

    /** Κλήσεις που έγιναν, για διάγνωση. Ποτέ δεν κρατά σώματα ή κεφαλίδες. */
    @Volatile
    var requestCount: Int = 0
        private set

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Τα PDF της ΑΑΔΕ παράγονται on demand και αργούν.
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Εκτελεί ένα αίτημα. Το [requestJson] έχει τη μορφή που στέλνει το
     * `fetch` shim: `{url, method, headers, body, redirect}`.
     *
     * Επιστρέφει `{status, url, headers, setCookie[], bodyB64}`. Το σώμα πάει
     * πάντα ως base64: μπορεί να είναι PDF, και το μόνο που ξέρει ο engine να
     * κάνει με bytes είναι `Buffer.from(arrayBuffer)`.
     */
    fun execute(requestJson: String): String {
        val req = JSONObject(requestJson)
        val url = req.getString("url")
        val method = req.optString("method", "GET").uppercase()
        // optString(name, null) δηλώνει μη-null default, οπότε ο έλεγχος γίνεται ρητά.
        val bodyStr: String? = if (req.isNull("body")) null else req.optString("body")

        val headers = req.optJSONObject("headers") ?: JSONObject()
        var contentType: String? = null
        val builder = Request.Builder().url(url)

        for (name in headers.keys()) {
            val value = headers.optString(name) ?: continue
            if (name.equals("Content-Type", ignoreCase = true)) contentType = value
            builder.header(name, value)
        }
        if (headers.optString("User-Agent").isNullOrEmpty()) {
            builder.header("User-Agent", userAgent)
        }

        val requestBody = when {
            bodyStr != null ->
                // Το charset του Content-Type διατηρείται αυτούσιο — είναι
                // απαραίτητο για τα ελληνικά στον JSF του e-ΕΦΚΑ.
                bodyStr.toRequestBody(contentType?.toMediaTypeOrNull())
            method in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, requestBody)

        requestCount++
        client.newCall(builder.build()).execute().use { response ->
            val out = JSONObject()
            out.put("status", response.code)
            out.put("url", response.request.url.toString())

            val bytes = decoded(response)
            // Αν αποσυμπιέσαμε εμείς, οι κεφαλίδες συμπίεσης δεν περιγράφουν
            // πια το σώμα που παραδίδουμε. Φεύγουν, ώστε να μην παραπλανήσουν
            // κάποιον μελλοντικό καταναλωτή.
            val decompressed = response.header("Content-Encoding")
                ?.lowercase()
                ?.let { it.contains("gzip") || it.contains("deflate") } == true

            val flat = JSONObject()
            val setCookies = JSONArray()
            for ((name, value) in response.headers) {
                val lower = name.lowercase()
                when {
                    lower == "set-cookie" -> setCookies.put(value)
                    decompressed && (lower == "content-encoding" || lower == "content-length") -> Unit
                    // Τελευταία τιμή κερδίζει· ο engine διαβάζει μόνο
                    // content-type και location, που δεν επαναλαμβάνονται.
                    else -> flat.put(lower, value)
                }
            }
            out.put("headers", flat)
            out.put("setCookie", setCookies)
            out.put("bodyB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            return out.toString()
        }
    }

    /**
     * Το σώμα, αποσυμπιεσμένο.
     *
     * Ο OkHttp αποσυμπιέζει **μόνο** όταν έβαλε ο ίδιος το `Accept-Encoding`.
     * Αν το βάλει ο καλών, θεωρεί ότι ξέρει τι κάνει και δίνει τα bytes ωμά —
     * αφήνοντας και το `Content-Encoding` στην απάντηση. Αυτό το χρησιμοποιούμε
     * ως σήμα: κεφαλίδα παρούσα σημαίνει «δεν το άγγιξε κανείς».
     *
     * Ιστορικό, γιατί αξίζει να μείνει γραμμένο: εδώ υπήρχε interceptor που
     * πρόσθετε `Accept-Encoding: gzip, deflate` «για να μη στέλνουμε br».
     * Ακριβώς αυτό ακύρωνε τη διάφανη αποσυμπίεση. Το αποτέλεσμα δεν ήταν
     * σφάλμα δικτύου αλλά **δεδομένα-σκουπίδια**: το `amka.gr` επιστρέφει το
     * token του gzipped, το token έφτανε να ξεκινά με τα magic bytes `1f 8b`,
     * και το επόμενο αίτημα έσκαγε με «unexpected char 0x1f at 6 in
     * authorization value». Κάθε πύλη που συμπιέζει έσπαγε το ίδιο σιωπηλά.
     */
    private fun decoded(response: okhttp3.Response): ByteArray {
        val bytes = response.body?.bytes() ?: return ByteArray(0)
        val encoding = response.header("Content-Encoding")?.lowercase()?.trim() ?: return bytes
        if (bytes.isEmpty()) return bytes
        return try {
            when {
                encoding.contains("gzip") ->
                    GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                encoding.contains("deflate") ->
                    InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
                else -> bytes
            }
        } catch (e: Exception) {
            // Λάθος δηλωμένη κωδικοποίηση συμβαίνει. Τα ωμά bytes είναι
            // καλύτερα από εξαίρεση που κόβει ολόκληρη τη διαδικασία.
            bytes
        }
    }

    private companion object {
        val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
    }
}
