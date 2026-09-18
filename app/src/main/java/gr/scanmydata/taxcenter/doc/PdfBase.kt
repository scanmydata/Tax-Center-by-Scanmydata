package gr.scanmydata.taxcenter.doc

/**
 * Όσο PDF χρειάζεται να **διαβάσουμε** για να προσθέσουμε σελίδες σε έντυπο που
 * κατέβηκε από την πύλη — και ούτε γραμμή παραπάνω.
 *
 * ## Τι θέλουμε από το αρχείο
 *
 * Τέσσερα πράγματα: πού αρχίζει ο τελευταίος πίνακας θέσεων (`startxref`), ποιο
 * αντικείμενο είναι ο κατάλογος (`/Root`), ποιο είναι ο κόμβος των σελίδων
 * (`/Pages`), και τι γράφει εκείνος (`/Kids`, `/Count`). Με αυτά, οι νέες
 * σελίδες γράφονται **στο τέλος** του αρχείου και το υπάρχον περιεχόμενο δεν
 * ακουμπιέται καθόλου.
 *
 * ## Γιατί αυτό αρκεί
 *
 * Το PDF σχεδιάστηκε από την αρχή για σταδιακή ενημέρωση: ο αναγνώστης διαβάζει
 * τον τελευταίο πίνακα και ακολουθεί το `/Prev` προς τα πίσω. Δεν χρειάζεται να
 * καταλάβουμε τίποτα από το περιεχόμενο — ούτε εικόνες, ούτε γραμματοσειρές,
 * ούτε τη σελίδα της Ταυτότητας Οφειλής. Το πρωτότυπο μένει byte προς byte ό,τι
 * έστειλε η ΑΑΔΕ, με ό,τι αυτό σημαίνει για ένα έγγραφο που ο πελάτης θα
 * πληρώσει.
 *
 * ## Τι δεν υποστηρίζεται
 *
 * Κρυπτογραφημένα αρχεία και συμπιεσμένοι πίνακες θέσεων (`/Type /XRef`, PDF
 * 1.5+). Και τα δύο αναγνωρίζονται και **απορρίπτονται καθαρά**: ο καλών γράφει
 * τότε χωριστό αρχείο. Η ΑΑΔΕ βγάζει iText 2.1.7 με κλασικό πίνακα — το
 * επαληθεύσαμε στο πραγματικό έντυπο — αλλά μια σιωπηλή απόπειρα σε άγνωστη
 * δομή θα παρέδιδε χαλασμένο PDF σε πελάτη.
 */
