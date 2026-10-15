package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.engine.FileBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Τα αιτήματα ενός πελάτη στον δίσκο, και η **μνήμη** του τι έχουμε ήδη δει.
 *
 * Δύο φάκελοι στον ιδιωτικό χώρο της λήψης, που σβήνονται μαζί με τον πελάτη:
 *
 *  * `runs/<ΑΦΜ>/easynotify/` — η **λίστα**:
 *     * `EASYNOTIFY_<ΑΦΜ>.json`, που το γράφει το config σε κάθε έλεγχο, ολόκληρο,
 *     * `AITIMATA_state.json`, που το γράφουμε εμείς — βλ. [State].
 *  * `runs/<ΑΦΜ>/aade-request/` — κάθε αίτημα **ολόκληρο**: `AITIMA_<id>.json`
 *    και τα συνημμένα του, `AITIMA_<id>_…`.
 *
 * Ό,τι κατέβει **μένει**. Το άνοιγμα ενός αιτήματος διαβάζει τον δίσκο και δεν
 * ανοίγει ποτέ σύνδεση· σύνδεση γίνεται μόνο στον έλεγχο, ή όταν το ζητήσει ο
 * χρήστης. Η πρώτη έκδοση κατέβαζε το αίτημα την ώρα που άνοιγε, δηλαδή μία
 * συνεδρία GSIS για κάθε αίτημα που ήθελε κανείς απλώς να διαβάσει.
 *
 * Το αρχείο κατάστασης είναι αυτό που κάνει την παρακολούθηση δυνατή: το config
 * δεν θυμάται τίποτα, οπότε χωρίς αυτό κάθε πρωί όλα τα αιτήματα θα ήταν «νέα» ή
 * κανένα. Κρατά αποτυπώματα και αριθμούς αιτημάτων — όχι το κείμενο.
 *
 * Διαβάζει και γράφει δίσκο — όχι από το κύριο νήμα.
 */
object RequestsStore {

    private const val PREFIX = "EASYNOTIFY_"
    private const val STATE = "AITIMATA_state.json"

    /**
     * Ό,τι θυμόμαστε για τα αιτήματα ενός πελάτη.
     *
     * @param known `id -> αποτύπωμα` της τελευταίας λίστας. `null` = δεν υπάρχει
     *   ακόμη μνήμη, άρα ο επόμενος έλεγχος είναι ο πρώτος.
     * @param fresh αιτήματα με απάντηση ή αλλαγή που ο λογιστής δεν έχει ανοίξει
     * @param detailed `id -> αποτύπωμα` **τη στιγμή που κατέβηκε ολόκληρο**. Αν
     *   διαφέρει από το τωρινό, το αποθηκευμένο αίτημα είναι παλαιότερο από τη
     *   λίστα. Αποτύπωμα και όχι ημερομηνία: η σύγκριση δεν πρέπει να εξαρτάται
     *   από το ρολόι της συσκευής ή τη ζώνη ώρας της πύλης.
     */
    internal data class State(
        val known: Map<String, String>? = null,
        val fresh: Set<String> = emptySet(),
        val detailed: Map<String, String> = emptyMap(),
    )

    data class Snapshot(
        val requests: List<AadeRequests.Request> = emptyList(),
        /** Πότε γράφτηκε το JSON. `0` = δεν έχει ληφθεί ποτέ. */
        val at: Long = 0L,
        /** Αιτήματα με απάντηση ή αλλαγή που δεν έχει δει ακόμη κανείς. */
        val fresh: Set<String> = emptySet(),
        /** Μη κενό όταν η τελευταία λήψη δεν διάβασε τα αιτήματα. */
        val error: String = "",
        /** Τα αιτήματα που είναι αποθηκευμένα ολόκληρα: `id -> συνημμένα στη συσκευή`. */
        val saved: Map<String, Int> = emptyMap(),
        /** Αποθηκευμένα που άλλαξαν στην πύλη από τότε — θέλουν ανανέωση. */
        val outdated: Set<String> = emptySet(),
    ) {
        val pending: Int get() = requests.count { !it.answered }

        /** Πόσα από τα αιτήματα της λίστας ανοίγουν χωρίς σύνδεση. */
        val complete: Int get() = requests.count { it.id in saved }
    }

    fun load(filesDir: File, afm: String): Snapshot {
        if (afm.isBlank()) return Snapshot()
        val dir = dir(filesDir, afm)
        val file = newest(dir) ?: return Snapshot()
        val json = read(file)
        val requests = AadeRequests.parse(json)
        val state = state(dir)
        val have = saved(filesDir, afm)
        return Snapshot(
            requests = requests.orEmpty(),
            at = file.lastModified(),
            fresh = state.fresh,
            error = if (requests == null) AadeRequests.error(json).ifBlank { "δεν διαβάστηκαν" } else "",
            saved = have,
            outdated = requests.orEmpty()
                .filter { it.id in have && RequestDetail.outdated(state.detailed[it.id], it.stamp) }
                .map { it.id }.toSet(),
        )
    }

