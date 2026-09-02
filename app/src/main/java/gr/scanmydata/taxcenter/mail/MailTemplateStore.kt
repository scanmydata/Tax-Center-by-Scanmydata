package gr.scanmydata.taxcenter.mail

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Τα επεξεργάσιμα πρότυπα των email.
 *
 * Δύο πράγματα είναι ρυθμιζόμενα και ένα δεν είναι:
 *
 *  * **Ρυθμιζόμενα**: το θέμα, το εισαγωγικό και το καταληκτικό κείμενο, και
 *    **ποια δυναμικά πεδία** μπαίνουν στο μήνυμα.
 *  * **Δεν ρυθμίζεται**: η προειδοποίηση ασφαλείας όταν στέλνονται κωδικοί. Δεν
 *    είναι διακοσμητική — είναι η μόνη ένδειξη που παίρνει ο πελάτης ότι κρατά
 *    στο γραμματοκιβώτιό του κάτι που πρέπει να σβήσει, και δεν πρέπει να
 *    μπορεί να απενεργοποιηθεί κατά λάθος.
 *
 * Τα πεδία είναι διακόπτες και όχι placeholders μέσα στο κείμενο: ένα
 * `{{συνθηματικό}}` που ξεχάστηκε σε ένα πρότυπο θα έστελνε κωδικό σε κάθε
 * πελάτη χωρίς να το προσέξει κανείς. Έτσι, το τι φεύγει είναι λίστα που
 * βλέπεις, όχι κείμενο που διαβάζεις.
 */
class MailTemplateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("taxcenter_templates", Context.MODE_PRIVATE)

    /** Τα δυναμικά πεδία του μηνύματος με τα στοιχεία του πελάτη. */
    enum class CredentialField(val key: String, val label: String, val sensitive: Boolean = false) {
        AFM("afm", "ΑΦΜ"),
        AMKA("amka", "ΑΜΚΑ"),
        DOY("doy", "ΔΟΥ"),
        TAXIS_USER("taxis_user", "Όνομα χρήστη TAXISnet"),
        TAXIS_PASS("taxis_pass", "Συνθηματικό TAXISnet", sensitive = true),
        KLIDARITHMOS("klidarithmos", "Κλειδάριθμος", sensitive = true),
    }

    /** Τα δυναμικά πεδία του μηνύματος με τα έντυπα. */
    enum class DocumentField(val key: String, val label: String) {
        FILE_LIST("file_list", "Λίστα ονομάτων αρχείων"),
        COUNT("count", "Πλήθος εντύπων στο θέμα"),
        AFM_IN_SUBJECT("afm_subject", "ΑΦΜ στο θέμα"),
        NOTE("note", "Σημείωμα της αποστολής"),
    }

    data class Template(
        val subject: String,
        val intro: String,
        val closing: String,
        val fields: Set<String>,
    ) {
        fun has(field: CredentialField) = field.key in fields
        fun has(field: DocumentField) = field.key in fields
    }

    var credentials: Template
        get() = read(KEY_CREDENTIALS, DEFAULT_CREDENTIALS)
        set(value) = write(KEY_CREDENTIALS, value)

    var documents: Template
        get() = read(KEY_DOCUMENTS, DEFAULT_DOCUMENTS)
        set(value) = write(KEY_DOCUMENTS, value)

    fun resetCredentials() = prefs.edit().remove(KEY_CREDENTIALS).apply()

    fun resetDocuments() = prefs.edit().remove(KEY_DOCUMENTS).apply()

    // ------------------------------------------------------------ αποθήκευση

    private fun read(key: String, fallback: Template): Template {
        val raw = prefs.getString(key, null) ?: return fallback
        return try {
            val json = JSONObject(raw)
            val array = json.optJSONArray("fields") ?: JSONArray()
            Template(
                subject = json.optString("subject", fallback.subject),
                intro = json.optString("intro", fallback.intro),
                closing = json.optString("closing", fallback.closing),
                fields = (0 until array.length()).map { array.getString(it) }.toSet(),
            )
        } catch (e: Exception) {
            // Χαλασμένη ρύθμιση δεν πρέπει να μπλοκάρει την αποστολή· γυρνάμε
            // στο προεπιλεγμένο πρότυπο, που είναι πάντα σωστό.
            fallback
        }
    }

    private fun write(key: String, template: Template) {
        val json = JSONObject()
            .put("subject", template.subject)
            .put("intro", template.intro)
            .put("closing", template.closing)
            .put("fields", JSONArray().apply { template.fields.forEach { put(it) } })
        prefs.edit().putString(key, json.toString()).apply()
    }

    companion object {
        private const val KEY_CREDENTIALS = "template_credentials"
        private const val KEY_DOCUMENTS = "template_documents"

        /** Διαθέσιμα placeholders, για την οθόνη επεξεργασίας. */
        const val PLACEHOLDER_NAME = "{{επωνυμία}}"
        const val PLACEHOLDER_AFM = "{{αφμ}}"
        const val PLACEHOLDER_COUNT = "{{πλήθος}}"

        val DEFAULT_CREDENTIALS = Template(
            subject = "Τα στοιχεία σας — ΑΦΜ $PLACEHOLDER_AFM",
            intro = "Αγαπητέ/ή $PLACEHOLDER_NAME,\n\nΣας στέλνουμε τα στοιχεία σας:",
            closing = "",
            // Οι κωδικοί είναι **εκτός** εξ ορισμού. Ο διακόπτης στην οθόνη
            // αποστολής μπορεί να τους προσθέσει για μία συγκεκριμένη αποστολή.
            fields = setOf(
                CredentialField.AFM.key,
                CredentialField.AMKA.key,
                CredentialField.TAXIS_USER.key,
            ),
        )

        val DEFAULT_DOCUMENTS = Template(
            subject = "Φορολογικά έντυπα ($PLACEHOLDER_COUNT) — ΑΦΜ $PLACEHOLDER_AFM",
            intro = "Αγαπητέ/ή $PLACEHOLDER_NAME,\n\nΣας επισυνάπτουμε τα παρακάτω έντυπα:",
            closing = "Είμαστε στη διάθεσή σας για οποιαδήποτε διευκρίνιση.",
            fields = setOf(
                DocumentField.FILE_LIST.key,
                DocumentField.COUNT.key,
                DocumentField.AFM_IN_SUBJECT.key,
                DocumentField.NOTE.key,
            ),
        )
    }
}
