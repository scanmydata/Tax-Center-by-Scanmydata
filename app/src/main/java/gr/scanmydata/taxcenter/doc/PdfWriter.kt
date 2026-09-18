package gr.scanmydata.taxcenter.doc

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Γράφει αντικείμενα PDF. Τίποτα παραπάνω.
 *
 * Ένα PDF είναι αριθμημένα αντικείμενα, ένας πίνακας με τη θέση του καθενός
 * (`xref`) και ένας επίλογος που λέει πού αρχίζει ο πίνακας. Αυτή η κλάση
 * μαζεύει τα αντικείμενα· το [PdfFile] αποφασίζει αν θα
 * γίνουν καινούργιο αρχείο ή προσθήκη σε υπάρχον.
 *
 * Όλα τα bytes γράφονται σε **ISO-8859-1**: ένας χαρακτήρας, ένα byte. Έτσι οι
 * θέσεις που μετράμε σε συμβολοσειρές είναι οι ίδιες με τις θέσεις στο αρχείο —
 * και σε ένα format όπου ο επίλογος δηλώνει θέσεις σε bytes, αυτό δεν είναι
 * λεπτομέρεια. Τα ελληνικά δεν περνούν ποτέ από εδώ: μέσα στο PDF το κείμενο
 * ζει ως δεκαεξαδικοί αριθμοί γλυφών.
 */
class PdfWriter(first: Int) {

    class Entry(val number: Int, val body: ByteArray)

    private val entries = ArrayList<Entry>()
    var next = first
        private set

    fun alloc(): Int = next++

    fun put(number: Int, body: String): Int = put(number, body.toByteArray(Charsets.ISO_8859_1))

    fun put(number: Int, body: ByteArray): Int {
        entries += Entry(number, body)
        return number
    }

    fun add(body: String): Int = put(alloc(), body)

    /**
     * Αντικείμενο-ροή, συμπιεσμένο. Η συμπίεση δεν είναι καλλωπισμός: η
     * γραμματοσειρά είναι 36 KB και μπαίνει σε κάθε έντυπο.
     */
    fun stream(dict: String, data: ByteArray): Int {
        val packed = deflate(data)
        val head = "<<$dict/Filter/FlateDecode/Length ${packed.size}>>stream\n"
            .toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendstream".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream(head.size + packed.size + tail.size)
        out.write(head); out.write(packed); out.write(tail)
        return add(out.toByteArray())
    }

    private fun add(body: ByteArray): Int = put(alloc(), body)

    /** Τα αντικείμενα, ταξινομημένα — ο `xref` θέλει αύξοντες αριθμούς. */
    fun sorted(): List<Entry> = entries.sortedBy { it.number }

    /**
     * Τα πέντε αντικείμενα μιας γραμματοσειράς, και η αναφορά που μπαίνει στα
     * `/Resources` της σελίδας.
     *
     * Η δομή είναι αυτή που ορίζει το πρότυπο για ενσωματωμένο TrueType με
     * κωδικοποίηση δύο byte: Type0 -> CIDFontType2 -> FontDescriptor -> τα
     * bytes. Το `/CIDToGIDMap /Identity` σημαίνει «ο κωδικός ΕΙΝΑΙ ο αριθμός
     * γλυφού», γι' αυτό και η γραμματοσειρά δεν χρειάζεται `cmap`.
     */
    fun font(font: PdfFont): Int {
        val file = stream("/Length1 ${font.program.size}", font.program)
        val toUnicode = stream("", font.toUnicode().toByteArray(Charsets.ISO_8859_1))
        val descriptor = add(
            "<</Type/FontDescriptor/FontName/${font.name}/Flags 32/FontBBox[${font.bbox}]" +
                "/ItalicAngle 0/Ascent ${font.ascent}/Descent ${font.descent}" +
                "/CapHeight ${font.capHeight}/StemV 80/FontFile2 $file 0 R>>",
        )
        val descendant = add(
            "<</Type/Font/Subtype/CIDFontType2/BaseFont/${font.name}" +
                "/CIDSystemInfo<</Registry(Adobe)/Ordering(Identity)/Supplement 0>>" +
                "/FontDescriptor $descriptor 0 R/DW 1000/W[${font.widths()}]" +
                "/CIDToGIDMap/Identity>>",
        )
        return add(
            "<</Type/Font/Subtype/Type0/BaseFont/${font.name}/Encoding/Identity-H" +
                "/DescendantFonts[$descendant 0 R]/ToUnicode $toUnicode 0 R>>",
        )
    }

    companion object {

        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream(data.size / 2 + 64)
                val buffer = ByteArray(16 * 1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /** Θέση αντικειμένου στον `xref`: δέκα ψηφία, πάντα. */
        fun offset(value: Int): String = value.toString().padStart(10, '0')
    }
}
