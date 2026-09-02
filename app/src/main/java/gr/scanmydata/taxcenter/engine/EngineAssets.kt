package gr.scanmydata.taxcenter.engine

import android.content.Context
import org.json.JSONObject

/**
 * Διαβάζει τον engine από τα assets.
 *
 * Διάταξη (βλ. `tools/vendor-engine.mjs`):
 * ```
 * assets/engine/
 *   shims.js         γέφυρα Node -> Android         (Android-owned)
 *   runner.js        entry point                    (Android-owned)
 *   page-helper.js   selector engine στη σελίδα     (Android-owned)
 *   browser-step.js  BrowserPage πάνω σε WebView    (Android-owned)
 *   render-pdf.js    graceful degradation           (Android-owned)
 *   hyper-http.js    ΑΥΤΟΥΣΙΟ από τον runner
 *   configs/         ένα .js ανά διαδικασία, ΑΥΤΟΥΣΙΑ από τον runner
 *   configs.json     κατάλογος, παράγεται από το vendor script
 * ```
 */
class EngineAssets(private val context: Context) {

    private val cache = HashMap<String, String?>()

    fun read(assetPath: String): String? = try {
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /**
     * Λύνει ένα module id σε πηγαίο κώδικα. Το shims.js κανονικοποιεί ήδη τα
     * `../lib/browser-step` σε `browser-step`, οπότε εδώ αρκεί να δούμε και τους
     * δύο φακέλους.
     */
    @Synchronized
    fun moduleSource(name: String): String? = cache.getOrPut(name) {
        read("$ENGINE/$name.js") ?: read("$ENGINE/configs/$name.js")
    }

    val shims: String get() = requireAsset("shims.js")
    val runner: String get() = requireAsset("runner.js")

    /**
     * Ο selector engine που εγχέεται στη σελίδα-στόχο. Διαβάζεται μία φορά:
     * εγχέεται σε κάθε onPageFinished, δηλαδή δεκάδες φορές ανά διαδικασία.
     */
    val pageHelper: String by lazy { requireAsset("page-helper.js") }

    private fun requireAsset(file: String): String =
        read("$ENGINE/$file") ?: error("Λείπει το asset engine/$file")

    /** Ο κατάλογος διαδικασιών, όπως τον παρήγαγε το vendor script. */
    fun catalog(): List<ConfigInfo> {
        val raw = read("$ENGINE/configs.json") ?: return emptyList()
        val arr = JSONObject(raw).optJSONArray("configs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val inputsArr = o.optJSONArray("inputs")
            ConfigInfo(
                id = o.getString("id"),
                title = o.optString("title"),
                portal = o.optString("portal"),
                needsBrowser = o.optBoolean("needsBrowser"),
                inputs = (0 until (inputsArr?.length() ?: 0)).map { j ->
                    val p = inputsArr!!.getJSONObject(j)
                    ConfigInput(
                        key = p.getString("key"),
                        label = p.optString("label", p.getString("key")),
                        hidden = p.optBoolean("hidden"),
                        optional = p.optBoolean("optional"),
                    )
                },
            )
        }
    }

    private companion object {
        const val ENGINE = "engine"
    }
}

/** Μεταδεδομένα μιας διαδικασίας, όπως τα δηλώνει το config. */
data class ConfigInfo(
    val id: String,
    val title: String,
    val portal: String,
    val needsBrowser: Boolean,
    val inputs: List<ConfigInput>,
)

/**
 * Ένα input που ζητά η διαδικασία. Το [hidden] σημαίνει «κωδικός»: δεν
 * εμφανίζεται, δεν λογάρεται, δεν μπαίνει σε αντίγραφο οθόνης.
 */
data class ConfigInput(
    val key: String,
    val label: String,
    val hidden: Boolean,
    val optional: Boolean,
)
