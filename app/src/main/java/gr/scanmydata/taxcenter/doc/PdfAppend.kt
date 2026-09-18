package gr.scanmydata.taxcenter.doc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Προσθέτει σελίδες **μέσα** σε PDF που κατέβηκε από την πύλη.
 *
 * ## Γιατί όχι merge
 *
 * Ο desktop runner κάνει πραγματικό merge με το `pdf-lib`: κρατά τη σελίδα της
 * Ταυτότητας Οφειλής όπως είναι και κολλάει από πίσω τη σελίδα των δόσεων. Στο
 * Android δεν υπάρχει τίποτα αντίστοιχο· το `PdfDocument` του συστήματος
 * **γράφει** PDF, δεν διαβάζει.
 *
 * Το να γραφτεί εδώ ένας merger σημαίνει parser για xref tables, object streams
 * και — το δύσκολο — ενσωμάτωση γραμματοσειράς με ελληνικά στη νέα σελίδα.
 * Είναι βιβλιοθήκη, όχι συνάρτηση.
 *
 * ## Τι κάνει αντ' αυτού
 *
 * Ξαναζωγραφίζει τις υπάρχουσες σελίδες με τον `PdfRenderer` (που **διαβάζει**
 * PDF, από το API 21) σε εικόνα 150 dpi, και συνεχίζει με τις δικές μας σελίδες
 * ως κανονικό κείμενο. Το αποτέλεσμα είναι ένα αρχείο, με τη σωστή σειρά, που ο
 * πελάτης ανοίγει και τυπώνει όπως κάθε άλλο.
 *
 * Το τίμημα είναι ρητό: η πρώτη σελίδα γίνεται εικόνα. Στα 150 dpi τυπώνεται
 * καθαρά και το barcode διαβάζεται, αλλά το κείμενό της δεν επιλέγεται πια. Γι'
 * αυτό η μετατροπή γίνεται **μόνο** όταν υπάρχει δοσολόγιο να μπει: μια οφειλή
 * χωρίς δόσεις αφήνει το έντυπο της πύλης ανέγγιχτο.
 */
object PdfAppend {

    /**
     * 150 dpi. Στα 72 το κείμενο της πύλης θολώνει· στα 300 ένα μονοσέλιδο
     * γίνεται τρία megabyte και το email με δέκα οφειλές δεν φεύγει.
     */
    private const val DPI = 150
    private const val SCALE = DPI / 72f

    /**
     * Βάζει το [report] ως επόμενες σελίδες του [target].
     *
     * @return `true` αν το αρχείο ξαναγράφτηκε. Σε αποτυχία το αρχικό μένει
     *   **ακέραιο**: γράφουμε σε προσωρινό και αντικαθιστούμε στο τέλος, ώστε
     *   μια εξαίρεση στη μέση να μην αφήσει μισό PDF στη θέση ενός σωστού.
     */
    fun append(target: File, report: Report): Boolean {
        if (!target.isFile || target.length() == 0L) return false
        val temp = File(target.parentFile, target.name + ".part")
        val doc = PdfDocument()
        try {
            val pages = copyPages(target, doc)
            if (pages < 0) return false
            ReportPdf.render(doc, report, pages)
            temp.outputStream().use { doc.writeTo(it) }
        } catch (e: Exception) {
            temp.delete()
            return false
        } finally {
            doc.close()
        }
        // Η αντικατάσταση γίνεται με φύλαξη του πρωτοτύπου: αν το δεύτερο
        // rename αποτύχει, ο πελάτης πρέπει να κρατήσει την Ταυτότητα Οφειλής
        // του — το δοσολόγιο είναι το «καλό να έχεις», όχι το έγγραφο.
        val backup = File(target.parentFile, target.name + ".bak")
        backup.delete()
        if (!target.renameTo(backup)) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(target)) {
            backup.renameTo(target)
            temp.delete()
            return false
        }
        backup.delete()
        return true
    }

    /** Αντιγράφει τις σελίδες του [source] στο [doc] ως εικόνες. -1 = δεν διαβάζεται. */
    private fun copyPages(source: File, doc: PdfDocument): Int {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            for (index in 0 until count) {
                val page = renderer.openPage(index)
                try {
                    // Το PdfRenderer δίνει διαστάσεις σε points — ακριβώς ό,τι
                    // θέλει και το PdfDocument, οπότε η σελίδα κρατά το μέγεθός
                    // της (A4, A5, ό,τι κι αν έστειλε η πύλη).
                    val bitmap = Bitmap.createBitmap(
                        (page.width * SCALE).toInt().coerceAtLeast(1),
                        (page.height * SCALE).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // Χωρίς αυτό, ό,τι είναι διαφανές στο PDF βγαίνει μαύρο.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val target = doc.startPage(
                        PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create(),
                    )
                    // Με προορισμό-ορθογώνιο, και όχι με συντεταγμένες: αλλιώς
                    // το Canvas κλιμακώνει την εικόνα με βάση την πυκνότητα της
                    // οθόνης και η σελίδα βγαίνει σε λάθος μέγεθος.
                    target.canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    doc.finishPage(target)
                    bitmap.recycle()
                } finally {
                    page.close()
                }
            }
            return count
        } catch (e: Exception) {
            // Κλειδωμένο ή αλλοιωμένο PDF: ο καλών κρατά το αρχικό αρχείο.
            return -1
        } finally {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }
}
