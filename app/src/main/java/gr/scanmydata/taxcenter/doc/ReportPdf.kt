package gr.scanmydata.taxcenter.doc

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Τυπώνει ένα [Report] σε σελίδες A4.
 *
 * Χρησιμοποιεί το `android.graphics.pdf.PdfDocument` του συστήματος — καμία
 * βιβλιοθήκη. Η εναλλακτική ήταν ένα iText/PdfBox για κάτι που το Android κάνει
 * από το API 19, με το ίδιο σκεπτικό που ο `XlsxReader` απέφυγε το POI.
 *
 * Η κλάση **δεν αποφασίζει τίποτα**: παίρνει έτοιμο το περιεχόμενο και το
 * τοποθετεί. Ό,τι έχει να κριθεί — ποιος φορέας, ποια ταυτότητα, ποια ποσά —
 * έχει ήδη κριθεί πριν φτάσει εδώ, σε κώδικα που ελέγχεται χωρίς Android.
 */
object ReportPdf {

    // A4 σε points (1/72 ίντσας), όπως τα θέλει το PdfDocument.
    const val PAGE_W = 595
    const val PAGE_H = 842
    private const val MARGIN = 42f
    private val INK = Color.rgb(0x0B, 0x1B, 0x2B)
    private val MUTED = Color.rgb(0x5A, 0x6B, 0x7D)
    private val BRAND = Color.rgb(0x2E, 0x7D, 0xE0)
    private val LINE = Color.rgb(0xDD, 0xE4, 0xEC)
    private val BOX = Color.rgb(0xEC, 0xF3, 0xFD)

    private fun paint(size: Float, bold: Boolean = false, colour: Int = INK) = Paint().apply {
        isAntiAlias = true
        textSize = size
        color = colour
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Κόβει με «…» ό,τι δεν χωρά — ένα κελί που ξεχειλίζει σκεπάζει το διπλανό. */
    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val room = width - paint.measureText("…")
        if (room <= 0) return "…"
        val chars = paint.breakText(text, true, room, null)
        return text.take(chars).trimEnd() + "…"
    }

    /** Σπάει κείμενο σε γραμμές που χωρούν, σε κενά όπου γίνεται. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (paint.measureText(rest) <= width) { out += rest; break }
            var take = paint.breakText(rest, true, width, null)
            if (take <= 0) take = 1
            val slice = rest.take(take)
            val cut = slice.lastIndexOf(' ')
            val end = if (cut > take / 3) cut else take
            out += rest.take(end).trimEnd()
            rest = rest.drop(end).trimStart()
        }
        return out
    }

    /**
     * Ο κέρσορας της σελίδας: ξέρει πού γράφουμε και πότε τελείωσε το χαρτί.
     *
     * Ζει χωριστά από τη [write] επειδή οι σελίδες δεν ξεκινούν πάντα από την
     * αρχή ενός εγγράφου — το [PdfAppend] προσθέτει σελίδες πίσω από έντυπο που
     * κατέβηκε από την πύλη, και η αρίθμηση πρέπει να συνεχίσει από εκεί.
     */
    private class Pager(val doc: PdfDocument, var number: Int) {
        private var page: PdfDocument.Page? = null
        var canvas: Canvas = Canvas()
            private set
        var y = 0f

        fun start() {
            number++
            val fresh = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create(),
            )
            page = fresh
            canvas = fresh.canvas
            y = MARGIN
        }

        fun end() {
            val current = page ?: return
            doc.finishPage(current)
            page = null
        }

