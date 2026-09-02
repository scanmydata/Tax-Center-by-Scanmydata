package gr.scanmydata.taxcenter.gdpr

import android.content.Context
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import java.io.File
import java.time.ZonedDateTime

/**
 * Πολιτική διατήρησης των ληφθέντων εντύπων.
 *
 * Η αρχή του περιορισμού της περιόδου αποθήκευσης (άρθρο 5 παρ. 1 στοιχ. ε)
 * λέει ότι τα δεδομένα δεν κρατιούνται «για κάθε ενδεχόμενο». Ένα εκκαθαριστικό
 * του 2019 στο κινητό του λογιστή το 2030 δεν εξυπηρετεί κανέναν σκοπό — είναι
 * μόνο έκθεση σε κίνδυνο.
 *
 * Τι **δεν** διαγράφεται:
 *
 *  * το `audit_log`, που είναι το αρχείο δραστηριοτήτων (άρθρο 30) και δεν
 *    περιέχει προσωπικά δεδομένα πέρα από το ΑΦΜ,
 *  * η καρτέλα και τα διαπιστευτήρια του πελάτη — η σχέση συνεχίζεται· αυτά
 *    φεύγουν μόνο με ρητή διαγραφή πελάτη.
 *
 * Διαγράφονται τα **αρχεία** και οι εγγραφές τους, καθώς και τα παλιά
 * `run_logs`, που είναι διαγνωστικά και όχι αρχείο.
 */
object Retention {

    data class Result(val documents: Int, val runLogs: Int)

    suspend fun apply(
        context: Context,
        db: TaxCenterDatabase,
        settings: Settings,
    ): Result {
        val months = settings.retentionMonths
        if (months <= 0) return Result(0, 0)

        val cutoff = ZonedDateTime.now().minusMonths(months.toLong()).toInstant().toEpochMilli()

        val stale = db.documents().olderThan(cutoff)
        var removedFiles = 0
        for (doc in stale) {
            val file = File(context.filesDir, doc.relativePath)
            if (file.isFile && file.delete()) removedFiles++
        }
        if (stale.isNotEmpty()) {
            db.documents().deleteByIds(stale.map { it.id })
        }

        // Τα run_logs κρατιούνται λιγότερο: είναι διαγνωστικά και μεγαλώνουν
        // πολύ γρηγορότερα από τα έγγραφα.
        val logCutoff = ZonedDateTime.now().minusMonths(minOf(months, 6).toLong())
            .toInstant().toEpochMilli()
        val removedLogs = db.runLogs().deleteOlderThan(logCutoff)

        if (stale.isNotEmpty() || removedLogs > 0) {
            db.audit().log(
                AuditEntity(
                    ts = System.currentTimeMillis(),
                    action = "RETENTION",
                    detail = "διαγράφηκαν ${stale.size} έντυπα ($removedFiles αρχεία) " +
                        "και $removedLogs εγγραφές ιστορικού, όρια $months μήνες",
                ),
            )
        }
        return Result(stale.size, removedLogs)
    }
}
