package gr.scanmydata.taxcenter.doc

/**
 * Τοποθετεί ένα [Report] σε σελίδες A4 και το γράφει ως εντολές PDF.
 *
 * ## Γιατί όχι `android.graphics.pdf.PdfDocument`
 *
 * Η πρώτη έκδοση ζωγράφιζε σε `Canvas` και άφηνε το σύστημα να φτιάξει το PDF.
 * Δούλευε — αλλά μόνο για **δικά μας** έντυπα: το `PdfDocument` γράφει PDF, δεν
 * διαβάζει, οπότε δεν υπήρχε τρόπος να μπει μια σελίδα δόσεων μέσα στην
 * Ταυτότητα Οφειλής που κατέβηκε από την ΑΑΔΕ παρά μόνο ξαναζωγραφίζοντάς την
 * ως εικόνα. Και μια εικόνα κειμένου δεν επιλέγεται, δεν αντιγράφεται και δεν
 * βρίσκεται με αναζήτηση.
 *
 * Γράφοντας εμείς τις εντολές, η ίδια διάταξη παράγει και αυτόνομο έντυπο και
 * σελίδες που προσαρτώνται σε ξένο αρχείο — και το κείμενο παραμένει κείμενο
 * παντού. Επιπλέον όλη η διάταξη ελέγχεται πια με τεστ χωρίς Android.
 *
 * Η ίδια η κλάση **δεν αποφασίζει τι λέει το έντυπο**: αυτό έχει κριθεί στο
 * [Report]. Εδώ κρίνεται μόνο πού μπαίνει.
 */
object ReportPdf {

    // A4 σε points (1/72 ίντσας).
    const val PAGE_W = 595
    const val PAGE_H = 842
    private const val MARGIN = 42f
    private const val BOTTOM = PAGE_H - MARGIN - 28f

    // Χρώματα ως κλάσματα, όπως τα θέλει το PDF.
    private const val INK = "0.043 0.106 0.169"
    private const val MUTED = "0.353 0.420 0.490"
    private const val BRAND = "0.180 0.490 0.878"
    private const val LINE = "0.867 0.894 0.925"
    private const val BOX = "0.925 0.953 0.992"

    /** Ποια γραμματοσειρά, με ποιο όνομα μέσα στη σελίδα. */
    private class Pen(val key: String, val font: PdfFont)

    private class Sheet {
        val ops = StringBuilder(4096)
        var y = MARGIN
    }

