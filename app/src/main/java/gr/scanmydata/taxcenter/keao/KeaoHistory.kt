package gr.scanmydata.taxcenter.keao

import gr.scanmydata.taxcenter.engine.FileBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Τα **μητρώα ΚΕΑΟ** που έχουν φανεί για έναν πελάτη — για να μπορεί ο λογιστής
 * να διαλέξει από λίστα και όχι να πληκτρολογεί ΑΜΟ από μνήμης.
 *
 * ## Γιατί δικό του αρχείο και όχι το JSON της λήψης
 *
 * Το `KEAO_ofeiles_<ΑΦΜ>.json` ξαναγράφεται σε κάθε λήψη **με ό,τι ζητήθηκε**.
 * Μετά από μια λήψη μόνο του ΤΕΚΑ, θα έλεγε ότι ο πελάτης έχει μόνο ΤΕΚΑ, και
 * την επόμενη φορά ο ΕΦΚΑ δεν θα προσφερόταν καν. Εδώ κρατιέται η **ένωση**:
 * ένα μητρώο που είδαμε μία φορά μένει στη λίστα.
 *
 * Ζει δίπλα στα υπόλοιπα αρχεία του πελάτη (app-private), σβήνεται μαζί τους
 * στη διαγραφή, και δεν ανεβαίνει στο Drive — εκεί πάνε μόνο έγγραφα.
 */
object KeaoHistory {

    const val FILE = "KEAO_mitroa.json"

    /** Προσθέτει ό,τι νέο βρέθηκε. Τα παλιά μένουν, με ανανεωμένη περιγραφή. */
    fun remember(dir: File, found: List<KeaoCard.Registry>) {
        if (found.isEmpty()) return
        val merged = LinkedHashMap<String, KeaoCard.Registry>()
        for (r in read(dir)) merged[r.key] = r
        for (r in found) merged[r.key] = r
        val arr = JSONArray()
        for (r in merged.values) {
            arr.put(
                JSONObject()
                    .put("amo", r.amo)
                    .put("am", r.am)
                    .put("title", r.title)
                    .put("category", r.category),
            )
        }
        runCatching { File(dir, FILE).writeText(JSONObject().put("registries", arr).toString(), Charsets.UTF_8) }
    }

    /**
     * Τα γνωστά μητρώα ενός πελάτη, από τον φάκελο που γράφει το `ProcessRunner`
     * (`runs/<ΑΦΜ>/keao-debts/`). Διαβάζει δίσκο — όχι από το main thread.
     */
    fun forClient(filesDir: File, afm: String): List<KeaoCard.Registry> {
        if (afm.isBlank()) return emptyList()
        val dir = File(
            filesDir,
            "runs/${FileBridge.sanitiseSegment(afm)}/${FileBridge.sanitiseSegment(CONFIG)}",
        )
        return read(dir)
    }

    private const val CONFIG = "keao-debts"

    /** Τα γνωστά μητρώα, με τη σειρά που πρωτοεμφανίστηκαν. Κενό αν δεν υπάρχουν. */
    fun read(dir: File): List<KeaoCard.Registry> {
        val file = File(dir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("registries") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                KeaoCard.Registry(
                    amo = o.optString("amo"),
                    am = o.optString("am"),
                    title = o.optString("title"),
                    category = o.optString("category"),
                ).takeIf { it.key.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }
}
