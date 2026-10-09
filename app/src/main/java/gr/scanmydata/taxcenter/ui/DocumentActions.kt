package gr.scanmydata.taxcenter.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import java.io.File

/**
 * Άνοιγμα ενός ληφθέντος εγγράφου σε εξωτερικό πρόγραμμα προβολής.
 *
 * **Δεν** ενσωματώνουμε δικό μας PDF viewer: θα σήμαινε βιβλιοθήκη rendering
 * μέσα σε εφαρμογή που έχει ρητή γραμμή να μη φουσκώνει με εξαρτήσεις, και ο
 * χρήστης έχει ήδη έναν viewer που ξέρει και εμπιστεύεται.
 *
 * Το URI δίνεται μέσω `FileProvider` με **προσωρινό** δικαίωμα ανάγνωσης: το
 * αρχείο ζει σε app-private αποθήκευση και δεν γίνεται ορατό σε κανέναν άλλο
 * πέρα από την εφαρμογή που το ανοίγει, όσο το ανοίγει.
 */
object DocumentActions {

    fun fileOf(context: Context, document: DocumentEntity): File =
        File(context.filesDir, document.relativePath)

    /**
     * Τα έντυπα των πυλών είναι PDF. Οι εικόνες και τα έγγραφα γραφείου
     * υπάρχουν για τα **συνημμένα των αιτημάτων**: εκεί ο πελάτης ανεβάζει ό,τι
     * έχει — φωτογραφία ταυτότητας, σαρωμένο συμβόλαιο — και ένας άγνωστος
     * τύπος θα έβγαζε «δεν βρέθηκε εφαρμογή» για μια απλή εικόνα.
     */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "txt" -> "text/plain"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "*/*"
    }

    /** Επιστρέφει μήνυμα σφάλματος, ή κενό όταν άνοιξε. */
    fun open(context: Context, document: DocumentEntity): String =
        open(context, fileOf(context, document), document.fileName)

    /** Το ίδιο, για αρχείο που δεν είναι εγγραφή της βάσης — π.χ. συνημμένο αιτήματος. */
    fun open(context: Context, file: File, name: String = file.name): String {
        if (!file.isFile) return "Το αρχείο δεν υπάρχει πια στη συσκευή."
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ""
        } catch (e: android.content.ActivityNotFoundException) {
            "Δεν βρέθηκε εφαρμογή που να ανοίγει αυτόν τον τύπο αρχείου."
        } catch (e: Exception) {
            "Δεν άνοιξε: ${e.message}"
        }
    }

    /** Διαγράφει έγγραφα μαζί με τα αρχεία τους. Επιστρέφει πόσα έφυγαν. */
    suspend fun delete(
        context: Context,
        db: gr.scanmydata.taxcenter.data.db.TaxCenterDatabase,
        documents: List<DocumentEntity>,
    ): Int {
        if (documents.isEmpty()) return 0
        for (document in documents) {
            fileOf(context, document).delete()
        }
        db.documents().deleteByIds(documents.map { it.id })
        db.audit().log(
            gr.scanmydata.taxcenter.data.db.AuditEntity(
                ts = System.currentTimeMillis(),
                action = "DELETE_DOCUMENTS",
                detail = "${documents.size} έγγραφα",
            ),
        )
        return documents.size
    }
}