    /**
     * Γράφει τις σελίδες του [report] ως ροές περιεχομένου στον [writer] και
     * επιστρέφει τους αριθμούς τους, με τη σειρά.
     */
    fun render(writer: PdfWriter, report: Report, fonts: ReportFonts): List<Int> {
        val regular = Pen("F1", fonts.regular)
        val bold = Pen("F2", fonts.bold)
        val width = PAGE_W - 2 * MARGIN
        val sheets = ArrayList<Sheet>()
        var p = Sheet().also { sheets += it }

        fun text(s: String, x: Float, y: Float, pen: Pen, size: Float, colour: String) {
            if (s.isEmpty()) return
            p.ops.append("BT ").append(colour).append(" rg /").append(pen.key).append(' ')
                .append(num(size)).append(" Tf 1 0 0 1 ").append(num(x)).append(' ')
                .append(num(PAGE_H - y)).append(" Tm <").append(pen.font.hex(s)).append("> Tj ET\n")
        }

        fun rect(x: Float, y: Float, w: Float, h: Float, colour: String) {
            p.ops.append(colour).append(" rg ").append(num(x)).append(' ')
                .append(num(PAGE_H - y - h)).append(' ').append(num(w)).append(' ')
                .append(num(h)).append(" re f\n")
        }

        fun room(needed: Float) {
            if (p.y + needed > BOTTOM) p = Sheet().also { sheets += it }
        }

        // ------------------------------------------------------- κεφαλίδα
        text(report.title, MARGIN, p.y + 16f, bold, 17f, INK)
        if (report.office.isNotBlank()) {
            val shown = fit(report.office, regular, 9f, width * 0.45f)
            text(shown, PAGE_W - MARGIN - regular.font.measure(shown, 9f), p.y + 15f, regular, 9f, MUTED)
        }
        p.y += 26f
        rect(MARGIN, p.y, width, 1.2f, BRAND)
        p.y += 18f

        // -------------------------------------------------------- στοιχεία
        for ((label, value) in report.identity) {
            room(16f)
            text(label, MARGIN, p.y + 10f, regular, 9f, MUTED)
            text(fit(value, regular, 11f, width - 140f), MARGIN + 140f, p.y + 10f, regular, 11f, INK)
            p.y += 16f
        }

        // ------------------------------------------- ταυτότητα, σε πλαίσιο
        //
        // Είναι το μόνο στοιχείο του εντύπου που ο πελάτης θα πληκτρολογήσει,
        // και το μόνο που αν το πάρει λάθος πληρώνει άλλη οφειλή.
        if (report.debtorId.isNotBlank()) {
            p.y += 10f
            room(58f)
            val top = p.y
            rect(MARGIN, top, width, 52f, BOX)
            text(report.idCaption, MARGIN + 14f, top + 18f, regular, 9f, MUTED)
            text(fit(report.debtorId, bold, 20f, width - 28f), MARGIN + 14f, top + 42f, bold, 20f, BRAND)
            p.y = top + 52f + 18f
        } else if (report.identity.isNotEmpty()) {
            p.y += 12f
        }

        // --------------------------------------------------------- σύνοψη
        if (report.summary.isNotEmpty()) {
            room(20f)
            text("Σύνοψη", MARGIN, p.y + 10f, bold, 11f, INK)
            p.y += 18f
            for ((label, value) in report.summary) {
                room(15f)
                text(label, MARGIN, p.y + 10f, regular, 9f, MUTED)
                text(value, MARGIN + 140f, p.y + 10f, if (label == "Υπόλοιπο") bold else regular, 11f, INK)
                p.y += 15f
            }
            p.y += 8f
        }

        // -------------------------------------------------------- πίνακες
        fun table(t: Table) {
            val widths = columns(t, regular, bold, width)
            val xs = ArrayList<Float>(widths.size)
            var x = MARGIN
            for (w in widths) { xs += x; x += w }
            val numeric = t.numeric.toSet()

            fun cell(s: String, i: Int, pen: Pen, size: Float, colour: String) {
                val shown = fit(s, pen, size, widths[i] - 6f)
                val left = if (i in numeric) {
                    xs[i] + widths[i] - 6f - pen.font.measure(shown, size)
                } else {
                    xs[i]
                }
                text(shown, left, p.y + 9f, pen, size, colour)
            }

            fun row(cells: List<String>, pen: Pen, size: Float) {
                for (i in cells.indices) {
                    if (i >= xs.size) break
                    cell(cells[i], i, pen, size, INK)
                }
                p.y += 13f
            }

            // Η κεφαλίδα ξαναγράφεται σε κάθε νέα σελίδα: ένας πίνακας δόσεων
            // που συνεχίζει χωρίς επικεφαλίδες είναι στήλες αριθμών χωρίς όνομα.
            fun header() {
                val lines = t.headers.mapIndexed { i, h ->
                    if (i < widths.size) wrap(h, bold, 8.5f, widths[i] - 6f).take(2) else listOf(h)
                }
                val rows = lines.maxOfOrNull { it.size } ?: 1
                val top = p.y
                lines.forEachIndexed { i, parts ->
                    if (i >= xs.size) return@forEachIndexed
                    parts.forEachIndexed { n, part ->
                        p.y = top + (rows - parts.size + n) * 11f
                        cell(part, i, bold, 8.5f, MUTED)
                    }
                }
                p.y = top + rows * 11f + 2f
                rect(MARGIN, p.y, width, 0.7f, LINE)
                p.y += 6f
            }

            room(46f)
            header()
            for (line in t.rows) {
                if (p.y + 15f > BOTTOM) {
                    room(15f)
                    header()
                }
                row(line, regular, 9f)
                rect(MARGIN, p.y, width, 0.4f, LINE)
                p.y += 2f
            }
            if (t.totals.isNotEmpty()) {
                room(18f)
                p.y += 2f
                row(t.totals, bold, 9f)
                p.y += 2f
            }
        }

        // -------------------------------------------------------- ενότητες
        for (section in report.sections) {
            room(46f)
            text(section.caption, MARGIN, p.y + 10f, bold, 11f, INK)
            p.y += 18f

            for ((label, value) in section.facts) {
                room(14f)
                text(label, MARGIN, p.y + 9f, regular, 9f, MUTED)
                text(fit(value, regular, 11f, width - 140f), MARGIN + 140f, p.y + 9f, regular, 11f, INK)
                p.y += 14f
            }
            if (section.facts.isNotEmpty()) p.y += 6f

            section.table?.let { table(it) }

            if (section.note.isNotBlank()) {
                for (piece in wrap(section.note, regular, 8f, width)) {
                    room(12f)
                    text(piece, MARGIN, p.y + 8f, regular, 8f, MUTED)
                    p.y += 11f
                }
            }
            p.y += 14f
        }

        // -------------------------------------------------------- υποσέλιδο
        for (line in report.footer) {
            for (piece in wrap(line, regular, 8f, width)) {
                room(12f)
                text(piece, MARGIN, p.y + 8f, regular, 8f, MUTED)
                p.y += 11f
            }
            p.y += 3f
        }

        return sheets.map { writer.stream("", it.ops.toString().toByteArray(Charsets.ISO_8859_1)) }
    }

