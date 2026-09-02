package gr.scanmydata.taxcenter.data

import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import gr.scanmydata.taxcenter.engine.Redactor

/**
 * Μετατρέπει ένα φύλλο Excel σε προεπισκόπηση εισαγωγής.
 *
 * **Τίποτα δεν γράφεται πριν την έγκριση.** Ο χρήστης βλέπει πρώτα τι ακριβώς
 * θα αλλάξει, με τις τιμές μασκαρισμένες, και μετά αποφασίζει. Ο κανόνας έρχεται
 * αυτούσιος από το `timologio-downloader`, όπου έχει αποδειχθεί σωστός σε
 * πραγματική χρήση.
 *
 * Οι προειδοποιήσεις δεν είναι απορρίψεις: μια γραμμή με ύποπτο ΑΦΜ εισάγεται
 * κανονικά και απλώς σημαίνεται. Το να αρνηθεί η εφαρμογή να καταχωρήσει έναν
 * πελάτη είναι χειρότερο από μια προειδοποίηση που ο λογιστής μπορεί να κρίνει.
 */
object ImportPreview {

    enum class Action { NEW, UPDATE, UNCHANGED }

    /**
     * Μία γραμμή προς εισαγωγή.
     *
     * Το [values] κρατά **καθαρές** τιμές στη μνήμη — αναγκαστικά, γιατί από εκεί
     * θα κρυπτογραφηθούν κατά την εγγραφή. Για την οθόνη υπάρχει το [masked].
     */
    data class Row(
        val afm: String,
        val values: Map<Field, String>,
        val action: Action,
        val warnings: List<String> = emptyList(),
    ) {
        val name: String get() = values[Field.NAME].orEmpty()
        val firstName: String get() = values[Field.FIRST_NAME].orEmpty()

        val displayName: String
            get() = listOf(name, firstName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { afm }

        /** Τι δείχνουμε στην οθόνη: τα μυστικά μόνο μασκαρισμένα. */
        fun masked(field: Field): String {
            val v = values[field].orEmpty()
            return if (field in SECRET_FIELDS) Redactor.mask(v) else v
        }

        val hasTaxisCredentials: Boolean
            get() = values[Field.TAXIS_USER].isNullOrBlank().not() &&
                values[Field.TAXIS_PASS].isNullOrBlank().not()
    }

    data class Result(
        val sheetName: String,
        val rows: List<Row>,
        /** Στήλες που αγνοήθηκαν σκόπιμα: επικεφαλίδα -> λόγος. */
        val ignoredColumns: List<Pair<String, String>> = emptyList(),
        /** Γραμμές χωρίς ΑΦΜ — δεν μπορούν να ταυτοποιηθούν. */
        val skippedRows: Int = 0,
    ) {
        val newCount: Int get() = rows.count { it.action == Action.NEW }
        val updateCount: Int get() = rows.count { it.action == Action.UPDATE }
        val unchangedCount: Int get() = rows.count { it.action == Action.UNCHANGED }
        val withTaxis: Int get() = rows.count { it.hasTaxisCredentials }

        val summary: String
            get() = "${rows.size} πελάτες · $newCount νέοι · $updateCount ενημερώσεις · " +
                "$withTaxis με κωδικούς TAXISnet"
    }

    class NoUsableSheetException(message: String) : Exception(message)

    private val SECRET_FIELDS = setOf(
        Field.TAXIS_PASS, Field.TAXIS_KLIDARITHMOS,
        Field.IKA_EMPLOYER_PASS, Field.IKA_INSURED_PASS,
        Field.MYDATA_KEY, Field.AMKA,
    )

    /** Πόσες γραμμές ψάχνουμε για επικεφαλίδες πριν τα παρατήσουμε. */
    private const val HEADER_SCAN = 10

    /**
     * Χτίζει την προεπισκόπηση.
     *
     * Χρησιμοποιείται το **πρώτο φύλλο που έχει στήλη ΑΦΜ**. Τα υπόλοιπα
     * αγνοούνται: στα πραγματικά exports το δεύτερο φύλλο είναι συνήθως
     * υποσύνολο του πρώτου χωρίς στήλες κωδικών.
     */
    fun build(
        sheets: List<XlsxReader.Sheet>,
        existingAfms: Set<String>,
    ): Result {
        for (sheet in sheets) {
            val headerIndex = findHeaderRow(sheet.rows) ?: continue
            val headerRow = sheet.rows[headerIndex]
            val mapping = ColumnAliases.mapHeaderRow(headerRow)
            if (!mapping.containsValue(Field.AFM)) continue

            val ignored = headerRow.values
                .mapNotNull { text -> ColumnAliases.forbiddenReason(text)?.let { text to it } }

            val merged = LinkedHashMap<String, MutableMap<Field, String>>()
            val seen = LinkedHashMap<String, Int>()
            var skipped = 0

            for (i in (headerIndex + 1) until sheet.rows.size) {
                val raw = sheet.rows[i]
                val values = HashMap<Field, String>()
                for ((column, field) in mapping) {
                    val cell = raw[column]?.trim().orEmpty()
                    if (cell.isNotEmpty()) values[field] = cell
                }

                val afm = Normalize.afm(values[Field.AFM])
                if (afm.isEmpty()) {
                    skipped++
                    continue
                }
                values[Field.AFM] = afm
                seen[afm] = (seen[afm] ?: 0) + 1

                // Ίδιο ΑΦΜ πολλές φορές: κερδίζει η τελευταία ΜΗ ΚΕΝΗ τιμή ανά πεδίο.
                val target = merged.getOrPut(afm) { LinkedHashMap() }
                for ((field, value) in values) {
                    if (value.isNotBlank()) target[field] = value
                }
            }

            val rows = merged.map { (afm, values) ->
                buildRow(afm, values, seen[afm] ?: 1, existingAfms)
            }
            return Result(sheet.name, rows, ignored, skipped)
        }

        throw NoUsableSheetException(
            "Δεν βρέθηκε στήλη «Α.Φ.Μ.» σε κανένα φύλλο. Ελέγξτε ότι εξήγατε το " +
                "αρχείο «Κωδικοί Υπόχρεων» από το λογιστικό σας πρόγραμμα.",
        )
    }

    private fun buildRow(
        afm: String,
        values: MutableMap<Field, String>,
        occurrences: Int,
        existingAfms: Set<String>,
    ): Row {
        val warnings = ArrayList<String>()

        if (!Normalize.validAfm(afm)) {
            warnings += "το ΑΦΜ δεν περνά τον έλεγχο ψηφίου"
        }
        if (occurrences > 1) {
            warnings += "$occurrences γραμμές για το ίδιο ΑΦΜ — συγχωνεύτηκαν"
        }

        // Κλειδί myDATA που δεν έχει τη σωστή μορφή είναι σχεδόν πάντα λάθος
        // στήλη. Το πετάμε αντί να προκαλέσουμε 403 σε κάθε κλήση.
        val key = values[Field.MYDATA_KEY]
        if (!key.isNullOrBlank() && !Normalize.validSubscriptionKey(key)) {
            warnings += "το κλειδί myDATA δεν είναι 32 δεκαεξαδικά — αγνοήθηκε"
            values.remove(Field.MYDATA_KEY)
        }
        if (!values[Field.MYDATA_KEY].isNullOrBlank() && values[Field.MYDATA_USER].isNullOrBlank()) {
            warnings += "κλειδί myDATA χωρίς όνομα χρήστη"
        }

        val amka = values[Field.AMKA]
        if (!amka.isNullOrBlank()) {
            val normalised = Normalize.amka(amka)
            if (normalised.isEmpty() || !Normalize.validAmka(normalised)) {
                warnings += "το ΑΜΚΑ δεν μοιάζει έγκυρο"
                values.remove(Field.AMKA)
            } else {
                values[Field.AMKA] = normalised
            }
        }

        val user = values[Field.TAXIS_USER]
        val pass = values[Field.TAXIS_PASS]
        if (!user.isNullOrBlank() && pass.isNullOrBlank()) {
            warnings += "χρήστης TAXISnet χωρίς συνθηματικό — οι λήψεις δεν θα δουλέψουν"
        }

        val carriesData = values.keys.any { it != Field.AFM && values[it]?.isNotBlank() == true }
        val action = when {
            afm !in existingAfms -> Action.NEW
            carriesData -> Action.UPDATE
            else -> Action.UNCHANGED
        }

        return Row(afm, values, action, warnings)
    }

    /** Η πρώτη γραμμή στις πρώτες [HEADER_SCAN] που αντιστοιχίζει ΑΦΜ. */
    private fun findHeaderRow(rows: List<Map<String, String>>): Int? {
        for (i in 0 until minOf(HEADER_SCAN, rows.size)) {
            val mapping = ColumnAliases.mapHeaderRow(rows[i])
            if (mapping.containsValue(Field.AFM)) return i
        }
        return null
    }
}
