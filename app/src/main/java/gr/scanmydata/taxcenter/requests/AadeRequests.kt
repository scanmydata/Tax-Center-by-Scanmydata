package gr.scanmydata.taxcenter.requests

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * «Τα Αιτήματά μου» της ΑΑΔΕ, όπως τα γράφει το `easynotify` (`which=REQUESTS`).
 *
 * Ο πελάτης — ή ο λογιστής για λογαριασμό του — υποβάλλει ένα αίτημα στην ΑΑΔΕ
 * και μετά **περιμένει**. Η απάντηση έρχεται μέσα στην πύλη, χωρίς ειδοποίηση
 * που να φτάνει στο γραφείο, και ο μόνος τρόπος να τη δει κανείς ήταν να
 * συνδέεται κάθε μέρα σε κάθε πελάτη. Αυτό το αρχείο απαντά σε μία ερώτηση:
 * **τι άλλαξε από την τελευταία φορά που κοιτάξαμε;**
 *
 * ## Το σχήμα
 *
 * Επαληθεύτηκε ζωντανά στις 2 Οκτωβρίου 2026: το `aadeRequests.items[]` είναι
 * οι εγγραφές του `eticketaade/api/amsmsg/filterMessages` αυτούσιες —
 * `messageId` (αριθμός), `caseNumber`, `submittedDate` / `updatedDate`
 * («yyyy-MM-dd HH:mm:ss»), `messageStatus` + `messageStatusText`,
 * `thematicalGroupText`, `diadikasiaText`, `orgGroupText`, `messageText`,
 * `answerText`, `rejected`. Όλα τα αιτήματα που είδαμε ήταν σε κατάσταση
 * `3 / Απαντημένο` — ένα από αυτά **χωρίς** κείμενο απάντησης.
 *
 * Γι' αυτό «απαντήθηκε» κρίνεται από την κατάσταση **ή** από το κείμενο, και
 * όχι από τον αριθμό 3 μόνο: τις υπόλοιπες τιμές δεν τις έχουμε δει, και ένας
 * κανόνας που στηρίζεται σε αριθμό που δεν ξέρουμε θα σώπαινε ακριβώς όταν
 * χρειάζεται να μιλήσει.
 *
 * Καθαρή Kotlin, χωρίς Android: όλη η λογική «τι είναι νέο» ελέγχεται με τεστ.
 */
object AadeRequests {

    const val CONFIG = "easynotify"

    data class Request(
        val id: String,
        val caseNumber: String = "",
        val submitted: String = "",
        val updated: String = "",
        val status: String = "",
        val statusCode: Int = 0,
        /** Θεματική ενότητα και διαδικασία — «για ποιο πράγμα» είναι το αίτημα. */
        val topic: String = "",
        val process: String = "",
        /** Η υπηρεσία που το χειρίζεται. */
        val service: String = "",
        val text: String = "",
        val answer: String = "",
        val rejected: Boolean = false,
    ) {
        val answered: Boolean
            get() = answer.isNotBlank() || fold(status).startsWith("απαντ")

        /** Η γραμμή που ονομάζει το αίτημα σε λίστα. */
        val title: String
            get() = listOf(process, topic).firstOrNull { it.isNotBlank() } ?: "Αίτημα"

        /**
         * Ό,τι, αν αλλάξει, αξίζει να το μάθει άνθρωπος: κατάσταση, απόρριψη,
         * και το **αποτύπωμα** της απάντησης.
         *
         * Αποτύπωμα και όχι το κείμενο: η σύγκριση γίνεται με ένα μικρό αρχείο
         * κατάστασης, και δεν υπάρχει λόγος η απάντηση της ΑΑΔΕ να γράφεται σε
         * δεύτερο σημείο του δίσκου.
         */
        val stamp: String
            get() = listOf(
                fold(status),
                if (rejected) "R" else "",
                if (answer.isBlank()) "" else answer.trim().hashCode().toString(16),
            ).joinToString("|")
    }

    enum class Kind { ANSWERED, STATUS }

    data class Change(val request: Request, val kind: Kind)

