package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.debts.DebtsRefresh
import gr.scanmydata.taxcenter.engine.FetchController
import gr.scanmydata.taxcenter.engine.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Η **άντληση των αιτημάτων** ενός πελάτη από την ΑΑΔΕ, και η σύγκριση με ό,τι
 * ξέραμε.
 *
 * Ένα σημείο για την καρτέλα, τη συγκεντρωτική οθόνη και τον
 * χρονοπρογραμματιστή: όλοι πρέπει να καταλήγουν στην ίδια μνήμη
 * ([RequestsStore.absorb]), αλλιώς ένα αίτημα που είδε ο λογιστής το απόγευμα
 * θα ξαναγινόταν «νέο» το επόμενο πρωί.
 *
 * ## Ο έλεγχος τα αποθηκεύει όλα
 *
 * Ένας έλεγχος κάνει δύο δουλειές: διαβάζει τη **λίστα**, και μετά κατεβάζει
 * **ολόκληρα** — κείμενο, απάντηση, συνημμένα — όσα αιτήματα δεν υπάρχουν ακόμη
 * στη συσκευή ή άλλαξαν από τότε. Έτσι το άνοιγμα ενός αιτήματος δεν χρειάζεται
 * ποτέ σύνδεση.
 *
 * Η δεύτερη δουλειά είναι δεύτερη συνεδρία GSIS, και γίνεται **μόνο όταν έχει
 * κάτι να φέρει**: στον συνηθισμένο πρωινό έλεγχο, όπου τίποτα δεν άλλαξε, ο
 * πελάτης δέχεται μία σύνδεση, όπως πριν.
 *
 * Περνά από την ίδια ουρά με κάθε άλλη λήψη ([DebtsRefresh.run]): δύο συνεδρίες
 * GSIS μαζί κλειδώνουν τον λογαριασμό του πελάτη.
 */
object RequestsRefresh {

    /** Ο engine γράφει έτσι την απόρριψη κωδικών από το GSIS. */
    private const val REASON_BAD_CREDENTIALS = "InvalidCredentials"

    enum class Outcome { OK, BUSY, MISSING, REJECTED, FAILED }

    /** Ποια αιτήματα κατεβαίνουν ολόκληρα μετά τη λίστα. */
    enum class Details {
        /** Κανένα — μόνο η λίστα. */
        NONE,

        /**
         * Όσα **άλλαξαν** σε αυτόν τον έλεγχο. Για τον χρονοπρογραμματιστή: η
         * ειδοποίηση πρέπει να οδηγεί σε απάντηση που ήδη υπάρχει στη συσκευή,
         * αλλά η εργασία παρασκηνίου δεν έχει χρόνο να κατεβάσει το ιστορικό
         * κάθε πελάτη.
         */
        CHANGED,

        /** Όσα λείπουν ή άλλαξαν. Για τον έλεγχο που ζητά ο χρήστης. */
        MISSING,
    }

    data class Result(
        val outcome: Outcome,
        val changes: List<AadeRequests.Change> = emptyList(),
        /** Έτοιμο κείμενο για την οθόνη. */
        val message: String = "",
        /** Πόσα αιτήματα αποθηκεύτηκαν ολόκληρα σε αυτή την εκτέλεση. */
        val saved: Int = 0,
    ) {
        val answered: Int get() = changes.count { it.kind == AadeRequests.Kind.ANSWERED }
    }

