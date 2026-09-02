package gr.scanmydata.taxcenter.engine

import android.content.Context
import android.view.ViewGroup
import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.db.ClientEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Η ουρά λήψης: ζει όσο η εφαρμογή, όχι όσο η οθόνη.
 *
 * Γιατί εδώ και όχι σε ViewModel: μια παρτίδα κρατάει δεκάδες λεπτά και ο
 * λογιστής θα φύγει από την οθόνη — να δει έναν πελάτη, να απαντήσει σε ένα
 * μήνυμα. Αν η ουρά ζούσε στη σύνθεση, θα ακυρωνόταν και θα άφηνε τις συνεδρίες
 * GSIS μισάνοιχτες.
 *
 * Δύο πράγματα κληρονομούνται από τον [ProcessRunner] και δεν αλλάζουν εδώ:
 * **αυστηρά σειριακή** εκτέλεση (GSIS `OAM-6`) και **μια αποτυχία δεν σταματά
 * την παρτίδα**.
 */
class FetchController(
    private val context: Context,
    private val runner: ProcessRunner,
    private val repository: ClientRepository,
    private val assets: EngineAssets,
) {

    enum class Status { PENDING, RUNNING, OK, FAILED, CANCELLED }

    data class Item(
        val afm: String,
        val clientName: String,
        val configId: String,
        val configTitle: String,
        val status: Status = Status.PENDING,
        val detail: String = "",
        val fileCount: Int = 0,
    ) {
        val key: String get() = "$afm/$configId"
    }

    data class State(
        val running: Boolean = false,
        val items: List<Item> = emptyList(),
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        /** Αληθές όσο τρέχει βήμα που χρειάζεται πραγματικό browser. */
        val browserActive: Boolean = false,
    ) {
        val done: Int get() = items.count { it.status != Status.PENDING && it.status != Status.RUNNING }
        val failed: Int get() = items.count { it.status == Status.FAILED }
        val total: Int get() = items.size
        val idle: Boolean get() = !running && items.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Το ViewGroup όπου μπαίνει το ορατό WebView, όταν η οθόνη λήψης είναι
     * ανοιχτή. Διαβάζεται τη στιγμή που το χρειάζεται ο browser, όχι μία φορά
     * στην αρχή — έτσι ο χρήστης μπορεί να επιστρέψει στην οθόνη ενώ τρέχει η
     * παρτίδα και να δει τη σελίδα για να λύσει OTP.
     */
    @Volatile
    var browserContainer: ViewGroup? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Κρατιέται για την «επανάληψη αποτυχιών»: τα items είναι index-aligned. */
    private var lastJobs: List<ProcessRunner.Job> = emptyList()

    /** Η διαδρομή εξόδου του βήματος που τρέχει αυτή τη στιγμή. */
    @Volatile
    private var currentOutDir: File = context.filesDir

    fun start(jobs: List<ProcessRunner.Job>) {
        if (_state.value.running || jobs.isEmpty()) return

        lastJobs = jobs
        val titles = assets.catalog().associate { it.id to it.title }
        _state.value = State(
            running = true,
            startedAt = System.currentTimeMillis(),
            items = jobs.map {
                Item(
                    afm = it.client.afm,
                    clientName = it.client.displayName,
                    configId = it.configId,
                    configTitle = titles[it.configId].orEmpty().ifBlank { it.configId },
                )
            },
        )

        job = scope.launch {
            val browser = WebViewBrowserPage(
                context = context,
                assets = assets,
                downloadRoot = { currentOutDir },
                container = { browserContainer },
                logSink = { },
            )
            val browserConfigs = assets.catalog().filter { it.needsBrowser }.map { it.id }.toSet()
            try {
                jobs.forEachIndexed { index, item ->
                    currentOutDir = runner.outputDir(item)
                    val needsBrowser = item.configId in browserConfigs
                    mark(index, Status.RUNNING, "")
                    _state.value = _state.value.copy(browserActive = needsBrowser)
                    notifyProgress(index)

                    // Ένα job τη φορά, ώστε ο φάκελος εξόδου του browser να
                    // αντιστοιχεί πάντα στον πελάτη που τρέχει.
                    val outcome = runner.run(
                        jobs = listOf(item),
                        browserHost = if (needsBrowser) browser else null,
                    ).first()

                    applySideEffects(item, outcome)
                    mark(
                        index = index,
                        status = if (outcome.ok) Status.OK else Status.FAILED,
                        detail = if (outcome.ok) "" else outcome.reason,
                        fileCount = outcome.files.count { it.endsWith(".pdf", ignoreCase = true) },
                    )
                }
            } finally {
                browser.shutdown()
                _state.value = _state.value.copy(
                    running = false,
                    finishedAt = System.currentTimeMillis(),
                    browserActive = false,
                    items = _state.value.items.map {
                        if (it.status == Status.PENDING || it.status == Status.RUNNING) {
                            it.copy(status = Status.CANCELLED, detail = "διακόπηκε")
                        } else {
                            it
                        }
                    },
                )
                FetchService.stop(context)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Ξανατρέχει **μόνο** όσα απέτυχαν ή διακόπηκαν.
     *
     * Είναι η συνηθισμένη περίπτωση: σε παρτίδα 40 πελατών θα πέσουν δύο-τρεις
     * σε timeout της πύλης. Το να ξανακατέβουν τα υπόλοιπα 37 είναι σπατάλη
     * χρόνου και, χειρότερα, άλλες 37 συνεδρίες στο GSIS.
     */
    fun retryFailed() {
        if (_state.value.running) return
        val retry = _state.value.items
            .mapIndexedNotNull { index, item ->
                if (item.status == Status.FAILED || item.status == Status.CANCELLED) {
                    lastJobs.getOrNull(index)
                } else {
                    null
                }
            }
        if (retry.isNotEmpty()) start(retry)
    }

    /** Καθαρίζει τη λίστα μετά το τέλος, ώστε η οθόνη να ξαναρχίζει καθαρή. */
    fun clear() {
        if (_state.value.running) return
        _state.value = State()
    }

    // ------------------------------------------------------------ βοηθητικά

    private fun mark(index: Int, status: Status, detail: String, fileCount: Int = 0) {
        val items = _state.value.items.toMutableList()
        if (index !in items.indices) return
        items[index] = items[index].copy(status = status, detail = detail, fileCount = fileCount)
        _state.value = _state.value.copy(items = items)
    }

    private fun notifyProgress(index: Int) {
        val state = _state.value
        val item = state.items.getOrNull(index) ?: return
        FetchService.update(
            context = context,
            text = "${index + 1}/${state.total} · ${item.clientName} — ${item.configTitle}",
            done = index,
            total = state.total,
        )
    }

    /**
     * Ό,τι πρέπει να γίνει με το αποτέλεσμα πέρα από τα αρχεία.
     *
     * Σήμερα ένα: το `aade-email` δεν παράγει έγγραφο αλλά **δεδομένο** — τη
     * διεύθυνση από το Μητρώο Επικοινωνίας. Γράφεται κατευθείαν στην καρτέλα,
     * στο πεδίο `emailAade`, χωρίς να πειραχτεί η χειροκίνητη διεύθυνση.
     */
    private suspend fun applySideEffects(job: ProcessRunner.Job, outcome: ProcessRunner.Outcome) {
        if (job.configId != CONFIG_EMAIL) return
        val raw = outcome.out ?: return
        val email = try {
            JSONObject(raw).optString("email").trim()
        } catch (e: Exception) {
            ""
        }
        if (email.isBlank()) return
        repository.setEmails(job.client.id, aade = email, manual = null, preferred = null)
    }

    companion object {
        const val CONFIG_EMAIL = "aade-email"

        /** Ποιες διαδικασίες δέχονται έτος ως είσοδο — καθορίζει το πεδίο στην οθόνη. */
        fun acceptsYear(config: ConfigInfo): Boolean =
            config.inputs.any { it.key.equals("year", ignoreCase = true) }

        /** Οι πελάτες που δεν έχουν τα διαπιστευτήρια που ζητά η διαδικασία. */
        suspend fun missingCredentials(
            repository: ClientRepository,
            client: ClientEntity,
            configId: String,
        ): List<String> {
            val required = CredentialMap.requiredFields(configId)
            if (required.isEmpty()) return emptyList()
            val stored = repository.credentials(client.id)
            val amka = repository.amka(client)
            return required.filter { field ->
                if (field.name == "AMKA") amka.isBlank() else stored[field].isNullOrBlank()
            }.map { it.name }
        }
    }
}