    /**
     * Τα αιτήματα του JSON, νεότερα πρώτα.
     *
     * `null` όταν το config **δεν διάβασε** τα αιτήματα: το `easynotify`
     * επιστρέφει `ok` ακόμη κι αν το τμήμα των αιτημάτων έσκασε, γράφοντας στη
     * θέση του ένα «ERR:…». Αυτό δεν είναι «κανένα αίτημα», και δεν πρέπει να
     * διαβαστεί έτσι — αλλιώς στην επόμενη επιτυχημένη λήψη κάθε παλιό αίτημα
     * θα έμοιαζε καινούργιο.
     */
    fun parse(json: String): List<Request>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val section = root.optJSONObject("aadeRequests") ?: return null
        val items = section.optJSONArray("items") ?: JSONArray()
        return (0 until items.length())
            .mapNotNull { i -> items.optJSONObject(i)?.let(::request) }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedByDescending { it.updated.ifBlank { it.submitted } }
    }

    /** Γιατί δεν διαβάστηκαν τα αιτήματα, όταν το [parse] δίνει `null`. */
    fun error(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "Μη αναγνώσιμο αποτέλεσμα"
        val raw = root.opt("aadeRequests")
        return if (raw is String) raw.removePrefix("ERR:").trim() else ""
    }

    private fun request(o: JSONObject): Request = Request(
        id = text(o, "messageId"),
        caseNumber = text(o, "caseNumber"),
        submitted = text(o, "submittedDate"),
        updated = text(o, "updatedDate"),
        status = text(o, "messageStatusText").ifBlank { text(o, "messageStatus") },
        statusCode = o.optInt("messageStatus", 0),
        topic = text(o, "thematicalGroupText").ifBlank { text(o, "processGroupText") },
        process = text(o, "diadikasiaText"),
        service = listOf(text(o, "orgGroupText"), text(o, "protipoTmimaText"))
            .filter { it.isNotBlank() }.distinct().joinToString(" · "),
        text = text(o, "messageText"),
        answer = text(o, "answerText"),
        rejected = o.optBoolean("rejected", false),
    )

    /**
     * Τι άλλαξε σε σχέση με την προηγούμενη φορά.
     *
     * @param known `id -> stamp` της προηγούμενης λήψης. `null` σημαίνει ότι
     *   **δεν υπάρχει προηγούμενη**: τότε τίποτα δεν είναι «νέο». Ο πελάτης που
     *   μπαίνει σήμερα στην παρακολούθηση με δέκα αιτήματα απαντημένα από πέρσι
     *   δεν πρέπει να στείλει δέκα ειδοποιήσεις.
     *
     * Ένα αίτημα που **δεν υπήρχε** και βρέθηκε ήδη απαντημένο μετρά ως νέα
     * απάντηση: υποβλήθηκε και απαντήθηκε ανάμεσα σε δύο ελέγχους. Ένα νέο
     * αίτημα που ακόμη περιμένει δεν είναι είδηση — ο λογιστής το υπέβαλε.
     */
    fun diff(known: Map<String, String>?, now: List<Request>): List<Change> {
        if (known == null) return emptyList()
        val out = ArrayList<Change>()
        for (request in now) {
            val before = known[request.id]
            if (before == request.stamp) continue
            when {
                request.answered && (before == null || answerOf(before) != answerOf(request.stamp)) ->
                    out += Change(request, Kind.ANSWERED)
                // Απαντημένο και πριν, με την ίδια απάντηση: άλλαξε μόνο η
                // κατάσταση (π.χ. έκλεισε). Και αυτό το θέλει ο λογιστής.
                before != null -> out += Change(request, Kind.STATUS)
            }
        }
        return out
    }

    /** `id -> stamp`, για το αρχείο κατάστασης. */
    fun stamps(requests: List<Request>): Map<String, String> = requests.associate { it.id to it.stamp }

    /**
     * Η «απάντηση» ενός αποτυπώματος: το κείμενό της, όπου υπάρχει. Το
     * «απαντημένο» χωρίς κείμενο είναι κι αυτό απάντηση — άλλη από το τίποτα,
     * και άλλη από το κείμενο που ίσως έρθει μετά.
     */
    private fun answerOf(stamp: String): String {
        val parts = stamp.split('|')
        val text = parts.getOrNull(2).orEmpty()
        return when {
            text.isNotBlank() -> text
            parts.getOrNull(0).orEmpty().startsWith("απαντ") -> "A"
            else -> ""
        }
    }

    // -------------------------------------------------------------- βοηθοί

    /** «2026-09-30 12:34:56» -> «30/09/2026 12:34». Ό,τι δεν ταιριάζει μένει ως έχει. */
    fun date(raw: String): String {
        val m = DATE.find(raw.trim()) ?: return raw.trim()
        val (y, mo, d, h, mi) = m.destructured
        return "$d/$mo/$y" + if (h.isBlank()) "" else " $h:$mi"
    }

    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?""")

    /** Πεζά χωρίς τόνους — η πύλη γράφει «Απαντημένο», αύριο ίσως «ΑΠΑΝΤΗΜΕΝΟ». */
    private fun fold(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** Το `optString` γυρίζει τη λέξη «null» για JSON null· εδώ θέλουμε κενό. */
    private fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key).trim()
}
