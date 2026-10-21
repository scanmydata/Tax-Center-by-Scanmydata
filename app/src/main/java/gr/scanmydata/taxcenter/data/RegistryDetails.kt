package gr.scanmydata.taxcenter.data

import org.json.JSONArray

/**
 * Τα πλήρη στοιχεία Μητρώου ενός πελάτη, όπως τα φέρνει η «Άντληση στοιχείων»:
 * πατρώνυμο, ταυτότητα, διεύθυνση, ΓΕΜΗ, ΚΑΔ, μέλη…
 *
 * Έρχονται από το `aade-profile.js` **ήδη σε ενότητες με ετικέτες**, και έτσι
 * αποθηκεύονται (JSON, κρυπτογραφημένο με το κλειδί του Keystore όπως ο ΑΜΚΑ).
 * Η οθόνη δεν ξέρει τα ονόματα των πεδίων της ΑΑΔΕ: δείχνει ό,τι ήρθε. Έτσι
 * ένα πεδίο που προστίθεται στο config φαίνεται χωρίς αλλαγή εδώ.
 *
 * Καθαρή Kotlin: ελέγχεται με τεστ.
 */
object RegistryDetails {

    data class Section(val title: String, val rows: List<Pair<String, String>>)

    /** Ό,τι δεν διαβάζεται είναι «καμία ενότητα» — ποτέ σφάλμα στην οθόνη. */
    fun parse(json: String): List<Section> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Section>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val rowsJson = o.optJSONArray("rows") ?: continue
            val rows = (0 until rowsJson.length()).mapNotNull { j ->
                val row = rowsJson.optJSONArray(j) ?: return@mapNotNull null
                val label = row.optString(0).trim()
                val value = row.optString(1).trim()
                if (label.isBlank() && value.isBlank()) null else label to value
            }
            if (title.isNotBlank() && rows.isNotEmpty()) out += Section(title, rows)
        }
        return out
    }

    /**
     * Οι ενότητες **χωρίς** ό,τι φαίνεται ήδη στα πεδία της καρτέλας, για να
     * μη γράφεται δύο φορές στην ίδια οθόνη.
     *
     * @param hidden ετικέτες που έχουν δικό τους πεδίο πάντα (π.χ. «Ονοματεπώνυμο»)
     * @param sameAs ετικέτα -> τιμή πεδίου· η γραμμή κρύβεται μόνο αν λέει **το ίδιο**.
     *   Η ΔΟΥ της επιχείρησης μετά τη διακοπή διαφέρει από τη ΔΟΥ της καρτέλας,
     *   και τότε πρέπει να φαίνεται.
     *
     * Τα αποθηκευμένα στοιχεία μένουν ολόκληρα (και στην εξαγωγή)· κρύβονται
     * μόνο στην οθόνη.
     */
    fun withoutShown(
        sections: List<Section>,
        hidden: Set<String>,
        sameAs: Map<String, String> = emptyMap(),
    ): List<Section> = sections
        .map { section ->
            section.copy(
                rows = section.rows.filterNot { (label, value) ->
                    label in hidden || sameAs[label]?.let { fold(it) == fold(value) && it.isNotBlank() } == true
                },
            )
        }
        .filter { it.rows.isNotEmpty() }

    private fun fold(raw: String): String = java.text.Normalizer
        .normalize(raw.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .uppercase()

    /** Πόσα πεδία συνολικά — για το «Περισσότερα στοιχεία (32)». */
    fun count(sections: List<Section>): Int = sections.sumOf { it.rows.size }
}
