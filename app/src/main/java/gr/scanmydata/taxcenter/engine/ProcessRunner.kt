package gr.scanmydata.taxcenter.engine

import android.content.Context
import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import gr.scanmydata.taxcenter.data.Crypto
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.data.db.RunLogEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import java.io.File

/**
 * Εκτελεί διαδικασίες λήψης για έναν ή πολλούς πελάτες.
 *
 * Τρεις κανόνες, όλοι από πραγματικό περιορισμό και όχι από προτίμηση:
 *
 *  1. **Αυστηρά σειριακά.** Το GSIS κλείνει τη συνεδρία με `OAM-6` («μέγιστος
 *     αριθμός περιόδων λειτουργίας») όταν ανοίξουν πολλές ταυτόχρονα. Δύο
 *     πελάτες παράλληλα σημαίνει αποτυχία και στους δύο.
 *  2. **Μια αποτυχία δεν σταματά την παρτίδα.** Ο desktop runner επιστρέφει
 *     σφάλματα ως τιμή· το ίδιο κάνουμε κι εδώ, ώστε ένας πελάτης με λάθος
 *     κωδικό να μη χαλάει τη λήψη των υπόλοιπων 40.
 *  3. **Κάθε εκτέλεση αφήνει ίχνος** στο `audit_log` — τι, πότε, για ποιον.
 *     Ποτέ τιμές (GDPR άρθρο 30).
 */
