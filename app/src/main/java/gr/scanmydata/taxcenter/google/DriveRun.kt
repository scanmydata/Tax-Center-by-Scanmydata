package gr.scanmydata.taxcenter.google

import android.content.Context
import gr.scanmydata.taxcenter.data.KeyStoreKeys
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Μία εκτέλεση του Drive: φάκελος, αντίγραφο της βάσης, αρχεία πελατών.
 *
 * Την ίδια ακριβώς σειρά την τρέχουν και οι Ρυθμίσεις (όταν ο χρήστης πατά ή
 * μόλις ενεργοποιήσει το Drive) και η καθημερινή εργασία του παρασκηνίου —
 * ώστε «το αντίγραφο έγινε» να σημαίνει το ίδιο πράγμα και στις δύο.
 *
 * Κάθε στάδιο είναι ανεξάρτητο: αν αποτύχει το ανέβασμα ενός PDF, το
 * αντίγραφο της βάσης έχει ήδη γίνει και μετρά. Το αποτέλεσμα γράφεται στις
 * ρυθμίσεις ([Settings.driveLastSummary]), για να το δείχνει η οθόνη.
 */
class DriveRun(
    private val context: Context,
    private val db: TaxCenterDatabase,
    private val settings: Settings,
    private val backup: DriveBackup,
    private val sync: DriveSync,
) {

    enum class Stage(val label: String) {
        FOLDER("Φάκελος στο Drive"),
        BACKUP("Κρυπτογραφημένο αντίγραφο της βάσης"),
        FILES("Έντυπα, οφειλές και αιτήματα"),
    }

    data class Report(
        val folder: String = "",
        val backup: DriveBackup.Entry? = null,
        val files: DriveSync.Progress? = null,
        /** Ανά στάδιο, ό,τι πήγε στραβά. Κενό = όλα καλά. */
        val errors: Map<Stage, String> = emptyMap(),
    ) {
        val ok: Boolean get() = errors.isEmpty()

        fun summary(): String = buildList {
            backup?.let { add("αντίγραφο ${it.size / 1024} KB") }
            files?.let {
                add(
                    "${it.uploaded} αρχεία ανέβηκαν" +
                        (if (it.failed > 0) ", ${it.failed} απέτυχαν" else "") +
                        (if (it.skipped > 0) ", ${it.skipped} ήδη εκεί" else ""),
                )
            }
            errors.forEach { (stage, error) -> add(stage.label.lowercase() + ": " + error) }
        }.joinToString(" · ").ifBlank { "τίποτα να γίνει" }
    }

    /**
     * @param onStage καλείται στην αρχή κάθε σταδίου, και με πρόοδο στα αρχεία
     */
    suspend fun run(
        token: String,
        stages: Set<Stage>,
        onStage: (Stage, String) -> Unit = { _, _ -> },
    ): Report {
        val errors = LinkedHashMap<Stage, String>()
        var folder = ""
        var entry: DriveBackup.Entry? = null
        var files: DriveSync.Progress? = null

        if (Stage.FOLDER in stages || Stage.BACKUP in stages) {
            onStage(Stage.FOLDER, sync.folderPath)
            try {
                folder = sync.ensureFolders(token)
                settings.driveFolderChosen = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[Stage.FOLDER] = message(e)
            }
        }

        if (Stage.BACKUP in stages && Stage.FOLDER !in errors) {
            onStage(Stage.BACKUP, "Κρυπτογράφηση και ανέβασμα…")
            val passphrase = KeyStoreKeys.backupPassphrase(context)
            if (passphrase == null) {
                errors[Stage.BACKUP] = "δεν έχει οριστεί passphrase"
            } else {
                try {
                    entry = withContext(Dispatchers.IO) {
                        val parent = sync.backupFolder(DriveClient(token), HashMap())
                        backup.upload(token, passphrase, parent).also {
                            runCatching { backup.prune(token) }
                        }
                    }
                    settings.driveLastBackup = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[Stage.BACKUP] = message(e)
                }
            }
        }

        if (Stage.FILES in stages && Stage.FOLDER !in errors) {
            onStage(Stage.FILES, "")
            try {
                files = sync.syncAll(token) { p ->
                    onStage(Stage.FILES, "${p.stage}: ${p.uploaded} ανέβηκαν" + if (p.failed > 0) ", ${p.failed} απέτυχαν" else "")
                }
                if (files.failed > 0) errors[Stage.FILES] = "${files.failed} αρχεία δεν ανέβηκαν"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[Stage.FILES] = message(e)
            }
        }

        val report = Report(folder, entry, files, errors)
        record(report)
        return report
    }

    /** Για όταν η εκτέλεση δεν έφτασε καν να ξεκινήσει — π.χ. χωρίς άδεια Google. */
    fun recordFailure(reason: String) {
        settings.driveLastRun = System.currentTimeMillis()
        settings.driveLastSummary = reason
        settings.driveLastFailed = true
    }

    private suspend fun record(report: Report) {
        settings.driveLastRun = System.currentTimeMillis()
        settings.driveLastSummary = report.summary()
        settings.driveLastFailed = !report.ok
        runCatching {
            db.audit().log(
                AuditEntity(
                    ts = System.currentTimeMillis(),
                    action = if (report.ok) "DRIVE_RUN" else "DRIVE_RUN_PARTIAL",
                    // Μόνο αριθμοί: ονόματα αρχείων και πελατών δεν χρειάζονται εδώ.
                    detail = report.summary(),
                ),
            )
        }
    }

    private fun message(e: Exception): String = when (e) {
        is GoogleAuthorizer.ConsentRequired -> "χρειάζεται ξανά σύνδεση με Google"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "χωρίς δίκτυο"
        else -> e.message ?: e.javaClass.simpleName
    }
}