        /** Χώρος για [needed] points· αλλιώς νέα σελίδα. */
        fun room(needed: Float) {
            if (y + needed > PAGE_H - MARGIN - 28f) { end(); start() }
        }
    }

    /** Γράφει το [report] σε δικό του αρχείο. */
    fun write(report: Report, target: File) {
        val doc = PdfDocument()
        try {
            render(doc, report, 0)
            target.parentFile?.mkdirs()
            target.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /**
     * Ζωγραφίζει το [report] μέσα σε **υπάρχον** έγγραφο, ξεκινώντας από τη
     * σελίδα [after] + 1. Επιστρέφει τον αριθμό της τελευταίας σελίδας.
     */
    fun render(doc: PdfDocument, report: Report, after: Int): Int {
        val width = PAGE_W - 2 * MARGIN
        val p = Pager(doc, after)
        p.start()

        // ------------------------------------------------------- κεφαλίδα
        p.canvas.drawText(report.title, MARGIN, p.y + 16f, paint(17f, bold = true))
        if (report.office.isNotBlank()) {
            val op = paint(9f, colour = MUTED)
            val text = fit(report.office, op, width * 0.45f)
            p.canvas.drawText(text, PAGE_W - MARGIN - op.measureText(text), p.y + 15f, op)
        }
        p.y += 26f
        p.canvas.drawRect(MARGIN, p.y, PAGE_W - MARGIN, p.y + 1.2f, Paint().apply { color = BRAND })
        p.y += 18f

        // -------------------------------------------------------- στοιχεία
        val labelPaint = paint(9f, colour = MUTED)
        val valuePaint = paint(11f)
        for ((label, value) in report.identity) {
            p.room(16f)
            p.canvas.drawText(label, MARGIN, p.y + 10f, labelPaint)
            p.canvas.drawText(fit(value, valuePaint, width - 140f), MARGIN + 140f, p.y + 10f, valuePaint)
            p.y += 16f
        }

        // ------------------------------------------- ταυτότητα, σε πλαίσιο
        //
        // Είναι το μόνο στοιχείο του εντύπου που ο πελάτης θα πληκτρολογήσει,
        // και το μόνο που αν το πάρει λάθος πληρώνει άλλη οφειλή.
        if (report.debtorId.isNotBlank()) {
            p.y += 10f
            p.room(58f)
            val boxTop = p.y
            p.canvas.drawRect(MARGIN, boxTop, PAGE_W - MARGIN, boxTop + 52f, Paint().apply { color = BOX })
            p.canvas.drawText(report.idCaption, MARGIN + 14f, boxTop + 18f, paint(9f, colour = MUTED))
            val idPaint = paint(20f, bold = true, colour = BRAND).apply { letterSpacing = 0.06f }
            p.canvas.drawText(fit(report.debtorId, idPaint, width - 28f), MARGIN + 14f, boxTop + 42f, idPaint)
            p.y = boxTop + 52f + 18f
        } else if (report.identity.isNotEmpty()) {
            p.y += 12f
        }

        // --------------------------------------------------------- σύνοψη
        if (report.summary.isNotEmpty()) {
            p.room(20f)
            p.canvas.drawText("Σύνοψη", MARGIN, p.y + 10f, paint(11f, bold = true))
            p.y += 18f
            for ((label, value) in report.summary) {
                p.room(15f)
                p.canvas.drawText(label, MARGIN, p.y + 10f, labelPaint)
                val vp = if (label == "Υπόλοιπο") paint(11f, bold = true) else valuePaint
                p.canvas.drawText(value, MARGIN + 140f, p.y + 10f, vp)
                p.y += 15f
            }
            p.y += 8f
        }

        // -------------------------------------------------------- ενότητες
        for (section in report.sections) {
            p.room(46f)
            p.canvas.drawText(section.caption, MARGIN, p.y + 10f, paint(11f, bold = true))
            p.y += 18f

            for ((label, value) in section.facts) {
                p.room(14f)
                p.canvas.drawText(label, MARGIN, p.y + 9f, labelPaint)
                p.canvas.drawText(fit(value, valuePaint, width - 140f), MARGIN + 140f, p.y + 9f, valuePaint)
                p.y += 14f
            }
            if (section.facts.isNotEmpty()) p.y += 6f

            section.table?.let { drawTable(p, it, width) }

            if (section.note.isNotBlank()) {
                val notePaint = paint(8f, colour = MUTED)
                for (piece in wrap(section.note, notePaint, width)) {
                    p.room(12f)
                    p.canvas.drawText(piece, MARGIN, p.y + 8f, notePaint)
                    p.y += 11f
                }
            }
            p.y += 14f
        }

        // -------------------------------------------------------- υποσέλιδο
        val footPaint = paint(8f, colour = MUTED)
        for (line in report.footer) {
            for (piece in wrap(line, footPaint, width)) {
                p.room(12f)
                p.canvas.drawText(piece, MARGIN, p.y + 8f, footPaint)
                p.y += 11f
            }
            p.y += 3f
        }

        p.end()
        return p.number
    }

    private fun drawTable(p: Pager, table: Table, width: Float) {
        val sum = table.weights.sum().takeIf { it > 0f } ?: 1f
        val widths = table.weights.map { width * it / sum }
        val xs = ArrayList<Float>(widths.size)
        var x = MARGIN
        for (w in widths) { xs += x; x += w }

        fun rowOf(cells: List<String>, textPaint: Paint) {
            for (i in cells.indices) {
                if (i >= xs.size) break
                p.canvas.drawText(fit(cells[i], textPaint, widths[i] - 6f), xs[i], p.y + 9f, textPaint)
            }
            p.y += 13f
        }

        // Η κεφαλίδα ξαναγράφεται σε κάθε νέα σελίδα: ένας πίνακας δόσεων που
        // συνεχίζει χωρίς επικεφαλίδες είναι στήλες αριθμών χωρίς όνομα.
        val headPaint = paint(8.5f, bold = true, colour = MUTED)
        fun header() {
            rowOf(table.headers, headPaint)
            p.canvas.drawRect(MARGIN, p.y, PAGE_W - MARGIN, p.y + 0.7f, Paint().apply { color = LINE })
            p.y += 6f
        }

        p.room(40f)
        header()
        val cellPaint = paint(9f)
        for (row in table.rows) {
            if (p.y + 15f > PAGE_H - MARGIN - 28f) {
                p.room(15f)
                header()
            }
            rowOf(row, cellPaint)
            p.canvas.drawRect(MARGIN, p.y, PAGE_W - MARGIN, p.y + 0.4f, Paint().apply { color = LINE })
            p.y += 2f
        }
        if (table.totals.isNotEmpty()) {
            p.room(18f)
            p.y += 2f
            rowOf(table.totals, paint(9f, bold = true))
            p.y += 2f
        }
    }
}
