package gr.scanmydata.taxcenter.gdpr

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import gr.scanmydata.taxcenter.engine.FileBridge
import gr.scanmydata.taxcenter.ui.AthensDates
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Εξαγωγές για τις υποχρεώσεις του ΓΚΠΔ.
 *
 * Δύο διαφορετικά πράγματα, που συχνά μπερδεύονται:
 *
 *  * **Αρχείο δραστηριοτήτων** (άρθρο 30) — τι έκανε το γραφείο. Είναι δικό του
 *    έγγραφο, το δείχνει στην Αρχή. Δεν περιέχει τιμές, μόνο ενέργειες.
 *  * **Φορητότητα** (άρθρο 20) — τα δεδομένα ενός πελάτη, για τον ίδιο τον
 *    πελάτη ή για τον επόμενο λογιστή του.
 *
 * Στη φορητότητα **δεν** μπαίνουν οι κωδικοί του: το ZIP φεύγει από τη
 * συσκευή σε κανάλι που δεν ελέγχουμε, και ο πελάτης έχει ήδη τους κωδικούς
 * του. Αν τους θέλει, υπάρχει η ρητή «αποστολή στοιχείων» με τις δικές της
 * δικλείδες.
 */
object Exports {

    /** Ο φάκελος που βλέπει το `FileProvider` (βλ. `xml/file_paths.xml`). */
    private fun dir(context: Context): File =
        File(context.filesDir, "exports").apply { mkdirs() }

    // --------------------------------------------------------- άρθρο 30

    suspend fun auditCsv(context: Context, db: TaxCenterDatabase): File {
        val rows: List<AuditEntity> = db.audit().all()
        val file = File(dir(context), "audit-${System.currentTimeMillis()}.csv")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            // BOM: χωρίς αυτό το Excel σε Windows διαβάζει τα ελληνικά ως ANSI.
            out.write("﻿")
            out.write("Ημερομηνία;Ενέργεια;ΑΦΜ;Λεπτομέρεια\n")
            for (row in rows) {
                out.write(
                    listOf(
                        AthensDates.iso(row.ts),
                        row.action,
                        row.afm,
                        row.detail,
                    ).joinToString(";") { csv(it) },
                )
                out.write("\n")
            }
        }
        return file
    }

    // ------------------------------------------------ ημερολόγιο αποστολών

    /**
     * Το ημερολόγιο σε CSV — για τον φάκελο του πελάτη ή για τον έλεγχο.
     *
     * Ξεχωριστό από το αρχείο δραστηριοτήτων: εκεί μπαίνουν όλες οι ενέργειες
     * του γραφείου, εδώ μόνο οι αποστολές, με τον παραλήπτη και το περιεχόμενο.
     */
    fun sendsCsv(context: Context, sends: List<SendEntity>): File {
        val file = File(dir(context), "αποστολές-${System.currentTimeMillis()}.csv")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("﻿")
            out.write("Ημερομηνία;Πελάτης;ΑΦΜ;Email;Είδος;Θέμα;Πλήθος;Κατάσταση;Σφάλμα;Περιεχόμενο\n")
            for (send in sends.sortedBy { it.sentAt }) {
                out.write(
                    listOf(
                        AthensDates.iso(send.sentAt),
                        send.clientName,
                        send.afm,
                        send.toEmail,
                        if (send.kind == SendEntity.KIND_CREDENTIALS) "Στοιχεία πελάτη" else "Φορολογικά έντυπα",
                        send.subject,
                        send.itemCount.toString(),
                        if (send.failed) "ΑΠΕΤΥΧΕ" else "Στάλθηκε",
                        send.error,
                        send.items.replace('\n', '|'),
                    ).joinToString(";") { csv(it) },
                )
                out.write("\n")
            }
        }
        return file
    }

    /**
     * Διαχωριστικό `;` και όχι `,`: το ελληνικό Excel χρησιμοποιεί το κόμμα ως
     * υποδιαστολή και θα έσπαγε κάθε γραμμή σε λάθος σημείο.
     */
    private fun csv(value: String): String =
        if (value.contains(';') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\""
        } else {
            value
        }

    // --------------------------------------------------------- άρθρο 20

    suspend fun clientZip(
        context: Context,
        db: TaxCenterDatabase,
        repository: ClientRepository,
        client: ClientEntity,
    ): File {
        val documents = db.documents().forClient(client.id)
        val sends = db.sends().forClient(client.id, limit = 10_000)
        val runs = db.runLogs().forAfm(client.afm, limit = 10_000)

        val profile = JSONObject().apply {
            put("afm", client.afm)
            put("επωνυμία", client.name)
            put("όνομα", client.firstName)
            put("είδος", client.kind)
            put("αμκα", repository.amka(client))
            put("δου", client.doy)
            put("ενεργός", client.active)
            put("emailΑΑΔΕ", client.emailAade)
            put("emailΧειροκίνητο", client.emailManual)
            put("εισήχθηΑπό", client.sourceFile)
            put("εισαγωγή", AthensDates.iso(client.importedAt))
            put("τελευταίαΕνημέρωση", AthensDates.iso(client.updatedAt))
            put(
                "σημείωση",
                "Τα διαπιστευτήρια δεν περιλαμβάνονται σε αυτή την εξαγωγή, " +
                    "εσκεμμένα: το αρχείο φεύγει από τη συσκευή σε κανάλι που " +
                    "δεν ελέγχεται.",
            )
            put(
                "έγγραφα",
                JSONArray().apply {
                    documents.forEach { doc ->
                        put(
                            JSONObject().apply {
                                put("αρχείο", doc.fileName)
                                put("διαδικασία", doc.configId)
                                put("έτος", doc.year)
                                put("λήψη", AthensDates.iso(doc.createdAt))
                                put("αποστολή", if (doc.sentAt == 0L) "" else AthensDates.iso(doc.sentAt))
                            },
                        )
                    }
                },
            )
            put(
                "αποστολές",
                JSONArray().apply {
                    sends.forEach { send ->
                        put(
                            JSONObject().apply {
                                put("ημερομηνία", AthensDates.iso(send.sentAt))
                                put("προς", send.toEmail)
                                put("θέμα", send.subject)
                                put("είδος", send.kind)
                                put("κατάσταση", send.status)
                                put("περιεχόμενο", send.items)
                            },
                        )
                    }
                },
            )
            put(
                "εκτελέσεις",
                JSONArray().apply {
                    runs.forEach { run ->
                        put(
                            JSONObject().apply {
                                put("ημερομηνία", AthensDates.iso(run.startedAt))
                                put("διαδικασία", run.configId)
                                put("επιτυχία", run.ok)
                                put("αρχεία", run.fileCount)
                            },
                        )
                    }
                },
            )
        }

        val file = File(dir(context), "πελάτης-${FileBridge.sanitiseSegment(client.afm)}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("στοιχεία.json"))
            zip.write(profile.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (doc in documents) {
                val source = File(context.filesDir, doc.relativePath)
                if (!source.isFile) continue
                zip.putNextEntry(ZipEntry("έντυπα/${doc.fileName}"))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return file
    }

    // ------------------------------------------------------------ κοινή

    /**
     * Ανοίγει τον επιλογέα κοινοποίησης.
     *
     * Μέσω `FileProvider`: ένα `file://` URI θα έσκαγε με `FileUriExposedException`
     * από το Android 7 και μετά, και θα έδινε στον παραλήπτη μόνιμη πρόσβαση
     * στον φάκελο αντί για προσωρινή στο ένα αρχείο.
     */
    fun share(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