    /**
     * Η εργασία, ή `null` όταν λείπουν οι κωδικοί — δεν ανοίγουμε σύνδεση που
     * ξέρουμε ότι θα αποτύχει.
     *
     * Ζητείται **μόνο** το τμήμα των αιτημάτων (`which=REQUESTS`). Το ίδιο
     * config ξέρει να φέρει και τα «Μηνύματά μου» και το myPROPERTY, αλλά αυτά
     * είναι άλλη δουλειά, με εκατοντάδες εγγραφές ανά πελάτη, και δεν
     * ζητήθηκαν.
     */
    suspend fun plan(repository: ClientRepository, client: ClientEntity): FetchController.Plan? {
        if (FetchController.missingCredentials(repository, client, AadeRequests.CONFIG).isNotEmpty()) return null
        return FetchController.Plan(
            job = ProcessRunner.Job(
                client = client,
                configId = AadeRequests.CONFIG,
                extraInputs = mapOf("which" to "REQUESTS", DebtsRefresh.DATA_ONLY to "1"),
            ),
            label = "Αιτήματα ΑΑΔΕ — έλεγχος για απαντήσεις",
            producesDocuments = false,
        )
    }

    /**
     * @param details ποια αιτήματα αποθηκεύονται ολόκληρα μετά τη λίστα
     * @param ownsBatch βλ. [DebtsRefresh.run]: ο χρονοπρογραμματιστής ακυρώνει
     *   την παρτίδα όταν ακυρωθεί ο ίδιος, η οθόνη όχι.
     */
    suspend fun run(
        filesDir: File,
        fetch: FetchController,
        repository: ClientRepository,
        settings: Settings,
        client: ClientEntity,
        ownsBatch: Boolean = false,
        details: Details = Details.MISSING,
    ): Result {
        val plan = plan(repository, client)
            ?: return Result(Outcome.MISSING, message = "Λείπουν οι κωδικοί TAXISnet του πελάτη.")
        val items = DebtsRefresh.run(fetch, listOf(plan), ownsBatch)
            ?: return Result(Outcome.BUSY, message = DebtsRefresh.BUSY)
        val item = items.firstOrNull()
        if (item == null || item.status != FetchController.Status.OK) {
            val rejected = item?.reason == REASON_BAD_CREDENTIALS
            return Result(
                if (rejected) Outcome.REJECTED else Outcome.FAILED,
                message = item?.detail.orEmpty().ifBlank { "Η άντληση δεν ολοκληρώθηκε." },
            )
        }

        val changes = withContext(Dispatchers.IO) { RequestsStore.absorb(filesDir, client.afm) }
            ?: return Result(
                Outcome.FAILED,
                // Η σύνδεση πέτυχε· δεν απάντησε το τμήμα των αιτημάτων. Δεν
                // είναι «κανένα αίτημα», και δεν πρέπει να ακουστεί έτσι.
                message = "Η σύνδεση έγινε, αλλά η ΑΑΔΕ δεν έδωσε τα αιτήματα. Δοκίμασε ξανά σε λίγο.",
            )
        if (changes.isNotEmpty()) settings.requestWatchFresh = settings.requestWatchFresh + client.id

        // Η λίστα διαβάστηκε. Ό,τι ακολουθεί είναι επιπλέον: αν δεν γίνει, ο
        // έλεγχος **πέτυχε** — απλώς κάποια αιτήματα δεν ανοίγουν ακόμη χωρίς
        // σύνδεση, και το μήνυμα το λέει.
        val ids = when (details) {
            Details.NONE -> emptyList()
            Details.CHANGED -> changes.map { it.request.id }.distinct().take(RequestDetail.MAX_BATCH)
            Details.MISSING -> withContext(Dispatchers.IO) { RequestsStore.wanted(filesDir, client.afm) }
        }
        if (ids.isEmpty()) return Result(Outcome.OK, changes, describe(changes))

        val full = fetchDetails(filesDir, fetch, repository, client, ids, ownsBatch)
        return Result(
            Outcome.OK,
            changes,
            message = (describe(changes) + " " + when {
                full.outcome != Outcome.OK ->
                    "Τα πλήρη αιτήματα δεν κατέβηκαν (" + full.message.trimEnd('.') + ") — πάτα ξανά «Έλεγχος»."
                full.saved == ids.size -> saved(full.saved)
                else -> saved(full.saved) + " ${ids.size - full.saved} δεν δόθηκαν από την πύλη."
            }).trim(),
            saved = full.saved,
        )
    }

