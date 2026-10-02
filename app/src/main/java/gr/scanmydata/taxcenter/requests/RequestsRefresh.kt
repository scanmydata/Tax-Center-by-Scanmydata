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
 * Ένα σημείο για την καρτέλα και για τον χρονοπρογραμματιστή: και οι δύο
 * πρέπει να καταλήγουν στην ίδια μνήμη ([RequestsStore.absorb]), αλλιώς ένα
 * αίτημα που είδε ο λογιστής το απόγευμα θα ξαναγινόταν «νέο» το επόμενο πρωί.
 *
 * Περνά από την ίδια ουρά με κάθε άλλη λήψη ([DebtsRefresh.run]): δύο συνεδρίες
 * GSIS μαζί κλειδώνουν τον λογαριασμό του πελάτη.
 */
object RequestsRefresh {

    /** Ο engine γράφει έτσι την απόρριψη κωδικών από το GSIS. */
    private const val REASON_BAD_CREDENTIALS = "InvalidCredentials"

    enum class Outcome { OK, BUSY, MISSING, REJECTED, FAILED }

    data class Result(
        val outcome: Outcome,
        val changes: List<AadeRequests.Change> = emptyList(),
        /** Έτοιμο κείμενο για την οθόνη. */
        val message: String = "",
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
        return Result(Outcome.OK, changes, describe(changes))
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
