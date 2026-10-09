package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.engine.FileBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Τα αιτήματα ενός πελάτη στον δίσκο, και η **μνήμη** του τι έχουμε ήδη δει.
 *
 * Δύο αρχεία, στον ιδιωτικό φάκελο της λήψης (`runs/<ΑΦΜ>/easynotify/`), που
 * σβήνεται μαζί με τον πελάτη:
 *
 *  * `EASYNOTIFY_<ΑΦΜ>.json` — το γράφει το config σε κάθε λήψη, ολόκληρο.
 *  * `AITIMATA_state.json` — το γράφουμε εμείς: ένα αποτύπωμα ανά αίτημα, και
 *    ποια αιτήματα έχουν απάντηση που ο λογιστής **δεν έχει ανοίξει ακόμη**.
 *
 * Το δεύτερο είναι αυτό που κάνει την παρακολούθηση δυνατή: το config δεν
 * θυμάται τίποτα, οπότε χωρίς αυτό κάθε πρωί όλα τα αιτήματα θα ήταν «νέα» ή
 * κανένα. Κρατά αποτυπώματα και αριθμούς αιτημάτων — όχι το κείμενο.
 *
 * Διαβάζει και γράφει δίσκο — όχι από το κύριο νήμα.
 */
object RequestsStore {

    private const val PREFIX = "EASYNOTIFY_"
    private const val STATE = "AITIMATA_state.json"

    data class Snapshot(
        val requests: List<AadeRequests.Request> = emptyList(),
        /** Πότε γράφτηκε το JSON. `0` = δεν έχει ληφθεί ποτέ. */
        val at: Long = 0L,
        /** Αιτήματα με απάντηση ή αλλαγή που δεν έχει δει ακόμη κανείς. */
        val fresh: Set<String> = emptySet(),
        /** Μη κενό όταν η τελευταία λήψη δεν διάβασε τα αιτήματα. */
        val error: String = "",
    ) {
        val pending: Int get() = requests.count { !it.answered }
    }

    fun load(filesDir: File, afm: String): Snapshot {
        if (afm.isBlank()) return Snapshot()
        val dir = dir(filesDir, afm)
        val file = newest(dir) ?: return Snapshot()
        val json = read(file)
        val requests = AadeRequests.parse(json)
        return Snapshot(
            requests = requests.orEmpty(),
            at = file.lastModified(),
            fresh = state(dir).second,
            error = if (requests == null) AadeRequests.error(json).ifBlank { "δεν διαβάστηκαν" } else "",
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
        val (known, fresh) = state(dir)
        val changes = AadeRequests.diff(known, requests)
        // Ό,τι ήταν «αδιάβαστο» μένει αδιάβαστο, εφόσον το αίτημα υπάρχει ακόμη.
        val ids = requests.map { it.id }.toSet()
        save(dir, AadeRequests.stamps(requests), (fresh + changes.map { it.request.id }).intersect(ids))
        return changes
    }

    /** Ο λογιστής τα είδε. Κενό [ids] = όλα. */
    fun markSeen(filesDir: File, afm: String, ids: Set<String> = emptySet()): Set<String> {
        val dir = dir(filesDir, afm)
        val (known, fresh) = state(dir)
        if (known == null) return emptySet()
        val left = if (ids.isEmpty()) emptySet() else fresh - ids
        save(dir, known, left)
        return left
    }

    // ------------------------------------------------------- ένα αίτημα

    /**
     * Το πλήρες αίτημα [id], αν έχει κατέβει — βλ. [RequestDetail].
     *
     * Ζει σε **δικό του** φάκελο (`runs/<ΑΦΜ>/aade-request/`), μαζί με τα
     * συνημμένα του, και σβήνεται με τον πελάτη όπως κάθε άλλη λήψη.
     */
    fun detail(filesDir: File, afm: String, id: String): RequestDetail.Detail? {
        if (afm.isBlank() || id.isBlank()) return null
        val file = File(detailDir(filesDir, afm), RequestDetail.fileName(id))
        if (!file.isFile) return null
        val detail = RequestDetail.parse(read(file)) ?: return null
        // Το config γράφει την ώρα μέσα στο JSON· αν λείπει, μιλά το αρχείο.
        return if (detail.retrievedAt > 0L) detail else detail.copy(retrievedAt = file.lastModified())
    }

    /** Ποια αιτήματα έχουν ήδη κατέβει ολόκληρα, και πόσα συνημμένα έχει το καθένα. */
    fun downloaded(filesDir: File, afm: String): Map<String, Int> {
        if (afm.isBlank()) return emptyMap()
        val files = detailDir(filesDir, afm).listFiles() ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (file in files) {
            val m = DETAIL_FILE.matchEntire(file.name) ?: continue
            val detail = RequestDetail.parse(read(file)) ?: continue
            out[m.groupValues[1]] = detail.attachments.count { it.downloaded }
        }
        return out
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

    private val DETAIL_FILE = Regex("""AITIMA_(\d+)\.json""")

    private fun detailDir(filesDir: File, afm: String): File = File(
        filesDir,
        "runs/${FileBridge.sanitiseSegment(afm)}/${FileBridge.sanitiseSegment(RequestDetail.CONFIG)}",
    )

    // ------------------------------------------------------------ εσωτερικά

    /** `(γνωστά, αδιάβαστα)`. Τα γνωστά είναι `null` όταν δεν υπάρχει ακόμη μνήμη. */
    private fun state(dir: File): Pair<Map<String, String>?, Set<String>> {
        val o = runCatching { JSONObject(File(dir, STATE).readText(Charsets.UTF_8)) }.getOrNull()
            ?: return NONE
        val known = o.optJSONObject("known") ?: return NONE
        val fresh = o.optJSONArray("fresh") ?: JSONArray()
        return known.keys().asSequence().associateWith { known.optString(it) } to
            (0 until fresh.length()).map { fresh.optString(it) }.filter { it.isNotBlank() }.toSet()
    }

    private val NONE: Pair<Map<String, String>?, Set<String>> = Pair(null, emptySet())

    private fun save(dir: File, known: Map<String, String>, fresh: Set<String>) {
        val o = JSONObject()
            .put("known", JSONObject().apply { known.forEach { (id, stamp) -> put(id, stamp) } })
            .put("fresh", JSONArray().apply { fresh.forEach { put(it) } })
        runCatching {
            dir.mkdirs()
            File(dir, STATE).writeText(o.toString(), Charsets.UTF_8)
        }
    }

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
