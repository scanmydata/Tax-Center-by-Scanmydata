package gr.scanmydata.taxcenter.debts

import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.engine.DocumentCatalog
import gr.scanmydata.taxcenter.engine.FetchController
import gr.scanmydata.taxcenter.engine.ProcessRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Η **ενημέρωση της καρτέλας οφειλών**: ίδιες διαδικασίες με τη λήψη, χωρίς τα
 * έντυπα.
 *
 * ## Γιατί «μόνο δεδομένα»
 *
 * Η καρτέλα ενημερώνεται συχνά — με ένα πάτημα, ή μόνη της κάθε πρωί. Αν κάθε
 * ενημέρωση κατέβαζε και τα PDF, η συσκευή θα γέμιζε Ταυτότητες Οφειλής που
 * διαφέρουν κατά λίγα λεπτά του ευρώ (το ποσό είναι μέρος του ονόματος του
 * αρχείου), και τα Έγγραφα του πελάτη θα γίνονταν άχρηστα. Τα PDF κατεβαίνουν
 * όταν τα ζητήσει κάποιος, από τη Λήψη εντύπων.
 *
 * Κέρδος και στον χρόνο: στην ΑΑΔΕ η ενημέρωση παίρνει δευτερόλεπτα, γιατί
 * λείπει ένα αίτημα εκτύπωσης ανά οφειλή.
 *
 * ## Μία ουρά, πάντα
 *
 * Όλα περνούν από το [FetchController]. Δεν είναι ευκολία: είναι ο μόνος
 * τρόπος να μη συμπέσει μια αυτόματη ενημέρωση με μια λήψη που έβαλε ο
 * λογιστής — δύο συνεδρίες GSIS μαζί κλειδώνουν τον λογαριασμό του πελάτη.
 */
object DebtsRefresh {

    /**
     * Σημάδι στα `extraInputs`: η εργασία θέλει δεδομένα, όχι έντυπα. Το
     * βλέπει το [ProcessRunner] και δεν συνθέτει PDF από το αποτέλεσμα.
     */
    const val DATA_ONLY = "dataOnly"

    /**
     * Οι εργασίες που ενημερώνουν την καρτέλα ενός πελάτη. Κενή λίστα σημαίνει
     * ότι λείπουν οι κωδικοί — δεν ανοίγουμε σύνδεση που ξέρουμε ότι θα αποτύχει.
     *
     * Το ΚΕΑΟ μπαίνει μόνο όπου έχει νόημα: θέλει ΑΜΚΑ, άρα φυσικό πρόσωπο.
     */
    suspend fun plans(repository: ClientRepository, client: ClientEntity): List<FetchController.Plan> {
        val out = ArrayList<FetchController.Plan>()
        if (FetchController.missingCredentials(repository, client, DebtsStore.CONFIG_AADE).isEmpty()) {
            out += FetchController.Plan(
                job = ProcessRunner.Job(
                    client = client,
                    configId = DebtsStore.CONFIG_AADE,
                    extraInputs = mapOf("doseis" to "ναι", "pdf" to "όχι", DATA_ONLY to "1"),
                ),
                label = "Οφειλές ΑΑΔΕ — ενημέρωση καρτέλας",
                producesDocuments = false,
            )
        }
        val keao = DocumentCatalog.ALL.firstOrNull { it.configId == DebtsStore.CONFIG_KEAO }
        if (keao != null && keao.matches(client.kind) &&
            FetchController.missingCredentials(repository, client, DebtsStore.CONFIG_KEAO).isEmpty()
        ) {
            out += FetchController.Plan(
                job = ProcessRunner.Job(
                    client = client,
                    configId = DebtsStore.CONFIG_KEAO,
                    extraInputs = mapOf(DATA_ONLY to "1"),
                ),
                label = "Οφειλές ΚΕΑΟ — ενημέρωση καρτέλας",
                producesDocuments = false,
            )
        }
        return out
    }

    /**
     * Τρέχει τις [plans] και περιμένει να τελειώσουν.
     *
     * Επιστρέφει `null` όταν η ουρά είναι **πιασμένη** — τρέχει άλλη λήψη, ή
     * υπάρχουν ενημερώσεις καρτέλας που περιμένουν έγκριση και θα χάνονταν αν
     * ξεκινούσε νέα παρτίδα από πάνω τους.
     *
     * @param ownsBatch όταν ο καλών ακυρωθεί, ακυρώνεται και η παρτίδα. Το
     *   θέλει ο χρονοπρογραμματιστής, που έχει όριο χρόνου από το σύστημα. Η
     *   οθόνη **δεν** το θέλει: αν ο χρήστης αλλάξει καρτέλα, η ενημέρωση
     *   συνεχίζει, όπως κάθε άλλη λήψη.
     */
    suspend fun run(
        fetch: FetchController,
        plans: List<FetchController.Plan>,
        ownsBatch: Boolean = false,
    ): List<FetchController.Item>? {
        if (plans.isEmpty()) return emptyList()
        // Στο κύριο νήμα: εκεί ξεκινούν και οι λήψεις του χρήστη, οπότε ο
        // έλεγχος «είναι ελεύθερη;» και η εκκίνηση δεν χωρίζονται από τίποτα.
        return withContext<List<FetchController.Item>?>(Dispatchers.Main) {
            val now = fetch.state.value
            if (now.running || now.pending.isNotEmpty() || now.spouses.isNotEmpty()) {
                return@withContext null
            }
            fetch.start(plans)
            try {
                val end = fetch.state.first { !it.running }
                // Η σύνοψη δεν μένει στην οθόνη λήψης: ο λογιστής που θα την
                // ανοίξει μετά περιμένει να διαλέξει έντυπα, όχι να δει τα
                // αποτελέσματα μιας ενημέρωσης που έτρεξε αλλού.
                fetch.clear()
                end.items
            } catch (e: CancellationException) {
                if (ownsBatch) fetch.cancel()
                throw e
            }
        }
    }

    /** Τι λέμε στον χρήστη όταν τελειώσει — μία γραμμή ανά πύλη που δεν πήγε καλά. */
    fun describe(items: List<FetchController.Item>?): String = when {
        items == null -> "Τρέχει ήδη λήψη, ή περιμένουν ενημερώσεις για έγκριση στη " +
            "Λήψη εντύπων. Δοκίμασε όταν τελειώσει."
        items.isEmpty() -> "Λείπουν οι κωδικοί TAXISnet του πελάτη."
        items.all { it.status == FetchController.Status.OK } -> ""
        else -> items.filter { it.status != FetchController.Status.OK }.joinToString("\n") { item ->
            source(item.configId) + ": " + item.detail.ifBlank { "δεν ολοκληρώθηκε" }
        }
    }

    fun source(configId: String): String = when (configId) {
        DebtsStore.CONFIG_AADE -> "ΑΑΔΕ"
        DebtsStore.CONFIG_KEAO -> "ΚΕΑΟ"
        else -> configId
    }
}