class ProcessRunner(
    private val context: Context,
    private val db: TaxCenterDatabase,
    private val crypto: Crypto,
    private val assets: EngineAssets = EngineAssets(context),
    private val settings: Settings = Settings(context),
) {

    data class Job(
        val client: ClientEntity,
        val configId: String,
        /** Έτος, μήνας, είδος εντύπου κ.λπ. — ό,τι δεν είναι διαπιστευτήριο. */
        val extraInputs: Map<String, String> = emptyMap(),
    )

    data class Outcome(
        val job: Job,
        val ok: Boolean,
        val reason: String,
        val files: List<String>,
        val durationMs: Long,
        val log: List<String> = emptyList(),
        /**
         * Ό,τι επέστρεψε το ίδιο το config στο πεδίο `out`, ως JSON.
         *
         * Χρειάζεται για διαδικασίες που δεν παράγουν έγγραφο αλλά **δεδομένα** —
         * το `aade-email` επιστρέφει τη διεύθυνση που βρήκε στο Μητρώο, και η
         * εφαρμογή τη γράφει στην καρτέλα αντί να την ψάχνει σε αρχείο.
         */
        val out: String? = null,
    )

    data class Progress(
        val index: Int,
        val total: Int,
        val client: ClientEntity,
        val configId: String,
        val phase: Phase,
        val outcome: Outcome? = null,
    ) {
        enum class Phase { STARTING, RUNNING, DONE }
    }

    /**
     * Τρέχει τις [jobs] με τη σειρά. Δεν πετάει ποτέ.
     *
     * Ο [browserHost] χρειάζεται μόνο για διαδικασίες με `needsBrowser`
     * (σήμερα: `aade-enfia`). Αν λείπει, αυτές αποτυγχάνουν με σαφή λόγο ενώ
     * όλες οι υπόλοιπες τρέχουν κανονικά.
     */
    suspend fun run(
        jobs: List<Job>,
        browserHost: JsHost.BrowserPageHost? = null,
        onProgress: (Progress) -> Unit = {},
    ): List<Outcome> {
        val outcomes = ArrayList<Outcome>(jobs.size)
        val host = JsHost(context, assets, browserHost)
        try {
            jobs.forEachIndexed { index, job ->
                onProgress(Progress(index, jobs.size, job.client, job.configId, Progress.Phase.STARTING))
                val outcome = runOne(job, host)
                outcomes += outcome
                onProgress(
                    Progress(index, jobs.size, job.client, job.configId, Progress.Phase.DONE, outcome),
                )
            }
        } finally {
            host.shutdown()
        }
        return outcomes
    }

    private suspend fun runOne(job: Job, host: JsHost): Outcome {
        val started = System.currentTimeMillis()
        val outDir = outputDir(job)

        val inputs = try {
            buildInputs(job)
        } catch (e: MissingCredentials) {
            audit("FETCH", job, ok = false, detail = e.message.orEmpty())
            return Outcome(job, false, e.message.orEmpty(), emptyList(), 0)
        }

        val before = existingFiles(outDir)
        val result = host.run(
            configId = job.configId,
            inputs = inputs,
            outDir = outDir,
            keepDiagnostics = settings.diagnostics,
        )
        val produced = existingFiles(outDir) - before

        if (result.ok) {
            recordDocuments(job, outDir, produced)
        }
        recordRunLog(job, started, result, produced.size)
        audit(
            "FETCH", job, result.ok,
            detail = buildString {
                append(job.configId)
                if (!result.ok) append(" — ").append(result.reason)
                else append(" — ").append(produced.size).append(" αρχεία")
            },
        )

        return Outcome(
            job = job,
            ok = result.ok,
            reason = result.reason,
            files = produced.toList().sorted(),
            durationMs = System.currentTimeMillis() - started,
            log = result.log,
            out = result.out,
        )
    }

    // ------------------------------------------------------------- είσοδοι

    private class MissingCredentials(message: String) : Exception(message)

    /**
     * Χτίζει τα inputs που περιμένει το config.
     *
     * Τα configs ζητούν πάντα `user`/`pass`, αλλά άλλα εννοούν TAXISnet κι άλλα
     * ΙΚΑ εργοδότη — βλ. [CredentialMap]. Οι κωδικοί αποκρυπτογραφούνται εδώ,
     * ζουν όσο διαρκεί η εκτέλεση, και δεν λογάρονται ποτέ.
     */
    private suspend fun buildInputs(job: Job): Map<String, String> {
        val requirement = CredentialMap.forConfig(job.configId)
            ?: throw MissingCredentials("Άγνωστη διαδικασία: ${job.configId}")

        val stored = db.credentials().forClient(job.client.id)
            .associate { it.field to crypto.dec(it.valueEnc) }

        fun credential(field: Field): String = stored[field.name].orEmpty()

        val (userField, passField) = when (requirement.login) {
            CredentialMap.Login.TAXISNET -> Field.TAXIS_USER to Field.TAXIS_PASS
            CredentialMap.Login.IKA_EMPLOYER -> Field.IKA_EMPLOYER_USER to Field.IKA_EMPLOYER_PASS
        }
        val user = credential(userField)
        val pass = credential(passField)
        if (user.isBlank() || pass.isBlank()) {
            throw MissingCredentials(
                "Λείπουν ${CredentialMap.describe(requirement.login)} για τον πελάτη ${job.client.afm}",
            )
        }

        val inputs = HashMap<String, String>()
        inputs["user"] = user
        inputs["pass"] = pass
        if (requirement.needsAfm) inputs["afm"] = job.client.afm
        if (requirement.needsAmka) {
            val amka = crypto.dec(job.client.amkaEnc)
            if (amka.isBlank()) {
                throw MissingCredentials("Λείπει το ΑΜΚΑ για τον πελάτη ${job.client.afm}")
            }
            inputs["amka"] = amka
        }
        // Το ΑΦΜ-στόχος για τις διαδικασίες μητρώου.
        inputs.putIfAbsent("vat", job.client.afm)
        inputs.putAll(job.extraInputs)
        return inputs
    }

    // --------------------------------------------------------------- αρχεία

    /** `filesDir/runs/<ΑΦΜ>/<configId>/` — app-private, ποτέ κοινόχρηστη αποθήκευση. */
    fun outputDir(job: Job): File =
        File(context.filesDir, "runs/${FileBridge.sanitiseSegment(job.client.afm)}/${FileBridge.sanitiseSegment(job.configId)}")
            .apply { mkdirs() }

    private fun existingFiles(dir: File): Set<String> =
        dir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()

    /**
     * Καταγράφει τα παραγόμενα PDF. Τα `.json`, `.html` και `run.log` είναι
     * διαγνωστικά του engine, όχι έγγραφα πελάτη — δεν μπαίνουν στη λίστα ούτε
     * στέλνονται ποτέ με email.
     */
    private suspend fun recordDocuments(job: Job, dir: File, produced: Set<String>) {
        val now = System.currentTimeMillis()
        for (name in produced) {
            if (!name.endsWith(".pdf", ignoreCase = true)) continue
            val file = File(dir, name)
            db.documents().put(
                DocumentEntity(
                    clientId = job.client.id,
                    configId = job.configId,
                    fileName = name,
                    relativePath = "runs/${job.client.afm}/${job.configId}/$name",
                    year = job.extraInputs["year"].orEmpty(),
                    bytes = file.length(),
                    createdAt = now,
                ),
            )
        }
    }

    /**
     * Το ημερολόγιο της εκτέλεσης.
     *
     * Ο runner θα έγραφε `run.log` δίπλα στα PDF· εδώ οι γραμμές καταλήγουν στη
     * βάση, ήδη περασμένες από τον [Redactor] στο [NativeBridge]. Έτσι ο φάκελος
     * του πελάτη μένει καθαρός και το log παραμένει αναζητήσιμο.
     */
    private suspend fun recordRunLog(job: Job, startedAt: Long, result: JsHost.RunResult, fileCount: Int) {
        db.runLogs().log(
            RunLogEntity(
                afm = job.client.afm,
                configId = job.configId,
                startedAt = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                ok = result.ok,
                reason = result.reason,
                fileCount = fileCount,
                lines = result.log.joinToString(System.lineSeparator()),
            ),
        )
    }

    private suspend fun audit(action: String, job: Job, ok: Boolean, detail: String) {
        db.audit().log(
            AuditEntity(
                ts = System.currentTimeMillis(),
                action = if (ok) action else "${action}_FAILED",
                afm = job.client.afm,
                detail = Redactor.scrub(detail),
            ),
        )
    }
}
