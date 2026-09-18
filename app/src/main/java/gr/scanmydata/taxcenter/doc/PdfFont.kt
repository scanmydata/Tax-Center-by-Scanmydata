package gr.scanmydata.taxcenter.doc

import org.json.JSONObject

/**
 * Η γραμματοσειρά ενός PDF: τι γλυφό έχει κάθε χαρακτήρας, πόσο πλατύ είναι, και
 * τα bytes που μπαίνουν μέσα στο αρχείο.
 *
 * ## Γιατί ενσωματώνεται
 *
 * Οι δεκατέσσερις «τυπικές» γραμματοσειρές του PDF δεν έχουν ελληνικά γλυφά.
 * Ένα έντυπο που βασίζεται σε ό,τι βρει ο viewer ανοίγει σωστά στον υπολογιστή
 * του λογιστή και κενά τετράγωνα στο κινητό του πελάτη. Η μόνη απάντηση είναι
 * να ταξιδεύει η γραμματοσειρά μαζί με το έγγραφο.
 *
 * ## Γιατί δεν διαβάζουμε TrueType εδώ
 *
 * Τα `.ttf` που φορτώνονται είναι ήδη κομμένα στα μέτρα μας από το
 * `tools/make-pdf-font.mjs`: 273 γλυφά αντί για 6.253, με αρίθμηση από το μηδέν.
 * Δίπλα τους υπάρχει ένα `.json` με ό,τι χρειάζεται το PDF — αντιστοίχιση
 * χαρακτήρα σε γλυφό, πλάτη, και οι μετρικές του FontDescriptor. Έτσι εδώ δεν
 * υπάρχει ούτε γραμμή binary parsing: το μέρος που σπάει σιωπηλά ζει στο
 * εργαλείο, όπου ελέγχεται με τα μάτια πριν μπει στο αποθετήριο.
 */
class PdfFont(
    /** Το όνομα όπως γράφεται στο PDF (`/BaseFont`). Χωρίς κενά. */
    val name: String,
    /** Τα bytes του TrueType, όπως μπαίνουν στο `/FontFile2`. */
    val program: ByteArray,
    metricsJson: String,
) {

    private val gidOfChar = HashMap<Int, Int>(320)
    private val widthOfGid = HashMap<Int, Int>(320)
    private val chars = ArrayList<Int>(320)

    val ascent: Int
    val descent: Int
    val capHeight: Int
    val bbox: String

    init {
        val o = JSONObject(metricsJson)
        ascent = o.optInt("ascent", 900)
        descent = o.optInt("descent", -200)
        capHeight = o.optInt("capHeight", 700)
        val box = o.optJSONArray("bbox")
        bbox = if (box == null || box.length() != 4) {
            "-1021 -463 1793 1232"
        } else {
            (0 until 4).joinToString(" ") { box.optInt(it).toString() }
        }
        val g = o.optJSONArray("glyphs")
        var i = 0
        while (g != null && i + 2 < g.length()) {
            val cp = g.optInt(i)
            val gid = g.optInt(i + 1)
            gidOfChar[cp] = gid
            widthOfGid[gid] = g.optInt(i + 2)
            chars += cp
            i += 3
        }
    }

    /**
     * Το γλυφό ενός χαρακτήρα. Ό,τι δεν υπάρχει γίνεται «?» — ένα κενό θα
     * έκρυβε τη διαφορά ανάμεσα σε «δεν το έχει η γραμματοσειρά» και «δεν το
     * έστειλε η πύλη».
     */
    fun gid(cp: Int): Int = gidOfChar[cp] ?: gidOfChar['?'.code] ?: 0

    fun width(cp: Int): Int = widthOfGid[gid(cp)] ?: 0

    /** Πλάτος κειμένου σε points, για μέγεθος [size]. */
    fun measure(text: String, size: Float): Float {
        var units = 0
        for (ch in text) units += width(ch.code)
        return units * size / 1000f
    }

    /** Το κείμενο ως δεκαεξαδική συμβολοσειρά γλυφών — η μορφή του Identity-H. */
    fun hex(text: String): String {
        val sb = StringBuilder(text.length * 4)
        for (ch in text) {
            val g = gid(ch.code)
            sb.append(HEX[(g shr 12) and 0xF]).append(HEX[(g shr 8) and 0xF])
                .append(HEX[(g shr 4) and 0xF]).append(HEX[g and 0xF])
        }
        return sb.toString()
    }

    /**
     * Ο πίνακας `/W`, σε ομάδες **συνεχόμενων** αριθμών γλυφών.
     *
     * Τα γλυφά που μπήκαν στη γραμματοσειρά μόνο ως εξαρτήματα συνθέτων (το «ά»
     * είναι alpha συν tonos) δεν αντιστοιχούν σε χαρακτήρα, άρα λείπουν από τη
     * λίστα. Μια ενιαία ακολουθία πλατών θα έδινε σε κάθε γλυφό μετά το πρώτο
     * κενό το πλάτος του επόμενου: το κείμενο γράφεται τότε το ένα γράμμα πάνω
     * στο άλλο, με κενά σε τυχαία σημεία. Το είδαμε, και μοιάζει με χαλασμένη
     * γραμματοσειρά ενώ φταίει αυτή η γραμμή.
     */
    fun widths(): String {
        val gids = widthOfGid.keys.sorted()
        val sb = StringBuilder()
        var i = 0
        while (i < gids.size) {
            var j = i
            while (j + 1 < gids.size && gids[j + 1] == gids[j] + 1) j++
            sb.append(gids[i]).append('[')
            for (k in i..j) {
                if (k > i) sb.append(' ')
                sb.append(widthOfGid[gids[k]])
            }
            sb.append(']')
            i = j + 1
        }
        return sb.toString()
    }

    /**
     * Ο πίνακας `/ToUnicode`: από γλυφό πίσω σε χαρακτήρα.
     *
     * Χωρίς αυτόν το έντυπο **φαίνεται** σωστό αλλά δεν αντιγράφεται: η
     * επικόλληση δίνει αριθμούς γλυφών, και η αναζήτηση μέσα στο PDF δεν βρίσκει
     * τίποτα. Είναι η διαφορά ανάμεσα σε κείμενο και σε εικόνα κειμένου.
     */
    fun toUnicode(): String {
        val pairs = chars.map { cp -> gid(cp) to cp }.sortedBy { it.first }
        val sb = StringBuilder(2048)
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
            .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
            .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
            .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        // Το πρότυπο δεν επιτρέπει πάνω από 100 ζεύγη ανά μπλοκ.
        for (chunk in pairs.chunked(100)) {
            sb.append(chunk.size).append(" beginbfchar\n")
            for ((gid, cp) in chunk) {
                sb.append('<').append(hex4(gid)).append("> <").append(hex4(cp)).append(">\n")
            }
            sb.append("endbfchar\n")
        }
        return sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
            .toString()
    }

    private fun hex4(v: Int): String {
        val sb = StringBuilder(4)
        sb.append(HEX[(v shr 12) and 0xF]).append(HEX[(v shr 8) and 0xF])
            .append(HEX[(v shr 4) and 0xF]).append(HEX[v and 0xF])
        return sb.toString()
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}

/** Οι δύο γραμματοσειρές του εντύπου. Πλάγια δεν χρησιμοποιούνται πουθενά. */
class ReportFonts(val regular: PdfFont, val bold: PdfFont)
