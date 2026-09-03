package gr.scanmydata.taxcenter.engine

import android.content.Context
import android.view.ViewGroup
import gr.scanmydata.taxcenter.data.ClientKind
import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.mail.MailService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val mail: MailService,
    private val driveSync: gr.scanmydata.taxcenter.google.DriveSync,
) {

    /**
     * Η κατάσταση μιας γραμμής της ουράς.
     *
     * Το [EMPTY] δεν είναι αποτυχία και δεν είναι επιτυχία: η σύνδεση έγινε, η
     * πύλη απάντησε, απλώς δεν υπάρχει υποβεβλημένο έντυπο για αυτόν τον πελάτη
     * και αυτό το έτος. Μέχρι τώρα εμφανιζόταν ως «✓ 0 έντυπα», που είναι το
     * χειρότερο μήνυμα: μοιάζει με επιτυχία και αφήνει τον λογιστή να νομίζει
     * ότι στάλθηκε κάτι. Ξεχωριστή κατάσταση σημαίνει ότι φαίνεται, ότι
     * μετριέται χωριστά, και ότι η «επανάληψη αποτυχιών» **δεν** το ξαναζητά —
     * το αποτέλεσμα θα ήταν πάλι κενό.
     */
    enum class Status { PENDING, RUNNING, OK, EMPTY, FAILED, CANCELLED }

    data class Item(
        val afm: String,
        val clientName: String,
        val configId: String,
        val configTitle: String,
        val status: Status = Status.PENDING,
        val detail: String = "",
        val fileCount: Int = 0,
        /** Ποιου πελάτη είναι — για ομαδοποίηση και για άνοιγμα των εντύπων. */
        val clientId: Long = 0L,
        /**
         * Τα ονόματα των PDF που παρήγαγε αυτή η γραμμή.
         *
         * Κρατιούνται ώστε το πάτημα πάνω στη γραμμή να ανοίγει **αυτό** το
         * έντυπο. Χωρίς αυτά, η οθόνη προόδου έλεγε «✓ 2 έντυπα» και ο μόνος
         * τρόπος να τα δεις ήταν να φύγεις και να τα ψάξεις στα Έγγραφα.
         */
        val files: List<String> = emptyList(),
        /** Η λήψη πέτυχε αλλά η αυτόματη αποστολή όχι — άλλο πράγμα από αποτυχία. */
        val sendFailed: Boolean = false,
    ) {
        val key: String get() = "$afm/$configId/$configTitle"
    }

    /** Ποιο πεδίο της καρτέλας προτείνεται να αλλάξει. */
    enum class UpdateField(val label: String) {
        EMAIL_AADE("Email ΑΑΔΕ"),
        NAME("Επωνυμία / Επώνυμο"),
        FIRST_NAME("Όνομα"),
        KIND("Είδος"),
        DOY("ΔΟΥ"),
        AMKA("ΑΜΚΑ"),
        MARITAL("Οικογενειακή κατάσταση"),
    }

    data class Change(val field: UpdateField, val before: String, val after: String)

    /**
     * Σύζυγος που βρέθηκε στις σχέσεις του ETAK.
     *
     * Το Μητρώο λέει μόνο «ΕΓΓΑΜΟΣ-Η»· τον **ΑΦΜ** τον δίνει αποκλειστικά το
     * ETAK, και μόνο όταν ο σύζυγος εμφανίζεται στο Ε9 του πελάτη. Γι' αυτό η
     * αυτόματη δημιουργία καρτέλας κρέμεται από τη λήψη ΕΝΦΙΑ/Ε9 και όχι από
     * την άντληση στοιχείων.
     *
     * Δεν γράφεται τίποτα χωρίς έγκριση: μια νέα καρτέλα πελάτη είναι
     * μεγαλύτερη ενέργεια από μια αλλαγή πεδίου.
     */
    data class SpouseFind(
        val clientId: Long,
        val clientName: String,
        val spouseAfm: String,
        val lastName: String,
        val firstName: String,
        val relation: String,
        /** Υπάρχει ήδη καρτέλα με αυτό το ΑΦΜ; Τότε αρκεί σύνδεση. */
        val alreadyClient: Boolean = false,
    ) {
        val displayName: String
            get() = listOf(lastName, firstName).filter { it.isNotBlank() }
                .joinToString(" ").ifBlank { spouseAfm }
    }

    /**
     * Μια προτεινόμενη ενημέρωση καρτέλας από μαζική άντληση.
     *
     * **Δεν γράφεται αυτόματα.** Μια παρτίδα 40 πελατών που ξαναγράφει
     * ονόματα και διευθύνσεις χωρίς να τα δει κανείς είναι ακριβώς ο τρόπος να
     * χαθεί σιωπηλά μια σωστή χειροκίνητη διόρθωση. Ο λογιστής βλέπει «πριν →
     * μετά» ανά πεδίο και διαλέγει.
     */
    data class PendingUpdate(
        val clientId: Long,
        val afm: String,
        val clientName: String,
        val changes: List<Change>,
    )

    data class State(
        val running: Boolean = false,
        val items: List<Item> = emptyList(),
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        /**
         * Το WebView είναι **στην οθόνη**.
         *
         * Δεν ταυτίζεται πια με το [browserRunning]: η σελίδα δουλεύει κρυφά
         * και εμφανίζεται μόνο όταν ζητά άνθρωπο (OTP/CAPTCHA) ή όταν το ζητά
         * ρητά ο χρήστης.
         */
        val browserActive: Boolean = false,
        /** Τρέχει βήμα που χρησιμοποιεί πραγματικό browser, ορατό ή όχι. */
        val browserRunning: Boolean = false,
        /** Ενημερώσεις καρτέλας που περιμένουν έγκριση. */
        val pending: List<PendingUpdate> = emptyList(),
        /** Σύζυγοι που εντοπίστηκαν και περιμένουν έγκριση. */
        val spouses: List<SpouseFind> = emptyList(),
    ) {
        val done: Int get() = items.count { it.status != Status.PENDING && it.status != Status.RUNNING }
        val failed: Int get() = items.count { it.status == Status.FAILED }

        /** Πέτυχαν αλλά δεν βρήκαν έντυπο. Χωριστός αριθμός από τις αποτυχίες. */
        val empty: Int get() = items.count { it.status == Status.EMPTY }

        /** Ποιοι πελάτες έμειναν χωρίς έντυπο, χωρίς επαναλήψεις ονόματος. */
        val emptyClients: List<String>
            get() = items.filter { it.status == Status.EMPTY }
                .map { it.clientName }
                .distinct()
        val total: Int get() = items.size
        val idle: Boolean get() = !running && items.isEmpty()
        val pendingChanges: Int get() = pending.sumOf { it.changes.size }
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

    /**
     * Ο ενεργός browser, για να μπορεί ο χρήστης να ζητήσει να τον δει.
     *
     * Ξεχωριστό όνομα από την τοπική μεταβλητή μέσα στο `scope.launch`: εκεί
     * το `this` είναι ο CoroutineScope, όχι ο controller.
     */
    @Volatile
    private var activeBrowser: WebViewBrowserPage? = null

    /** «Εμφάνιση σελίδας» — όταν ο χρήστης θέλει να δει τι κάνει η διαδικασία. */
    fun showBrowser() {
        activeBrowser?.reveal()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Μία γραμμή της ουράς: η εργασία του engine μαζί με το όνομα που βλέπει ο
     * χρήστης. Η ετικέτα δεν προκύπτει από το config — ένα `aade-income` μπορεί
     * να είναι «Ε1 2025» ή «Ε2 2023», και ο λογιστής πρέπει να ξεχωρίζει ποιο
     * απέτυχε.
     */
    data class Plan(
        val job: ProcessRunner.Job,
        val label: String,
        val producesDocuments: Boolean = true,
    )

    /** Κρατιέται για την «επανάληψη αποτυχιών»: τα items είναι index-aligned. */
    private var lastPlans: List<Plan> = emptyList()

    /** Η διαδρομή εξόδου του βήματος που τρέχει αυτή τη στιγμή. */
    @Volatile
    private var currentOutDir: File = context.filesDir

    /**
     * @param autoSendToken όταν δοθεί, μόλις τελειώσει η παρτίδα στέλνεται σε
     *   κάθε πελάτη **ένα** email με τα έντυπα που κατέβηκαν *σε αυτή* την
     *   εκτέλεση. Ένα email ανά πελάτη, ποτέ κοινοποίηση σε τρίτο.
     */
    fun start(
        plans: List<Plan>,
        autoSendToken: String? = null,
        syncToken: String? = null,
    ) {
        if (_state.value.running || plans.isEmpty()) return

        lastPlans = plans
        val startedAt = System.currentTimeMillis()
        _state.value = State(
            running = true,
            startedAt = startedAt,
            items = plans.map {
                Item(
                    afm = it.job.client.afm,
                    clientName = it.job.client.displayName,
                    configId = it.job.configId,
                    configTitle = it.label,
                    clientId = it.job.client.id,
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
                // Η σελίδα ανακοίνωσε ότι χρειάζεται άνθρωπο. Μέχρι τότε τρέχει
                // κρυφή: ο λογιστής βλέπει την πρόοδο, όχι το ADF να φορτώνει.
                onNeedsUser = { visible ->
                    _state.value = _state.value.copy(browserActive = visible)
                },
            )
            activeBrowser = browser
            val browserConfigs = assets.catalog().filter { it.needsBrowser }.map { it.id }.toSet()
            try {
                plans.forEachIndexed { index, plan ->
                    val item = plan.job
                    currentOutDir = runner.outputDir(item)
                    val needsBrowser = item.configId in browserConfigs
                    mark(index, Status.RUNNING, "")
                    _state.value = _state.value.copy(browserRunning = needsBrowser)
                    notifyProgress(index)

                    // Ένα job τη φορά, ώστε ο φάκελος εξόδου του browser να
                    // αντιστοιχεί πάντα στον πελάτη που τρέχει.
                    val outcome = runner.run(
                        jobs = listOf(item),
                        browserHost = if (needsBrowser) browser else null,
                    ).first()

                    proposeUpdates(item, outcome)
                    val pdfs = outcome.files.count { it.endsWith(".pdf", ignoreCase = true) }
                    // Μια διαδικασία που **δεν** παράγει έγγραφα (άντληση
                    // στοιχείων, email, ΑΜΚΑ) δεν κρίνεται από τα PDF της.
                    val status = when {
                        !outcome.ok -> Status.FAILED
                        plan.producesDocuments && pdfs == 0 -> Status.EMPTY
                        else -> Status.OK
                    }
                    mark(
                        index = index,
                        status = status,
                        detail = when (status) {
                            Status.FAILED -> describe(outcome.reason)
                            Status.EMPTY -> "δεν βρέθηκε έντυπο για αυτόν τον πελάτη"
                            else -> ""
                        },
                        fileCount = pdfs,
                        files = outcome.files.filter { it.endsWith(".pdf", ignoreCase = true) },
                    )
                }
                if (autoSendToken != null) autoSend(autoSendToken, plans, startedAt)

                // Ο συγχρονισμός στο Drive γίνεται **στο τέλος** και όχι ανά
                // αρχείο: μια παρτίδα 40 πελατών θα άνοιγε 40 συνδέσεις προς
                // την Google ενώ ήδη κρατά ανοιχτή συνεδρία στο GSIS.
                if (syncToken != null && driveSync.enabled && driveSync.online()) {
                    runCatching { driveSync.syncAll(syncToken) }
                }
            } finally {
                browser.shutdown()
                activeBrowser = null
                _state.value = _state.value.copy(
                    running = false,
                    finishedAt = System.currentTimeMillis(),
                    browserActive = false,
                    browserRunning = false,
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

    /**
     * Στέλνει ό,τι κατέβηκε **σε αυτή** την παρτίδα.
     *
     * Το φίλτρο `createdAt >= startedAt` είναι το κρίσιμο σημείο: χωρίς αυτό,
     * μια λήψη του Ε1 θα ξανάστελνε και τα περσινά έντυπα που ήδη έχει ο
     * πελάτης. Ο λογιστής ζήτησε «κατέβασε και στείλε», όχι «στείλε ό,τι έχεις».
     *
     * Οι αποτυχίες αποστολής δεν ρίχνουν την παρτίδα — καταγράφονται στο
     * ημερολόγιο και εμφανίζονται στη λίστα.
     */
    private suspend fun autoSend(accessToken: String, plans: List<Plan>, startedAt: Long) {
        val clients = plans.filter { it.producesDocuments }
            .map { it.job.client }
            .distinctBy { it.id }
            .filter { it.id != 0L }

        var index = 0
        for (client in clients) {
            index++
            val fresh = repository.byId(client.id) ?: client
            val documents = mail.documentsSince(client.id, startedAt)
            if (documents.isEmpty()) continue
            FetchService.update(
                context = context,
                text = "Αποστολή $index/${clients.size} · ${fresh.displayName}",
                done = index,
                total = clients.size,
            )
            // **Ρητά εκτός κύριου νήματος.** Ο scope του controller είναι
            // `Dispatchers.Main.immediate` — σωστό για την κατάσταση της οθόνης,
            // θανάσιμο για κλήση δικτύου: το Gmail API χτυπιέται σύγχρονα με
            // OkHttp και το Android ρίχνει `NetworkOnMainThreadException`.
            // Παντού αλλού η αποστολή καλείται μέσα από `withContext(IO)`· εδώ
            // έλειπε, και έσκαγε ακριβώς στη «λήψη και αποστολή».
            val detail = withContext(Dispatchers.IO) {
                runCatching { mail.sendDocuments(accessToken, fresh, documents) }
            }
            val message = when {
                detail.isFailure -> detail.exceptionOrNull()?.message.orEmpty()
                detail.getOrNull()?.failed == true -> detail.getOrNull()?.error.orEmpty()
                else -> ""
            }
            markSend(client.id, documents.size, message)
        }
    }

    /** Σημειώνει το αποτέλεσμα της αυτόματης αποστολής στις γραμμές του πελάτη. */
    private fun markSend(clientId: Long, count: Int, error: String) {
        val plansById = lastPlans.withIndex().filter { it.value.job.client.id == clientId }
        val items = _state.value.items.toMutableList()
        for ((index, _) in plansById) {
            val current = items.getOrNull(index) ?: continue
            if (current.status != Status.OK) continue
            items[index] = current.copy(
                detail = if (error.isBlank()) "στάλθηκαν $count έντυπα" else "αποστολή απέτυχε: $error",
                sendFailed = error.isNotBlank(),
            )
        }
        _state.value = _state.value.copy(items = items)
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
                    lastPlans.getOrNull(index)
                } else {
                    null
                }
            }
        if (retry.isNotEmpty()) start(retry)
    }

    /**
     * Μία μεμονωμένη αναζήτηση email στο Μητρώο Επικοινωνίας, εκτός ουράς.
     *
     * Χρησιμοποιείται από την καρτέλα πελάτη, όπου ο λογιστής θέλει απάντηση
     * τώρα και όχι παρτίδα. Αν τρέχει ήδη παρτίδα, **αρνείται**: δύο ταυτόχρονες
     * συνεδρίες στο GSIS κλειδώνουν τον λογαριασμό (`OAM-6`), και το να χαλάσει
     * μια παρτίδα 40 πελατών για μία διεύθυνση δεν αξίζει.
     *
     * Επιστρέφει τη διεύθυνση, ή κείμενο σφάλματος με πρόθεμα `!`.
     *
     * **Δεν γράφει στη βάση**: γεμίζει το πεδίο της φόρμας και αποθηκεύει ο
     * χρήστης. Έτσι η μεμονωμένη άντληση συμπεριφέρεται όπως και η μαζική —
     * τίποτα δεν αλλάζει στην καρτέλα χωρίς να το δει κάποιος.
     */
    suspend fun lookupEmail(client: ClientEntity): String {
        if (_state.value.running) return "!Τρέχει ήδη παρτίδα λήψης — δοκίμασε όταν τελειώσει."
        val job = ProcessRunner.Job(client = client, configId = CONFIG_EMAIL)
        val outcome = runner.run(listOf(job)).first()
        if (!outcome.ok) return "!${describe(outcome.reason)}"
        val email = try {
            JSONObject(outcome.out.orEmpty()).optString("email").trim()
        } catch (e: Exception) {
            ""
        }
        return email.ifBlank { "!Δεν βρέθηκε διεύθυνση στο Μητρώο." }
    }

    /**
     * Τα στοιχεία ταυτότητας του πελάτη από το Μητρώο ΑΑΔΕ.
     *
     * Είναι η «άντληση από TAXIS» της καρτέλας: ο λογιστής βάζει τους κωδικούς
     * του πελάτη και γεμίζουν ονοματεπώνυμο, ΑΦΜ, ΔΟΥ και είδος υπόχρεου.
     *
     * Τα [user]/[pass] δίνονται από τη φόρμα και **δεν** διαβάζονται από τη
     * βάση: στη νέα καρτέλα δεν υπάρχει ακόμη τίποτα αποθηκευμένο. Το
     * αποτέλεσμα επιστρέφεται στην οθόνη και δεν γράφεται πουθενά — ο χρήστης
     * βλέπει τι ήρθε πριν αποφασίσει να το κρατήσει.
     */
    suspend fun lookupProfile(user: String, pass: String, afm: String = ""): Profile {
        if (_state.value.running) {
            return Profile(error = "Τρέχει ήδη παρτίδα λήψης — δοκίμασε όταν τελειώσει.")
        }
        if (user.isBlank() || pass.isBlank()) {
            return Profile(error = "Συμπλήρωσε πρώτα όνομα χρήστη και συνθηματικό TAXISnet.")
        }
        val job = ProcessRunner.Job(
            client = ClientEntity(afm = afm),
            configId = CONFIG_PROFILE,
            // Κενό `vat` = «ο ΑΦΜ του λογαριασμού». Στη νέα καρτέλα ο ΑΦΜ είναι
            // ακριβώς αυτό που ψάχνουμε, οπότε δεν έχουμε τι να στείλουμε.
            extraInputs = mapOf("vat" to afm),
            credentialsOverride = user.trim() to pass,
        )
        val outcome = runner.run(listOf(job)).first()
        if (!outcome.ok) return Profile(error = describe(outcome.reason))
        val profile = try {
            val json = JSONObject(outcome.out.orEmpty())
            Profile(
                afm = json.optString("afm").trim(),
                name = json.optString("name").trim(),
                firstName = json.optString("firstName").trim(),
                kind = json.optString("kind").trim(),
                doy = json.optString("doy").trim(),
                email = json.optString("email").trim(),
                maritalStatus = json.optString("maritalStatus").trim(),
                active = json.optBoolean("active", true),
            )
        } catch (e: Exception) {
            return Profile(error = "Η απάντηση του Μητρώου δεν διαβάστηκε.")
        }

        // Ιδιώτης ή ατομική σημαίνει ότι υπάρχει ΑΜΚΑ — οπότε το φέρνουμε
        // αμέσως, χωρίς δεύτερο πάτημα. Είναι δεύτερη σύνδεση σε **άλλη** πύλη
        // (το amka.gr μπαίνει με GSIS OAuth2, όχι με το OAM της ΑΑΔΕ), γι' αυτό
        // δεν έρχεται μαζί με τα υπόλοιπα.
        //
        // Αν αποτύχει, **δεν** χαλάει η άντληση: τα στοιχεία μητρώου έχουν ήδη
        // βρεθεί και είναι το κύριο ζητούμενο. Μπαίνει σημείωμα και το κουμπί
        // δίπλα στο πεδίο μένει για δεύτερη προσπάθεια.
        if (!ClientKind.hasAmka(profile.kind)) return profile
        val amka = lookupAmka(user, pass)
        return if (amka.startsWith("!")) {
            profile.copy(amkaNote = amka.removePrefix("!"))
        } else {
            profile.copy(amka = amka)
        }
    }

    /**
     * Ο ΑΜΚΑ από το MyAMKA.
     *
     * Χωριστή διαδικασία και **χωριστή πύλη** από το Μητρώο: το `amka.gr`
     * μπαίνει με GSIS OAuth2, όχι με το OAM της ΑΑΔΕ. Γι' αυτό δεν έρχεται
     * μαζί με τα υπόλοιπα στοιχεία — είναι δεύτερη σύνδεση.
     *
     * Έχει νόημα μόνο για ιδιώτη ή ατομική επιχείρηση· ένα νομικό πρόσωπο δεν
     * έχει ΑΜΚΑ, και η οθόνη δεν δείχνει καν το κουμπί.
     */
    suspend fun lookupAmka(user: String, pass: String): String {
        if (_state.value.running) return "!Τρέχει ήδη παρτίδα λήψης — δοκίμασε όταν τελειώσει."
        if (user.isBlank() || pass.isBlank()) {
            return "!Συμπλήρωσε πρώτα όνομα χρήστη και συνθηματικό TAXISnet."
        }
        val job = ProcessRunner.Job(
            client = ClientEntity(afm = ""),
            configId = CONFIG_AMKA,
            credentialsOverride = user.trim() to pass,
        )
        val outcome = runner.run(listOf(job)).first()
        if (!outcome.ok) return "!${describe(outcome.reason)}"
        val amka = try {
            JSONObject(outcome.out.orEmpty()).optString("amka").trim()
        } catch (e: Exception) {
            ""
        }
        return amka.ifBlank { "!Δεν βρέθηκε ΑΜΚΑ στον λογαριασμό." }
    }

    /** Τα πεδία που γεμίζει η άντληση. Κενό [error] σημαίνει επιτυχία. */
    data class Profile(
        val afm: String = "",
        val name: String = "",
        val firstName: String = "",
        val kind: String = "",
        val doy: String = "",
        val email: String = "",
        val amka: String = "",
        /** «ΕΓΓΑΜΟΣ-Η», «ΑΓΑΜΟΣ-Η»… όπως το γράφει το Μητρώο. */
        val maritalStatus: String = "",
        /** Γιατί δεν ήρθε ο ΑΜΚΑ, όταν έπρεπε να υπάρχει. Δεν είναι σφάλμα. */
        val amkaNote: String = "",
        val active: Boolean = true,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isBlank()
    }

    /**
     * Τα βήματα μιας **μαζικής ενημέρωσης καρτελών**.
     *
     * Είναι η ίδια δουλειά με το «Άντληση στοιχείων» της καρτέλας, για πολλούς
     * πελάτες: ονοματεπώνυμο, ΔΟΥ, είδος, οικογενειακή κατάσταση, email και —
     * όπου έχει νόημα — ΑΜΚΑ. Τίποτα δεν γράφεται χωρίς έγκριση· τα
     * αποτελέσματα περνούν από την ίδια οθόνη «πριν → μετά».
     *
     * Παραλείπονται σιωπηλά όσοι δεν έχουν κωδικούς TAXISnet: η εναλλακτική
     * θα ήταν δεκάδες βέβαιες αποτυχίες σύνδεσης.
     */
    suspend fun refreshPlans(clients: List<ClientEntity>): List<Plan> {
        val plans = ArrayList<Plan>()
        for (client in clients) {
            for (id in REFRESH_ITEMS) {
                val item = DocumentCatalog.byId(id) ?: continue
                if (!item.matches(client.kind)) continue
                if (missingCredentials(repository, client, item.configId).isNotEmpty()) continue
                val inputs = HashMap(item.inputs)
                // Μόνο η άντληση μητρώου δέχεται ΑΦΜ-στόχο· οι άλλες δύο
                // δουλεύουν πάνω στον λογαριασμό που συνδέθηκε.
                if (item.configId == CONFIG_PROFILE) inputs["vat"] = client.afm
                plans += Plan(
                    job = ProcessRunner.Job(
                        client = client,
                        configId = item.configId,
                        extraInputs = inputs,
                    ),
                    label = item.label,
                    producesDocuments = false,
                )
            }
        }
        return plans
    }

    /** Δέχεται μια πρόταση συζύγου: φτιάχνει ή απλώς συνδέει την καρτέλα. */
    suspend fun applySpouse(find: SpouseFind) {
        repository.createSpouse(
            clientId = find.clientId,
            spouseAfm = find.spouseAfm,
            lastName = find.lastName,
            firstName = find.firstName,
        )
        _state.value = _state.value.copy(
            spouses = _state.value.spouses.filterNot { it.spouseAfm == find.spouseAfm && it.clientId == find.clientId },
        )
    }

    fun discardSpouse(find: SpouseFind) {
        _state.value = _state.value.copy(
            spouses = _state.value.spouses.filterNot { it.spouseAfm == find.spouseAfm && it.clientId == find.clientId },
        )
    }

    /** Καθαρίζει τη λίστα μετά το τέλος, ώστε η οθόνη να ξαναρχίζει καθαρή. */
    fun clear() {
        if (_state.value.running) return
        _state.value = State()
    }

    // ------------------------------------------------------------ βοηθητικά

    private fun mark(
        index: Int,
        status: Status,
        detail: String,
        fileCount: Int = 0,
        files: List<String> = emptyList(),
    ) {
        val items = _state.value.items.toMutableList()
        if (index !in items.indices) return
        items[index] = items[index].copy(
            status = status,
            detail = detail,
            fileCount = fileCount,
            files = files,
        )
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
     * Μετατρέπει ένα αποτέλεσμα που φέρνει **δεδομένα** (και όχι έγγραφα) σε
     * προτεινόμενη ενημέρωση καρτέλας.
     *
     * Τρία configs το κάνουν: το `aade-email` φέρνει τη διεύθυνση επικοινωνίας,
     * το `aade-profile` ονοματεπώνυμο/ΔΟΥ/είδος, το `amka-retrieve` τον ΑΜΚΑ.
     * Καμία τιμή δεν γράφεται εδώ — μπαίνει στην ουρά έγκρισης.
     *
     * Δύο κανόνες, και οι δύο σκόπιμοι:
     *  * **κενό δεν προτείνεται.** Αν η πύλη δεν επέστρεψε τιμή, η αποθηκευμένη
     *    μένει· «δεν βρήκα» δεν σημαίνει «σβήσ' το».
     *  * **ίδια τιμή δεν προτείνεται.** Η λίστα έγκρισης πρέπει να δείχνει μόνο
     *    ό,τι πράγματι αλλάζει, αλλιώς κανείς δεν τη διαβάζει.
     */
    private suspend fun proposeUpdates(job: ProcessRunner.Job, outcome: ProcessRunner.Outcome) {
        if (!outcome.ok || job.client.id == 0L) return
        val json = try {
            JSONObject(outcome.out ?: return)
        } catch (e: Exception) {
            return
        }

        val client = job.client
        val changes = ArrayList<Change>()
        fun propose(field: UpdateField, before: String, after: String) {
            val value = after.trim()
            if (value.isBlank() || value == before.trim()) return
            changes += Change(field, before, value)
        }

        when (job.configId) {
            CONFIG_EMAIL -> propose(UpdateField.EMAIL_AADE, client.emailAade, json.optString("email"))

            CONFIG_PROFILE -> {
                propose(UpdateField.EMAIL_AADE, client.emailAade, json.optString("email"))
                propose(UpdateField.NAME, client.name, json.optString("name"))
                propose(UpdateField.FIRST_NAME, client.firstName, json.optString("firstName"))
                propose(UpdateField.KIND, client.kind, json.optString("kind"))
                propose(UpdateField.DOY, client.doy, json.optString("doy"))
                propose(UpdateField.MARITAL, client.maritalStatus, json.optString("maritalStatus"))
            }

            CONFIG_AMKA -> propose(UpdateField.AMKA, repository.amka(client), json.optString("amka"))
            CONFIG_ENFIA -> noteSpouses(job, json)
        }

        if (changes.isEmpty()) return
        val update = PendingUpdate(client.id, client.afm, client.displayName, changes)
        _state.value = _state.value.copy(
            pending = _state.value.pending.filterNot { it.clientId == client.id } + update,
        )
    }

    /**
     * Γράφει τις εγκεκριμένες ενημερώσεις.
     *
     * Το [approved] είναι τα κλειδιά `"<clientId>/<UpdateField>"` που άφησε
     * τσεκαρισμένα ο χρήστης — ό,τι δεν είναι μέσα, αγνοείται και χάνεται.
     */
    suspend fun applyPending(approved: Set<String>) {
        for (update in _state.value.pending) {
            val taken = update.changes.filter { "${update.clientId}/${it.field.name}" in approved }
            if (taken.isEmpty()) continue
            val value = taken.associate { it.field to it.after }
            repository.applyLookup(
                clientId = update.clientId,
                name = value[UpdateField.NAME],
                firstName = value[UpdateField.FIRST_NAME],
                kind = value[UpdateField.KIND],
                doy = value[UpdateField.DOY],
                amka = value[UpdateField.AMKA],
                emailAade = value[UpdateField.EMAIL_AADE],
                maritalStatus = value[UpdateField.MARITAL],
            )
        }
        _state.value = _state.value.copy(pending = emptyList())
    }

    /** Απόρριψη όλων των προτάσεων χωρίς εγγραφή. */
    fun discardPending() {
        _state.value = _state.value.copy(pending = emptyList())
    }

    companion object {
        /** Ποιες διαδικασίες τρέχει η μαζική ενημέρωση καρτελών. */
        private val REFRESH_ITEMS = listOf("profile", "email", "amka")

        const val CONFIG_ENFIA = "aade-enfia"
        const val CONFIG_EMAIL = "aade-email"
        const val CONFIG_PROFILE = "aade-profile"
        const val CONFIG_AMKA = "amka-retrieve"

        /**
         * Οι λόγοι αποτυχίας του engine είναι αγγλικά αναγνωριστικά, γραμμένα
         * για τον runner. Στην καρτέλα πελάτη τα βλέπει λογιστής που μόλις
         * πληκτρολόγησε κωδικούς — και το `InvalidCredentials` πρέπει να λέει
         * «λάθος κωδικοί», όχι να μοιάζει με βλάβη της εφαρμογής.
         */
        fun describe(reason: String): String = when (reason) {
            "InvalidCredentials" ->
                "Λάθος όνομα χρήστη ή συνθηματικό TAXISnet. Πρόσεξε: το GSIS " +
                    "κλειδώνει τον λογαριασμό μετά από αλλεπάλληλες αποτυχίες."
            "NoAfm" -> "Ο λογαριασμός δεν επέστρεψε ΑΦΜ."
            "NoRegistry" -> "Ο ΑΦΜ δεν έχει μητρώο φυσικού προσώπου ούτε επιχείρησης."
            "NoEmail" -> "Δεν βρέθηκε διεύθυνση στο Μητρώο Επικοινωνίας."
            "NotFound" -> "Δεν βρέθηκε εγγραφή."
            "NotLoggedIn" -> "Η σύνδεση δεν ολοκληρώθηκε — δοκίμασε ξανά."
            else -> reason
        }

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
