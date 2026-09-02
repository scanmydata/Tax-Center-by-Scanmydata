package gr.scanmydata.taxcenter.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import gr.scanmydata.taxcenter.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ενημέρωση από GitHub Releases.
 *
 * Η εφαρμογή διανέμεται με sideload, όχι από το Play Store — ένα λογιστικό
 * γραφείο δεν χρειάζεται κατάστημα, και το Play Store θα απαιτούσε δημοσίευση
 * εφαρμογής που χειρίζεται κωδικούς τρίτων.
 *
 * Ο έλεγχος είναι **χειροκίνητος**, από τις Ρυθμίσεις. Δεν τρέχει στο
 * παρασκήνιο και δεν στέλνει τίποτα: ένα ανώνυμο GET στο δημόσιο API του
 * GitHub, χωρίς κανένα στοιχείο της συσκευής ή των πελατών.
 */
object UpdateChecker {

    private const val LATEST =
        "https://api.github.com/repos/scanmydata/ScanMyData-Tax-Center/releases/latest"

    data class Release(val tag: String, val apkUrl: String, val notes: String) {
        /** Η έκδοση χωρίς το `v` του tag, για σύγκριση. */
        val version: String get() = tag.removePrefix("v")
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Επιστρέφει την τελευταία έκδοση, ή `null` αν δεν υπάρχει APK στο release. */
    fun latest(): Release? {
        val request = Request.Builder()
            .url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        tag = tag,
                        apkUrl = asset.optString("browser_download_url"),
                        notes = json.optString("body"),
                    )
                }
            }
            return null
        }
    }

    /**
     * Σύγκριση εκδόσεων κατά τμήμα, όχι αλφαβητικά.
     *
     * Το `0.1.9` < `0.1.10` είναι σωστό αριθμητικά και λάθος αλφαβητικά, και το
     * versionName εδώ είναι `0.1.<run_number>` — δηλαδή θα περάσει από το 9 στο
     * 10 μέσα σε μια μέρα.
     */
    fun isNewer(candidate: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val a = candidate.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Κατεβάζει το APK στην cache και επιστρέφει το αρχείο. */
    fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Ένα αρχείο τη φορά: δεν υπάρχει λόγος να μαζεύονται APK στην cache.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "TaxCenter-${release.version}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("κενή απάντηση")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        return target
    }

    /**
     * Ανοίγει τον εγκαταστάτη του συστήματος.
     *
     * Η εφαρμογή **δεν** εγκαθιστά μόνη της· ο χρήστης βλέπει τον διάλογο του
     * Android και εγκρίνει. Το `REQUEST_INSTALL_PACKAGES` δίνει μόνο το δικαίωμα
     * να ζητηθεί.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
