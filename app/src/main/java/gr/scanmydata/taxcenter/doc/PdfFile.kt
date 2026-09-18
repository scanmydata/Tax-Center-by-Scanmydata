package gr.scanmydata.taxcenter.doc

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Γράφει ένα [Report] σε αρχείο — είτε μόνο του, είτε **μέσα** σε έντυπο που
 * κατέβηκε από την πύλη.
 *
 * Οι δύο δρόμοι μοιράζονται τα πάντα εκτός από το τέλος: ίδιες γραμματοσειρές,
 * ίδιες ροές περιεχομένου, ίδιες σελίδες. Αλλάζει μόνο τι μπαίνει μπροστά τους
 * (τίποτα ή ολόκληρο το αρχείο της ΑΑΔΕ) και τι γράφει ο επίλογος.
 */
object PdfFile {

    /** Καινούργιο έγγραφο. */
    fun write(report: Report, fonts: ReportFonts, target: File) {
        val writer = PdfWriter(1)
        val catalog = writer.alloc()
        val pages = writer.alloc()
        val (regular, bold) = fontObjects(writer, fonts)
        val pageNumbers = pages(writer, report, fonts, pages, regular, bold)
        writer.put(catalog, "<</Type/Catalog/Pages $pages 0 R>>")
        writer.put(
            pages,
            "<</Type/Pages/Kids[" + pageNumbers.joinToString(" ") { "$it 0 R" } +
                "]/Count ${pageNumbers.size}>>",
        )

        val out = ByteArrayOutputStream(64 * 1024)
        val header = "%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1)
        out.write(header)
        val offsets = body(out, writer, header.size)

        val xref = StringBuilder("xref\n0 ${writer.next}\n0000000000 65535 f \n")
        for (n in 1 until writer.next) {
            xref.append(PdfWriter.offset(offsets[n] ?: 0)).append(" 00000 n \n")
        }
        xref.append("trailer\n<</Size ${writer.next}/Root $catalog 0 R>>\nstartxref\n")
            .append(out.size()).append("\n%%EOF\n")
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))

        target.parentFile?.mkdirs()
        target.writeBytes(out.toByteArray())
    }

    /**
     * Προσθέτει τις σελίδες του [report] στο τέλος του [target].
     *
     * Το αρχικό αρχείο **δεν αλλάζει ούτε ένα byte**: το PDF σχεδιάστηκε για
     * σταδιακή ενημέρωση, οπότε αρκεί να γραφτούν τα νέα αντικείμενα από πίσω
     * και ένας νέος πίνακας θέσεων που δείχνει στον προηγούμενο. Η Ταυτότητα
     * Οφειλής της ΑΑΔΕ μένει ακριβώς όπως την εξέδωσε η πύλη — με το κείμενό
     * της να επιλέγεται και να αναζητείται, όπως και οι δικές μας σελίδες.
     *
     * @return `false` αν το αρχείο δεν είναι δομής που ξέρουμε να επεκτείνουμε
     *   (κρυπτογραφημένο, ή με συμπιεσμένο πίνακα θέσεων). Ο καλών γράφει τότε
     *   χωριστό αρχείο· τίποτα δεν έχει αγγιχτεί.
     */
    fun append(report: Report, fonts: ReportFonts, target: File): Boolean {
        if (!target.isFile || target.length() == 0L) return false
        val base = PdfBase.read(target.readBytes()) ?: return false

        val writer = PdfWriter(base.size)
        val (regular, bold) = fontObjects(writer, fonts)
        val pageNumbers = pages(writer, report, fonts, base.pagesNumber, regular, bold)
        writer.put(base.pagesNumber, base.pagesWith(pageNumbers))

        val out = ByteArrayOutputStream(base.bytes.size + 64 * 1024)
        out.write(base.bytes)
        if (base.bytes.isNotEmpty() && base.bytes.last() != '\n'.code.toByte()) out.write('\n'.code)
        val offsets = body(out, writer, out.size())

        val startxref = out.size()
        val xref = StringBuilder("xref\n")
        for (group in groups(offsets.keys.sorted())) {
            xref.append(group.first()).append(' ').append(group.size).append('\n')
            for (n in group) xref.append(PdfWriter.offset(offsets[n] ?: 0)).append(" 00000 n \n")
        }
        xref.append("trailer\n<</Size ${maxOf(base.size, writer.next)}/Root ${base.rootNumber} 0 R")
        if (base.info.isNotEmpty()) xref.append("/Info ${base.info} 0 R")
        if (base.id.isNotEmpty()) xref.append("/ID ${base.id}")
        xref.append("/Prev ${base.startxref}>>\nstartxref\n").append(startxref).append("\n%%EOF\n")
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))

        return replace(target, out.toByteArray())
    }

    // ------------------------------------------------------------- κοινά

    private fun fontObjects(writer: PdfWriter, fonts: ReportFonts): Pair<Int, Int> =
        writer.font(fonts.regular) to writer.font(fonts.bold)

    private fun pages(
        writer: PdfWriter,
        report: Report,
        fonts: ReportFonts,
        parent: Int,
        regular: Int,
        bold: Int,
    ): List<Int> = ReportPdf.render(writer, report, fonts).map { content ->
        writer.add(
            "<</Type/Page/Parent $parent 0 R/MediaBox[0 0 ${ReportPdf.PAGE_W} ${ReportPdf.PAGE_H}]" +
                "/Resources<</Font<</F1 $regular 0 R/F2 $bold 0 R>>>>/Contents $content 0 R>>",
        )
    }

    /** Γράφει τα αντικείμενα και επιστρέφει πού κάθισε το καθένα. */
    private fun body(out: ByteArrayOutputStream, writer: PdfWriter, from: Int): Map<Int, Int> {
        val offsets = LinkedHashMap<Int, Int>()
        var at = from
        for (entry in writer.sorted()) {
            offsets[entry.number] = at
            val head = "${entry.number} 0 obj\n".toByteArray(Charsets.ISO_8859_1)
            val tail = "\nendobj\n".toByteArray(Charsets.ISO_8859_1)
            out.write(head); out.write(entry.body); out.write(tail)
            at += head.size + entry.body.size + tail.size
        }
        return offsets
    }

    /** Ο πίνακας θέσεων θέλει υποενότητες με **συνεχόμενους** αριθμούς. */
    private fun groups(numbers: List<Int>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for (n in numbers) {
            if (current.isNotEmpty() && n != current.last() + 1) {
                out += current
                current = ArrayList()
            }
            current += n
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * Αντικατάσταση με φύλαξη του πρωτοτύπου.
     *
     * Το δοσολόγιο είναι το «καλό να έχεις»· η Ταυτότητα Οφειλής είναι το
     * έγγραφο. Αν κάτι πάει στραβά στη μέση, ο πελάτης πρέπει να κρατήσει το
     * δεύτερο.
     */
    private fun replace(target: File, bytes: ByteArray): Boolean {
        val backup = File(target.parentFile, target.name + ".bak")
        val temp = File(target.parentFile, target.name + ".part")
        return try {
            temp.writeBytes(bytes)
            backup.delete()
            if (!target.renameTo(backup)) return false.also { temp.delete() }
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                temp.delete()
                return false
            }
            backup.delete()
            true
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }
}
