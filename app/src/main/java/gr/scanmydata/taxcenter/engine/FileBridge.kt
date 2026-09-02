package gr.scanmydata.taxcenter.engine

import android.util.Base64
import java.io.File
import java.io.IOException

/**
 * Το `fs` του engine, δεμένο σε έναν φάκελο-ρίζα.
 *
 * Ο runner γράφει σε `downloads/<configId>/`. Εδώ η ρίζα είναι app-private
 * (`filesDir/runs/...`), ώστε τίποτε να μη διαρρέει σε κοινόχρηστη αποθήκευση —
 * τα PDF περιέχουν ΑΦΜ, εισοδήματα και οφειλές πελατών.
 *
 * Δύο πράγματα προσέχει ιδιαίτερα:
 *
 *  * **Ελληνικά ονόματα.** Ο runner παράγει `Φ2_123456789_2025.pdf` και
 *    `MISTH_Μισθωτής_<διεύθυνση>_<ποσό>.pdf`. Τα κρατάμε — είναι χρήσιμα για τον
 *    λογιστή — αλλά καθαρίζουμε ό,τι δεν επιτρέπεται σε filesystem, και κόβουμε
 *    το μήκος ώστε να μη σκάσει σε FAT/exFAT.
 *  * **Path traversal.** Ένα config δεν πρέπει ποτέ να γράψει έξω από τη ρίζα,
 *    ακόμη κι αν κάποια μελλοντική αλλαγή φέρει `..` σε όνομα αρχείου.
 */
class FileBridge(
    private val root: File,
    /**
     * Όταν είναι false, τα διαγνωστικά του runner **δεν γράφονται καθόλου**.
     *
     * Ο runner γράφει δίπλα σε κάθε PDF ένα `run.log` και δεκάδες dumps σελίδων
     * (`01_aade_oam.html`, `registry_userdata.xml`, …). Στον desktop είναι
     * χρήσιμα για post-mortem· στο κινητό μπερδεύονται με τα έγγραφα του πελάτη
     * και, χειρότερα, τα dumps είναι **ολόκληρες σελίδες ΑΑΔΕ** με προσωπικά
     * δεδομένα σε καθαρό κείμενο.
     *
     * Οι γραμμές του log δεν χάνονται: καταλήγουν στο `run_logs`, περασμένες
     * από τον [Redactor].
     */
    private val keepDiagnostics: Boolean = false,
) {

    init {
        root.mkdirs()
    }

    /** Ό,τι δεν κρατάμε όταν τα διαγνωστικά είναι κλειστά. */
    private fun isDiagnostic(name: String): Boolean {
        val lower = name.lowercase()
        // Κρατάμε το ζητούμενο: το PDF, και το JSON με τα δεδομένα — για
        // διαδικασίες όπως ΑΜΚΑ και ΑΤΛΑΣ το JSON ΕΙΝΑΙ το παραδοτέο.
        if (lower.endsWith(".pdf") || lower.endsWith(".json")) return false
        return true
    }

    /**
     * Γράφει (ή προσθέτει) bytes. Επιστρέφει "" ή μήνυμα λάθους.
     *
     * Τα διαγνωστικά αρχεία «γράφονται» επιτυχώς χωρίς να αγγίξουν τον δίσκο:
     * τα configs δεν ελέγχουν το αποτέλεσμα του `fs.writeFileSync`, αλλά ένα
     * σφάλμα εδώ θα τερμάτιζε τη διαδικασία.
     */
    fun write(path: String, dataB64: String, append: Boolean): String = guard {
        val target = resolve(path)
        if (!keepDiagnostics && isDiagnostic(target.name)) return@guard ""
        target.parentFile?.mkdirs()
        val bytes = Base64.decode(dataB64, Base64.DEFAULT)
        if (append) target.appendBytes(bytes) else target.writeBytes(bytes)
        ""
    }

    /** Επιστρέφει base64 ή null αν δεν υπάρχει. */
    fun read(path: String): String? = try {
        val f = resolve(path)
        if (f.isFile) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
    } catch (e: Exception) {
        null
    }

    fun exists(path: String): String = try {
        if (resolve(path).exists()) "1" else "0"
    } catch (e: Exception) {
        "0"
    }

    fun size(path: String): String = try {
        val f = resolve(path)
        if (f.isFile) f.length().toString() else "-1"
    } catch (e: Exception) {
        "-1"
    }

    fun mkdirs(path: String): String = guard {
        resolve(path).mkdirs()
        ""
    }

    /** Όλα τα αρχεία που γράφτηκαν κάτω από τη ρίζα, με σχετική διαδρομή. */
    fun list(): List<File> =
        root.walkTopDown().filter { it.isFile }.toList()

    private inline fun guard(block: () -> String): String = try {
        block()
    } catch (e: SecurityException) {
        e.message ?: "άρνηση πρόσβασης"
    } catch (e: IOException) {
        e.message ?: "σφάλμα εγγραφής"
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    /**
     * Μετατρέπει μια διαδρομή του engine σε πραγματικό αρχείο μέσα στη ρίζα.
     * Πετάει αν το αποτέλεσμα θα έβγαινε εκτός ρίζας.
     */
    private fun resolve(path: String): File {
        val cleaned = path.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .map(::sanitiseSegment)

        val target = cleaned.fold(root) { acc, seg -> File(acc, seg) }
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw SecurityException("διαδρομή εκτός ρίζας: $path")
        }
        return target
    }

    companion object {
        /**
         * Χαρακτήρες άκυροι σε Windows/exFAT/SMB, όχι μόνο σε Linux, συν οι
         * control chars. Παύλες και κενά ΕΠΙΤΡΕΠΟΝΤΑΙ: τα ids των configs είναι
         * aade-income, efka-notices κ.λπ., και ονόματα PDF του runner περιέχουν
         * κενά — π.χ. MISTH_Μισθωτής_ΟΔΟΣ 12_450,00.pdf.
         */
        private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")

        /** Μέγιστο μήκος ονόματος. Το exFAT κόβει στα 255· κρατάμε περιθώριο. */
        private const val MAX_SEGMENT = 120

        /**
         * Καθαρίζει ένα κομμάτι διαδρομής διατηρώντας τα ελληνικά.
         * `..` εξουδετερώνεται εδώ, όχι μόνο στο [resolve].
         */
        fun sanitiseSegment(raw: String): String {
            var s = raw.replace(ILLEGAL, "_").trim()
            if (s == ".." || s.isEmpty()) s = "_"
            // Τα trailing '.' και ' ' είναι άκυρα σε Windows shares.
            s = s.trimEnd('.', ' ')
            if (s.isEmpty()) s = "_"
            if (s.length > MAX_SEGMENT) {
                val dot = s.lastIndexOf('.')
                s = if (dot > 0 && s.length - dot <= 6) {
                    val ext = s.substring(dot)
                    s.substring(0, MAX_SEGMENT - ext.length) + ext
                } else {
                    s.substring(0, MAX_SEGMENT)
                }
            }
            return s
        }
    }
}
