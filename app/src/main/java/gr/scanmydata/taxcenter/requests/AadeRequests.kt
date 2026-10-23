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
        /** Η υπηρεσία **υποβολής** («Υπηρεσία Υποβολής Αιτήματος» · τμήμα). */
        val service: String = "",
        val text: String = "",
        val answer: String = "",
        val rejected: Boolean = false,
        /**
         * Η **ανάθεση**: «Υπηρεσία Διεκπεραίωσης Αιτήματος» και «Τμήμα Υπηρεσίας
         * Διεκπεραίωσης», όπως τα ονομάζει η πύλη. Άλλο από την υπηρεσία
         * υποβολής, και αλλάζει όσο το αίτημα προχωρά.
         */
        val handlingService: String = "",
        val handlingDept: String = "",
        /**
         * Τι ζητά η ΑΑΔΕ από τον πελάτη, από την ενδιάμεση επικοινωνία — κενό
         * όταν δεν εκκρεμεί τίποτα. Συμπληρώνεται από το αποθηκευμένο αίτημα
         * ([RequestDetail.merge]), γιατί η λίστα δεν το έχει.
         */
        val ask: String = "",
        val askDate: String = "",
    ) {
        /**
         * Απάντησε η **ΑΑΔΕ**; Από τον κωδικό της πύλης όπου υπάρχει (3 =
         * απαντημένο). Η λέξη «Απαντημένο» δεν αρκεί: την ίδια λέξη δείχνει η
         * πύλη και όταν απάντησε **ο πελάτης** σε εκκρεμότητα.
         */
        val answered: Boolean
            get() = if (statusCode != 0) {
                statusCode == STATUS_ANSWERED
            } else {
                answer.isNotBlank() || fold(status).startsWith("απαντ")
            }

        /** Περιμένει ενέργεια του πελάτη (κωδικός 4). */
        val waitingClient: Boolean get() = stage(status, statusCode) == Kind.WAITING_CLIENT

        /** Η ανάθεση σε μία γραμμή. */
        val handling: String
            get() = listOf(handlingService, handlingDept).filter { it.isNotBlank() }.distinct().joinToString(" · ")

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

    /**
     * Τι είδους αλλαγή.
     *
     * Δύο καταστάσεις έχουν δικό τους είδος, γιατί ο λογιστής θέλει να τις
     * μάθει **με το όνομά τους** και όχι ως «αλλαγή κατάστασης»:
     *
     *  * [WAITING_CLIENT] — «Σε εκκρεμότητα από τον συναλλασσόμενο»: η ΑΑΔΕ
     *    περιμένει κάτι από τον πελάτη (έγγραφο, διευκρίνιση). Χωρίς ενέργεια το
     *    αίτημα δεν προχωρά — και συχνά έχει προθεσμία.
     *  * [PROCESSING] — «Σε επεξεργασία»: κάποιος το ανέλαβε.
     */
    enum class Kind { ANSWERED, STATUS, PROCESSING, WAITING_CLIENT, MESSAGE }

    // Οι κωδικοί κατάστασης, όπως τους ορίζει το ίδιο το SPA της πύλης
    // (eticketaade, πίνακας `*_STATUS_CODE` — επαληθεύτηκε 23 Οκτ 2026).
    const val STATUS_SENT = 1
    const val STATUS_PROCESSING = 2
    const val STATUS_ANSWERED = 3
    const val STATUS_CLIENT_PENDING = 4
    const val STATUS_REVOKED = 5
    const val STATUS_REVIEW = 6
    const val STATUS_SENT_FOR_REVOCATION = 7
    const val STATUS_REJECTED = 8
    const val STATUS_ARCHIVED = 10

    /** Τα κείμενα της πύλης για κάθε κωδικό — όταν το αίτημα έχει μόνο τον αριθμό. */
    fun statusLabel(code: Int): String = when (code) {
        STATUS_SENT -> "Απεσταλμένο"
        STATUS_PROCESSING -> "Σε επεξεργασία"
        STATUS_ANSWERED -> "Απαντημένο"
        STATUS_CLIENT_PENDING -> "Σε εκκρεμότητα από τον συναλλασσόμενο"
        STATUS_REVOKED -> "Ανακλήθηκε"
        STATUS_REVIEW -> "Επανεξέταση"
        STATUS_SENT_FOR_REVOCATION -> "Αίτηση ανάκλησης"
        STATUS_REJECTED -> "Απορρίφθηκε"
        STATUS_ARCHIVED -> "Αρχειοθετήθηκε – Άνευ ενέργειας"
        else -> ""
    }

    /**
     * Η κατάσταση **σύντομα**, για κάρτες και γραμμές λίστας. Το «Σε εκκρεμότητα
     * από τον συναλλασσόμενο» δεν χωρά δίπλα σε έναν τίτλο σε τηλέφωνο — και
     * έσπαγε την κάρτα. Ολόκληρο φαίνεται μέσα στο αίτημα.
     */
    fun shortStatus(request: Request): String = when {
        request.rejected || request.statusCode == STATUS_REJECTED -> "Απορρίφθηκε"
        request.waitingClient -> "Εκκρεμεί ο πελάτης"
        request.statusCode == STATUS_ARCHIVED -> "Αρχειοθετήθηκε"
        request.statusCode == STATUS_SENT_FOR_REVOCATION -> "Ανάκληση"
        request.status.isNotBlank() && request.status.length <= 22 -> request.status
        request.statusCode != 0 && statusLabel(request.statusCode).length <= 22 -> statusLabel(request.statusCode)
        request.answered -> "Απαντημένο"
        request.status.isNotBlank() -> request.status.take(20).trimEnd() + "…"
        else -> "Σε αναμονή"
    }

    /** Ανοιχτό αίτημα: μπορεί ακόμη να έρθει απάντηση ή ερώτηση της ΑΑΔΕ. */
    fun open(request: Request): Boolean = when (request.statusCode) {
        STATUS_SENT, STATUS_PROCESSING, STATUS_CLIENT_PENDING, STATUS_REVIEW, STATUS_SENT_FOR_REVOCATION -> true
        0 -> !request.answered && !request.rejected
        else -> false
    }

    /**
     * Η κατάσταση ως [Kind], όταν είναι μία από τις δύο που ονομάζονται ρητά.
     *
     * Με λέξεις και όχι με τον αριθμό `messageStatus`: τον αριθμό τον είδαμε
     * ζωντανά μόνο για «απαντημένο», ενώ το κείμενο το γράφει η πύλη με τον
     * ίδιο τρόπο που το βλέπει ο χρήστης.
     */
    fun stage(status: String, code: Int = 0): Kind? {
        when (code) {
            STATUS_CLIENT_PENDING -> return Kind.WAITING_CLIENT
            STATUS_PROCESSING -> return Kind.PROCESSING
            0 -> Unit
            else -> return null
        }
        val f = fold(status)
        return when {
            f.contains("εκκρεμ") && f.contains("συναλλασσ") -> Kind.WAITING_CLIENT
            f.contains("επεξεργασ") -> Kind.PROCESSING
            else -> null
        }
    }

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
        handlingService = text(o, "processGroupText"),
        handlingDept = text(o, "idSubDepartmentAitimatosText").ifBlank { text(o, "processGroupProtipoTmimaText") },
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
     * αίτημα που ακόμη περιμένει δεν είναι είδηση — ο λογιστής το υπέβαλε· εκτός αν
     * είναι ήδη σε επεξεργασία ή σε εκκρεμότητα από τον πελάτη (βλ. [stage]).
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
                // Μπήκε τώρα σε επεξεργασία ή σε εκκρεμότητα από τον πελάτη —
                // και όταν το αίτημα εμφανίζεται πρώτη φορά ήδη έτσι.
                stage(request.status, request.statusCode) != null &&
                    (before == null || statusOf(before) != fold(request.status)) ->
                    out += Change(request, stage(request.status, request.statusCode)!!)
                // Απαντημένο και πριν, με την ίδια απάντηση: άλλαξε μόνο η
                // κατάσταση (π.χ. έκλεισε). Και αυτό το θέλει ο λογιστής.
                before != null -> out += Change(request, Kind.STATUS)
            }
        }
        return out
    }

    private fun statusOf(stamp: String): String = stamp.split('|').firstOrNull().orEmpty()

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