    /**
     * Συγκρίνει τη λήψη που μόλις τελείωσε με ό,τι ξέραμε, **και το θυμάται**.
     *
     * Καλείται μία φορά μετά από κάθε λήψη, από όποιον την ξεκίνησε — την
     * καρτέλα ή τον χρονοπρογραμματιστή. Επιστρέφει τις αλλαγές, ή `null` όταν
     * τα αιτήματα δεν διαβάστηκαν (τότε η μνήμη μένει ανέγγιχτη).
     *
     * Η πρώτη λήψη ενός πελάτη δεν έχει προηγούμενη: γράφει τη μνήμη και δεν
     * αναφέρει τίποτα — βλ. [AadeRequests.diff].
     */
    fun absorb(filesDir: File, afm: String): List<AadeRequests.Change>? {
        if (afm.isBlank()) return null
        val dir = dir(filesDir, afm)
        val file = newest(dir) ?: return null
        val requests = AadeRequests.parse(read(file)) ?: return null
        val state = state(dir)
        val changes = AadeRequests.diff(state.known, requests)
        // Ό,τι ήταν «αδιάβαστο» μένει αδιάβαστο, εφόσον το αίτημα υπάρχει ακόμη.
        val ids = requests.map { it.id }.toSet()
        save(
            dir,
            state.copy(
                known = AadeRequests.stamps(requests),
                fresh = (state.fresh + changes.map { it.request.id }).intersect(ids),
            ),
        )
        return changes
    }

    /** Ο λογιστής τα είδε. Κενό [ids] = όλα. */
    fun markSeen(filesDir: File, afm: String, ids: Set<String> = emptySet()): Set<String> {
        val dir = dir(filesDir, afm)
        val state = state(dir)
        if (state.known == null) return emptySet()
        val left = if (ids.isEmpty()) emptySet() else state.fresh - ids
        save(dir, state.copy(fresh = left))
        return left
    }

    // ------------------------------------------------------- ένα αίτημα

    /**
     * Το πλήρες αίτημα [id], αν έχει κατέβει — βλ. [RequestDetail].
     */
    fun detail(filesDir: File, afm: String, id: String): RequestDetail.Detail? {
        if (afm.isBlank() || id.isBlank()) return null
        val file = File(detailDir(filesDir, afm), RequestDetail.fileName(id))
        if (!file.isFile) return null
        val detail = RequestDetail.parse(read(file)) ?: return null
        // Το config γράφει την ώρα μέσα στο JSON· αν λείπει, μιλά το αρχείο.
        return if (detail.retrievedAt > 0L) detail else detail.copy(retrievedAt = file.lastModified())
    }

    /**
     * Ποια αιτήματα της λίστας πρέπει να κατέβουν ολόκληρα: όσα δεν έχουν
     * κατέβει ποτέ, και όσα άλλαξαν στην πύλη από τότε. Κενή λίστα σημαίνει ότι
     * **δεν χρειάζεται δεύτερη σύνδεση** — η συνηθισμένη περίπτωση.
     */
    fun wanted(filesDir: File, afm: String, limit: Int = RequestDetail.MAX_BATCH): List<String> {
        if (afm.isBlank()) return emptyList()
        val dir = dir(filesDir, afm)
        val file = newest(dir) ?: return emptyList()
        val requests = AadeRequests.parse(read(file)) ?: return emptyList()
        return RequestDetail.wanted(requests, saved(filesDir, afm).keys, state(dir).detailed, limit)
    }

    /**
     * Σημειώνει ότι τα [ids] κατέβηκαν **τώρα**, με τη λίστα όπως είναι τώρα.
     *
     * Μόνο όσα έχουν πράγματι αρχείο γραμμένο από τη [since] και μετά: ένα
     * αίτημα που η πύλη δεν έδωσε δεν πρέπει να περάσει για αποθηκευμένο.
     * Επιστρέφει πόσα σημειώθηκαν.
     */
    fun markDetailed(filesDir: File, afm: String, ids: Collection<String>, since: Long): Int {
        if (afm.isBlank() || ids.isEmpty()) return 0
        val dir = dir(filesDir, afm)
        val file = newest(dir) ?: return 0
        val stamps = AadeRequests.stamps(AadeRequests.parse(read(file)) ?: return 0)
        val folder = detailDir(filesDir, afm)
        val done = ids.filter { id ->
            val json = File(folder, RequestDetail.fileName(id))
            // Ένα δευτερόλεπτο ανοχή: κάποια συστήματα αρχείων στρογγυλεύουν την ώρα.
            json.isFile && json.lastModified() >= since - 1_000L && id in stamps
        }
        if (done.isEmpty()) return 0
        val state = state(dir)
        // Χωρίς μνήμη της λίστας δεν γράφουμε τίποτα: ένα αρχείο κατάστασης με
        // άδεια «γνωστά» θα έκανε τον επόμενο έλεγχο να δει κάθε απαντημένο
        // αίτημα ως καινούργιο.
        if (state.known == null) return 0
        save(dir, state.copy(detailed = state.detailed + done.associateWith { stamps.getValue(it) }))
        return done.size
    }