class PdfBase private constructor(
    val bytes: ByteArray,
    val text: String,
    /** Θέση του τελευταίου πίνακα θέσεων — γίνεται το `/Prev` του δικού μας. */
    val startxref: Int,
    val size: Int,
    val rootNumber: Int,
    val pagesNumber: Int,
    val pagesDict: String,
    val info: String,
    val id: String,
) {

    /** Το `/Pages` ξαναγραμμένο με τις νέες σελίδες στο τέλος του `/Kids`. */
    fun pagesWith(newPages: List<Int>): String {
        val kids = KIDS.find(pagesDict)?.groupValues?.get(1)?.trim().orEmpty()
        val count = COUNT.find(pagesDict)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val merged = (if (kids.isEmpty()) "" else "$kids ") + newPages.joinToString(" ") { "$it 0 R" }
        return pagesDict
            .replace(KIDS, "/Kids[$merged]")
            .replace(COUNT, "/Count ${count + newPages.size}")
            .trim()
    }

    companion object {

        private val KIDS = Regex("""/Kids\s*\[([^]]*)]""")
        private val COUNT = Regex("""/Count\s+(\d+)""")
        private val PAGES_REF = Regex("""/Pages\s+(\d+)\s+(\d+)\s+R""")
        private val ROOT_REF = Regex("""/Root\s+(\d+)\s+(\d+)\s+R""")
        private val INFO_REF = Regex("""/Info\s+(\d+)\s+(\d+)\s+R""")
        private val ID_ARRAY = Regex("""/ID\s*(\[[^]]*])""")
        private val SIZE = Regex("""/Size\s+(\d+)""")
        private val PREV = Regex("""/Prev\s+(\d+)""")

        /** `null` όταν το αρχείο δεν είναι δομής που ξέρουμε να επεκτείνουμε. */
        fun read(bytes: ByteArray): PdfBase? {
            // Ένα byte, ένας χαρακτήρας: οι θέσεις που μετράμε εδώ είναι οι
            // θέσεις που γράφει ο πίνακας. Με UTF-8 θα απέκλιναν σιωπηλά.
            val text = String(bytes, Charsets.ISO_8859_1)
            if (!text.startsWith("%PDF-")) return null

            val marker = text.lastIndexOf("startxref")
            if (marker < 0) return null
            val start = text.substring(marker + 9).trim().takeWhile { it.isDigit() }.toIntOrNull()
                ?: return null
            if (start <= 0 || start >= bytes.size) return null
            // Συμπιεσμένος πίνακας θέσεων: εκεί θα βρίσκαμε «N 0 obj», όχι «xref».
            if (!text.startsWith("xref", start)) return null

            // Η αλυσίδα των πινάκων, από τον νεότερο προς τα πίσω.
            //
            // Ένα αρχείο που έχει ήδη επεκταθεί μία φορά — και το δικό μας
            // αποτέλεσμα είναι ακριβώς αυτό — κρατά τα παλιά αντικείμενα σε
            // προηγούμενο πίνακα, με το `/Prev` να δείχνει εκεί. Χωρίς να την
            // ακολουθήσουμε, ο κατάλογος του εγγράφου «δεν υπάρχει».
            val offsets = HashMap<Int, Int>()
            val known = HashSet<Int>()
            var trailer: String? = null
            var section: Int? = start
            val visited = HashSet<Int>()
            while (section != null && visited.add(section)) {
                if (!text.startsWith("xref", section)) return null
                val scanner = Scanner(text, section + 4)
                while (true) {
                    val head = scanner.token() ?: return null
                    if (head == "trailer") break
                    val first = head.toIntOrNull() ?: return null
                    val count = scanner.token()?.toIntOrNull() ?: return null
                    for (i in 0 until count) {
                        val offset = scanner.token()?.toIntOrNull() ?: return null
                        scanner.token() ?: return null                   // γενιά
                        val kind = scanner.token() ?: return null
                        // Ο νεότερος πίνακας υπερισχύει — και το «f» σημαίνει
                        // «διαγράφηκε», που επίσης δεν αναιρείται από παλιότερο.
                        if (known.add(first + i) && kind == "n") offsets[first + i] = offset
                    }
                }
                val dict = dictAt(text, scanner.at) ?: return null
                if (dict.contains("/Encrypt")) return null
                if (trailer == null) trailer = dict
                section = PREV.find(dict)?.groupValues?.get(1)?.toIntOrNull()
            }
            val newest = trailer ?: return null

            val root = ROOT_REF.find(newest)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val size = SIZE.find(newest)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            val catalog = objectAt(text, offsets[root] ?: return null) ?: return null
            val pagesNumber = PAGES_REF.find(catalog)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val pages = objectAt(text, offsets[pagesNumber] ?: return null) ?: return null
            if (!KIDS.containsMatchIn(pages) || !COUNT.containsMatchIn(pages)) return null

            return PdfBase(
                bytes = bytes,
                text = text,
                startxref = start,
                size = size,
                rootNumber = root,
                pagesNumber = pagesNumber,
                pagesDict = pages.trim(),
                info = INFO_REF.find(newest)?.groupValues?.get(1).orEmpty(),
                id = ID_ARRAY.find(newest)?.groupValues?.get(1).orEmpty(),
            )
        }

        /** Το σώμα του αντικειμένου που αρχίζει στη θέση [offset]. */
        private fun objectAt(text: String, offset: Int): String? {
            if (offset < 0 || offset >= text.length) return null
            val begin = text.indexOf("obj", offset)
            if (begin < 0) return null
            val end = text.indexOf("endobj", begin)
            if (end < 0) return null
            return text.substring(begin + 3, end)
        }

        /**
         * Το λεξικό που αρχίζει στο ή μετά το [from], με μέτρημα φωλιασμάτων.
         *
         * Το `/ID` του trailer είναι `[<...><...>]`: ένα απλό «βρες το πρώτο >>»
         * δουλεύει εδώ, αλλά όχι σε trailer με φωλιασμένο λεξικό. Μετράμε.
         */
        private fun dictAt(text: String, from: Int): String? {
            val open = text.indexOf("<<", from)
            if (open < 0) return null
            var depth = 0
            var i = open
            while (i < text.length - 1) {
                if (text.startsWith("<<", i)) { depth++; i += 2; continue }
                if (text.startsWith(">>", i)) {
                    depth--
                    i += 2
                    if (depth == 0) return text.substring(open, i)
                    continue
                }
                i++
            }
            return null
        }
    }

    /** Διαβάζει λέξεις χωρισμένες με κενά — όσο χρειάζεται για έναν πίνακα θέσεων. */
    private class Scanner(val text: String, var at: Int) {
        fun token(): String? {
            while (at < text.length && text[at].isWhitespace()) at++
            if (at >= text.length) return null
            val start = at
            while (at < text.length && !text[at].isWhitespace()) at++
            return text.substring(start, at)
        }
    }
}