    private fun saved(count: Int): String = when (count) {
        0 -> ""
        1 -> "Αποθηκεύτηκε 1 αίτημα με τα συνημμένα του."
        else -> "Αποθηκεύτηκαν $count αιτήματα με τα συνημμένα τους."
    }

    // ------------------------------------------------------- πλήρη αιτήματα

    /**
     * Η εργασία που κατεβάζει **ολόκληρα** τα αιτήματα [ids] με τα συνημμένα
     * τους — όλα σε μία σύνδεση.
     *
     * Δεν παράγει «έγγραφα»: τα συνημμένα μένουν μέσα στο αίτημα — βλ.
     * [ProcessRunner] για το γιατί δεν μπαίνουν στη λίστα των εντύπων.
     */
    suspend fun detailPlan(
        repository: ClientRepository,
        client: ClientEntity,
        ids: List<String>,
    ): FetchController.Plan? {
        if (ids.isEmpty()) return null
        if (FetchController.missingCredentials(repository, client, RequestDetail.CONFIG).isNotEmpty()) return null
        return FetchController.Plan(
            job = ProcessRunner.Job(
                client = client,
                configId = RequestDetail.CONFIG,
                extraInputs = mapOf(RequestDetail.INPUT_ID to ids.joinToString(",")),
            ),
            label = if (ids.size == 1) {
                "Αίτημα ΑΑΔΕ — περιεχόμενο και συνημμένα"
            } else {
                "Αιτήματα ΑΑΔΕ — περιεχόμενο και συνημμένα (${ids.size})"
            },
            producesDocuments = false,
        )
    }

    /**
     * Κατεβάζει τα αιτήματα [ids] και **σημειώνει** ότι αποθηκεύτηκαν. Το
     * περιεχόμενο διαβάζεται μετά από τον δίσκο ([RequestsStore.detail]).
     *
     * `saved` είναι όσα έχουν πράγματι αρχείο γραμμένο σε αυτή την εκτέλεση —
     * όχι όσα ζητήθηκαν.
     */
    suspend fun fetchDetails(
        filesDir: File,
        fetch: FetchController,
        repository: ClientRepository,
        client: ClientEntity,
        ids: List<String>,
        ownsBatch: Boolean = false,
    ): Result {
        val plan = detailPlan(repository, client, ids)
            ?: return Result(Outcome.MISSING, message = "Λείπουν οι κωδικοί TAXISnet του πελάτη.")
        val since = System.currentTimeMillis()
        val items = DebtsRefresh.run(fetch, listOf(plan), ownsBatch)
            ?: return Result(Outcome.BUSY, message = DebtsRefresh.BUSY)
        val item = items.firstOrNull()
        if (item == null || item.status != FetchController.Status.OK) {
            val rejected = item?.reason == REASON_BAD_CREDENTIALS
            return Result(
                if (rejected) Outcome.REJECTED else Outcome.FAILED,
                message = item?.detail.orEmpty().ifBlank { "Η λήψη του αιτήματος δεν ολοκληρώθηκε." },
            )
        }
        val saved = withContext(Dispatchers.IO) { RequestsStore.markDetailed(filesDir, client.afm, ids, since) }
        return Result(Outcome.OK, saved = saved)
    }

    fun describe(changes: List<AadeRequests.Change>): String {
        val answered = changes.count { it.kind == AadeRequests.Kind.ANSWERED }
        val moved = changes.size - answered
        return when {
            changes.isEmpty() -> "Ενημερώθηκε — καμία αλλαγή από την προηγούμενη φορά."
            else -> buildString {
                append("Ενημερώθηκε — ")
                val parts = ArrayList<String>()
                if (answered > 0) parts += if (answered == 1) "1 νέα απάντηση" else "$answered νέες απαντήσεις"
                if (moved > 0) parts += if (moved == 1) "1 αλλαγή κατάστασης" else "$moved αλλαγές κατάστασης"
                append(parts.joinToString(" · ")).append('.')
            }
        }
    }
}
