package gr.scanmydata.taxcenter.keao

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Τυπώνει ένα [KeaoCard.Report] σε PDF σελίδας A4.
 *
 * Χρησιμοποιεί το `android.graphics.pdf.PdfDocument` του συστήματος — καμία
 * βιβλιοθήκη. Η εναλλακτική ήταν ένα iText/PdfBox για κάτι που το Android
 * κάνει από το API 19, με το ίδιο σκεπτικό που ο `XlsxReader` απέφυγε το POI.
 *
 * Η κλάση **δεν αποφασίζει τίποτα**: παίρνει έτοιμο το περιεχόμενο και το
 * τοποθετεί. Ό,τι έχει να κριθεί — ποιος φορέας, ποια ταυτότητα, ποια ποσά —
 * έχει ήδη κριθεί στο [KeaoCard], που ελέγχεται με τεστ χωρίς Android.
 */
object KeaoPdf {

    // A4 σε points (1/72 ίντσας), όπως τα θέλει το PdfDocument.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
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

    fun write(report: KeaoCard.Report, target: File) {
        val doc = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun startPage() {
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            canvas = page!!.canvas
            y = MARGIN
        }

        fun endPage() {
            val current = page ?: return
            doc.finishPage(current)
            page = null
            canvas = null
        }

        fun room(needed: Float) {
            if (y + needed > PAGE_H - MARGIN - 28f) { endPage(); startPage() }
        }

        val width = PAGE_W - 2 * MARGIN
        startPage()
        val c = { canvas!! }

        // ------------------------------------------------------- κεφαλίδα
        c().drawText(report.title, MARGIN, y + 16f, paint(17f, bold = true))
        if (report.office.isNotBlank()) {
            val p = paint(9f, colour = MUTED)
            c().drawText(
                fit(report.office, p, width * 0.45f),
                PAGE_W - MARGIN - p.measureText(fit(report.office, p, width * 0.45f)),
                y + 15f,
                p,
            )
        }
        y += 26f
        c().drawRect(MARGIN, y, PAGE_W - MARGIN, y + 1.2f, Paint().apply { color = BRAND })
        y += 18f

        // -------------------------------------------------------- στοιχεία
        val labelPaint = paint(9f, colour = MUTED)
        val valuePaint = paint(11f)
        for ((label, value) in report.identity) {
            room(16f)
            c().drawText(label, MARGIN, y + 10f, labelPaint)
            c().drawText(fit(value, valuePaint, width - 140f), MARGIN + 140f, y + 10f, valuePaint)
            y += 16f
        }

        // ------------------------------------------- ταυτότητα, σε πλαίσιο
        //
        // Είναι το μόνο στοιχείο του εντύπου που ο πελάτης θα πληκτρολογήσει,
        // και το μόνο που αν το πάρει λάθος πληρώνει άλλον φορέα.
        y += 10f
        room(58f)
        val boxTop = y
        c().drawRect(MARGIN, boxTop, PAGE_W - MARGIN, boxTop + 52f, Paint().apply { color = BOX })
        c().drawText("ΤΑΥΤΟΤΗΤΑ ΟΦΕΙΛΕΤΗ", MARGIN + 14f, boxTop + 18f, paint(9f, colour = MUTED))
        val idPaint = paint(20f, bold = true, colour = BRAND).apply { letterSpacing = 0.06f }
        c().drawText(
            fit(report.debtorId.ifBlank { "—" }, idPaint, width - 28f),
            MARGIN + 14f,
            boxTop + 42f,
            idPaint,
        )
        y = boxTop + 52f + 18f

        // --------------------------------------------------------- σύνοψη
        if (report.summary.isNotEmpty()) {
            room(20f)
            c().drawText("Σύνοψη", MARGIN, y + 10f, paint(11f, bold = true))
            y += 18f
            for ((label, value) in report.summary) {
                room(15f)
                c().drawText(label, MARGIN, y + 10f, labelPaint)
                val p = if (label == "Υπόλοιπο") paint(11f, bold = true) else valuePaint
                c().drawText(value, MARGIN + 140f, y + 10f, p)
                y += 15f
            }
            y += 8f
        }

        // -------------------------------------------------------- ενότητες

        fun drawTable(table: KeaoCard.Table) {
            val sum = table.weights.sum().takeIf { it > 0f } ?: 1f
            val widths = table.weights.map { width * it / sum }
            val xs = ArrayList<Float>(widths.size)
            var x = MARGIN
            for (w in widths) { xs += x; x += w }

            fun rowOf(cells: List<String>, textPaint: Paint) {
                for (i in cells.indices) {
                    if (i >= xs.size) break
                    c().drawText(fit(cells[i], textPaint, widths[i] - 6f), xs[i], y + 9f, textPaint)
                }
                y += 13f
            }

            // Η κεφαλίδα ξαναγράφεται σε κάθε νέα σελίδα: ένας πίνακας δόσεων
            // που συνεχίζει χωρίς επικεφαλίδες είναι στήλες αριθμών χωρίς όνομα.
            val headPaint = paint(8.5f, bold = true, colour = MUTED)
            fun header() {
                rowOf(table.headers, headPaint)
                c().drawRect(MARGIN, y, PAGE_W - MARGIN, y + 0.7f, Paint().apply { color = LINE })
                y += 6f
            }

            room(40f)
            header()
            val cellPaint = paint(9f)
            for (row in table.rows) {
                if (y + 15f > PAGE_H - MARGIN - 28f) {
                    room(15f)
                    header()
                }
                rowOf(row, cellPaint)
                c().drawRect(MARGIN, y, PAGE_W - MARGIN, y + 0.4f, Paint().apply { color = LINE })
                y += 2f
            }
            if (table.totals.isNotEmpty()) {
                room(18f)
                y += 2f
                rowOf(table.totals, paint(9f, bold = true))
                y += 2f
            }
        }

        for (section in report.sections) {
            room(46f)
            c().drawText(section.caption, MARGIN, y + 10f, paint(11f, bold = true))
            y += 18f

            for ((label, value) in section.facts) {
                room(14f)
                c().drawText(label, MARGIN, y + 9f, labelPaint)
                c().drawText(fit(value, valuePaint, width - 140f), MARGIN + 140f, y + 9f, valuePaint)
                y += 14f
            }
            if (section.facts.isNotEmpty()) y += 6f

            section.table?.let { drawTable(it) }

            if (section.note.isNotBlank()) {
                val notePaint = paint(8f, colour = MUTED)
                for (piece in wrap(section.note, notePaint, width)) {
                    room(12f)
                    c().drawText(piece, MARGIN, y + 8f, notePaint)
                    y += 11f
                }
            }
            y += 14f
        }

        // -------------------------------------------------------- υποσέλιδο
        val footPaint = paint(8f, colour = MUTED)
        for (line in report.footer) {
            for (piece in wrap(line, footPaint, width)) {
                room(12f)
                c().drawText(piece, MARGIN, y + 8f, footPaint)
                y += 11f
            }
            y += 3f
        }

        endPage()
        target.parentFile?.mkdirs()
        target.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