    /**
     * Πλάτη στηλών.
     *
     * Όπου το έντυπο ορίζει αναλογίες, τις τηρεί. Όπου **δεν** ορίζει — δηλαδή
     * όπου τις στήλες τις ονομάζει η πύλη και όχι εμείς — τα πλάτη βγαίνουν από
     * το περιεχόμενο: ελάχιστο χώρο για τα δεδομένα ολόκληρα και για την
     * πλατύτερη λέξη κάθε επικεφαλίδας, και ό,τι περισσεύει σε όποια επικεφαλίδα
     * θέλει περισσότερο. Έτσι το «Προσαυξήσεις, Τόκοι, Τέλη» σπάει σε δύο
     * γραμμές αντί να κοπεί σε «Προσαυξήσεις,…».
     */
    private fun columns(t: Table, regular: Pen, bold: Pen, width: Float): List<Float> {
        if (t.weights.isNotEmpty()) {
            val sum = t.weights.sum().takeIf { it > 0f } ?: 1f
            return t.weights.map { width * it / sum }
        }
        val body = t.rows + listOf(t.totals)
        val minimum = t.headers.mapIndexed { i, h ->
            var w = h.split(' ').maxOfOrNull { bold.font.measure(it, 8.5f) } ?: 0f
            for (line in body) {
                val value = line.getOrNull(i).orEmpty()
                if (value.isNotEmpty()) w = maxOf(w, regular.font.measure(value, 9f))
            }
            w + 8f
        }
        val sum = minimum.sum().takeIf { it > 0f } ?: 1f
        if (sum >= width) return minimum.map { it * width / sum }

        val wanted = t.headers.map { bold.font.measure(it, 8.5f) + 8f }
        val need = minimum.mapIndexed { i, w -> maxOf(0f, wanted[i] - w) }
        val total = need.sum()
        var slack = width - sum
        val out = minimum.mapIndexed { i, w ->
            if (total <= 0f) w else w + minOf(need[i], slack * need[i] / total)
        }.toMutableList()
        slack = width - out.sum()
        if (slack > 0f) for (i in out.indices) out[i] = out[i] + slack / out.size
        return out
    }

    /** Κόβει με «…» ό,τι δεν χωρά — ένα κελί που ξεχειλίζει σκεπάζει το διπλανό. */
    private fun fit(text: String, pen: Pen, size: Float, width: Float): String {
        if (pen.font.measure(text, size) <= width) return text
        val sb = StringBuilder()
        for (ch in text) {
            if (pen.font.measure("$sb$ch…", size) > width) break
            sb.append(ch)
        }
        return sb.toString().trimEnd() + "…"
    }

    /** Σπάει κείμενο σε γραμμές που χωρούν, στα κενά. */
    private fun wrap(text: String, pen: Pen, size: Float, width: Float): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.trim().split(' ')) {
            if (word.isEmpty()) continue
            val probe = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && pen.font.measure(probe, size) > width) {
                out += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(probe)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /**
     * Αριθμός για το PDF, με τελεία **πάντα**.
     *
     * Το `String.format("%.2f")` ακολουθεί το locale της συσκευής: σε ελληνική
     * συσκευή θα έγραφε «595,00» και το αρχείο δεν θα άνοιγε καθόλου.
     */
    private fun num(value: Float): String = (Math.round(value * 100) / 100.0).toString()
}