    /**
     * Το αρχείο ενός συνημμένου, ή `null` όταν δεν υπάρχει στη συσκευή.
     *
     * Το όνομα έρχεται από JSON που έγραψε ο engine, όχι από τον χρήστη — αλλά
     * ένα όνομα που θα έβγαινε από τον φάκελο δεν έχει λόγο να γίνει δεκτό.
     */
    fun attachment(filesDir: File, afm: String, saved: String): File? {
        if (afm.isBlank() || saved.isBlank() || saved != FileBridge.sanitiseSegment(saved)) return null
        if (!saved.startsWith(FileBridge.ATTACHMENT_PREFIX)) return null
        return File(detailDir(filesDir, afm), saved).takeIf { it.isFile }
    }

    /** `id -> πόσα συνημμένα έχει στη συσκευή`, για κάθε αίτημα που έχει κατέβει ολόκληρο. */
    private fun saved(filesDir: File, afm: String): Map<String, Int> {
        val files = detailDir(filesDir, afm).listFiles() ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (file in files) {
            val m = DETAIL_FILE.matchEntire(file.name) ?: continue
            val detail = RequestDetail.parse(read(file)) ?: continue
            out[m.groupValues[1]] = detail.attachments.count { it.downloaded }
        }
        return out
    }

    private val DETAIL_FILE = Regex("""AITIMA_(\d+)\.json""")

    /** Το JSON ενός αποθηκευμένου αιτήματος — υπάρχει ή όχι. */
    fun detailFile(filesDir: File, afm: String, id: String): File =
        File(detailDir(filesDir, afm), RequestDetail.fileName(id))

    private fun detailDir(filesDir: File, afm: String): File = File(
        filesDir,
        "runs/${FileBridge.sanitiseSegment(afm)}/${FileBridge.sanitiseSegment(RequestDetail.CONFIG)}",
    )

    // ------------------------------------------------------------ κατάσταση

    private fun state(dir: File): State =
        decode(runCatching { File(dir, STATE).readText(Charsets.UTF_8) }.getOrDefault(""))

    private fun save(dir: File, state: State) {
        runCatching {
            dir.mkdirs()
            File(dir, STATE).writeText(encode(state), Charsets.UTF_8)
        }
    }

    /** Ό,τι δεν διαβάζεται είναι «καμία μνήμη» — ποτέ μισή μνήμη. */
    internal fun decode(json: String): State {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return State()
        val known = o.optJSONObject("known") ?: return State()
        val fresh = o.optJSONArray("fresh") ?: JSONArray()
        // Τα αρχεία των προηγούμενων εκδόσεων δεν έχουν «detailed»: κενό, όχι σφάλμα.
        val detailed = o.optJSONObject("detailed") ?: JSONObject()
        return State(
            known = known.keys().asSequence().associateWith { known.optString(it) },
            fresh = (0 until fresh.length()).map { fresh.optString(it) }.filter { it.isNotBlank() }.toSet(),
            detailed = detailed.keys().asSequence().associateWith { detailed.optString(it) },
        )
    }

    internal fun encode(state: State): String = JSONObject()
        .put("known", JSONObject().apply { state.known.orEmpty().forEach { (id, stamp) -> put(id, stamp) } })
        .put("fresh", JSONArray().apply { state.fresh.forEach { put(it) } })
        .put("detailed", JSONObject().apply { state.detailed.forEach { (id, stamp) -> put(id, stamp) } })
        .toString()

    /** Ο φάκελος όπου γράφει το `ProcessRunner` — ίδιος κανόνας ονομάτων. */
    private fun dir(filesDir: File, afm: String): File = File(
        filesDir,
        "runs/${FileBridge.sanitiseSegment(afm)}/${FileBridge.sanitiseSegment(AadeRequests.CONFIG)}",
    )

    private fun newest(dir: File): File? = dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".json") }
        ?.maxByOrNull { it.lastModified() }

    private fun read(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
}
