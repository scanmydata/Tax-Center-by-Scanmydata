package gr.scanmydata.taxcenter.update

/**
 * Κωδικοποιητής QR — byte mode, επίπεδο διόρθωσης **M**, εκδόσεις 1 έως 10.
 *
 * Υπάρχει για έναν λόγο: να δείχνει η εφαρμογή τον σύνδεσμο λήψης της ίδιας της
 * εφαρμογής, ώστε ένας δεύτερος λογιστής να τη σκανάρει και να την εγκαταστήσει
 * χωρίς να του τη στείλει κανείς. Η διανομή γίνεται με sideload από GitHub
 * Releases, οπότε ο σύνδεσμος είναι το μόνο που χρειάζεται να ταξιδέψει.
 *
 * **Γιατί χειρόγραφος και όχι βιβλιοθήκη:** η μόνη εναλλακτική είναι το ZXing,
 * που φέρνει και ολόκληρο τον αποκωδικοποιητή για δουλειά που εδώ είναι 200
 * γραμμές. Η ίδια λογική με τον `XlsxReader`, που απέφυγε το Apache POI.
 *
 * **Πώς επαληθεύτηκε:** ο αλγόριθμος γράφτηκε πρώτα σε JS και συγκρίθηκε
 * πλέγμα-προς-πλέγμα με τη βιβλιοθήκη αναφοράς `qrcode`, και κάθε παραγόμενος
 * κώδικας αποκωδικοποιήθηκε με τον **jsQR** — 253 περιπτώσεις, όλες οι εκδόσεις
 * 1..10 και όλες οι μάσκες, 253/253 διαβάστηκαν σωστά. Το [QrCodeTest] κρατά τα
 * διανύσματα εκείνης της επαλήθευσης, ώστε μια μελλοντική «βελτίωση» που θα
 * χαλούσε την κωδικοποίηση να μη φτάσει ποτέ σε release.
 *
 * Η επιλογή μάσκας μπορεί να διαφέρει από άλλες υλοποιήσεις όταν δύο μάσκες
 * βαθμολογούνται σχεδόν ίδια. Δεν έχει σημασία για την ανάγνωση: η μάσκα
 * δηλώνεται μέσα στην πληροφορία μορφής και ο σαρωτής την ακολουθεί.
 */
object QrCode {

    /** Το αποτέλεσμα: τετράγωνο πλέγμα, `true` = σκούρη μονάδα. */
    class Symbol(val version: Int, val modules: Array<BooleanArray>) {
        val size: Int get() = modules.size
        operator fun get(x: Int, y: Int): Boolean = modules[y][x]
    }

    /**
     * Πόσα byte χωρούν το πολύ — πάνω από αυτά η [encode] πετά εξαίρεση.
     *
     * Είναι η χωρητικότητα της έκδοσης 10 μείον την κεφαλίδα. Οι σύνδεσμοι που
     * δείχνει η εφαρμογή είναι κάτω από 110 byte, οπότε το όριο δεν πλησιάζεται
     * ποτέ· υπάρχει για να μην περάσει σιωπηλά κάτι κομμένο.
     */
    const val MAX_BYTES = 212

    // ---------------------------------------------------------------- GF(256)

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Το πολυώνυμο γεννήτορας για [n] codewords διόρθωσης. */
    private fun generator(n: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until n) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor mul(poly[j], EXP[i])
            }
            poly = next
        }
        return poly
    }

    /** Το υπόλοιπο της διαίρεσης — τα codewords διόρθωσης ενός μπλοκ. */
    private fun ecCodewords(data: IntArray, n: Int): IntArray {
        val gen = generator(n)
        val res = IntArray(data.size + n)
        data.copyInto(res)
        for (i in data.indices) {
            val factor = res[i]
            if (factor == 0) continue
            for (j in gen.indices) res[i + j] = res[i + j] xor mul(gen[j], factor)
        }
        return res.copyOfRange(data.size, res.size)
    }

    // ------------------------------------------------- πίνακες, επίπεδο M

    /** Ανά έκδοση: codewords διόρθωσης ανά μπλοκ, μπλοκ/δεδομένα ομάδας 1 και 2. */
    private val TABLE = arrayOf(
        intArrayOf(10, 1, 16, 0, 0),   // 1
        intArrayOf(16, 1, 28, 0, 0),   // 2
        intArrayOf(26, 1, 44, 0, 0),   // 3
        intArrayOf(18, 2, 32, 0, 0),   // 4
        intArrayOf(24, 2, 43, 0, 0),   // 5
        intArrayOf(16, 4, 27, 0, 0),   // 6
        intArrayOf(18, 4, 31, 0, 0),   // 7
        intArrayOf(22, 2, 38, 2, 39),  // 8
        intArrayOf(22, 3, 36, 2, 37),  // 9
        intArrayOf(26, 4, 43, 1, 44),  // 10
    )

    private val ALIGN = arrayOf(
        intArrayOf(),                  // 1
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),         // 10
    )

    /** Πληροφορία έκδοσης (18 bit, BCH) — μόνο από την έκδοση 7 και πάνω. */
    private val VERSION_BITS = mapOf(7 to 0x07c94, 8 to 0x085bc, 9 to 0x09a99, 10 to 0x0a4d3)

    private fun dataCodewords(version: Int): Int {
        val t = TABLE[version - 1]
        return t[1] * t[2] + t[3] * t[4]
    }

    // ------------------------------------------------------------ κωδικοποίηση

    fun encode(text: String): Symbol {
        val bytes = text.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }

        // Ο δείκτης μήκους είναι 8 bit ως την έκδοση 9 και 16 από τη 10, δηλαδή
        // 2 ή 4 byte κεφαλίδα μαζί με τον δείκτη λειτουργίας.
        var version = 0
        for (v in 1..10) {
            val header = if (v >= 10) 4 else 2
            if (bytes.size + header <= dataCodewords(v)) { version = v; break }
        }
        require(version != 0) { "το κείμενο δεν χωρά σε QR έκδοσης 10" }

        val bits = ArrayList<Int>(dataCodewords(version) * 8)
        fun push(value: Int, n: Int) {
            for (i in n - 1 downTo 0) bits.add((value shr i) and 1)
        }

        push(0b0100, 4)                                       // byte mode
        push(bytes.size, if (version >= 10) 16 else 8)
        for (b in bytes) push(b, 8)

        val capacity = dataCodewords(version) * 8
        var i = 0
        while (i < 4 && bits.size < capacity) { bits.add(0); i++ }
        while (bits.size % 8 != 0) bits.add(0)

        val data = ArrayList<Int>(dataCodewords(version))
        var p = 0
        while (p < bits.size) {
            var byte = 0
            for (j in 0 until 8) byte = (byte shl 1) or bits[p + j]
            data.add(byte)
            p += 8
        }
        // Γέμισμα με τα δύο καθορισμένα byte, εναλλάξ.
        val pad = intArrayOf(0xec, 0x11)
        var k = 0
        while (data.size < dataCodewords(version)) { data.add(pad[k % 2]); k++ }

        // --- Μπλοκ και πλέξιμο ------------------------------------------------
        //
        // Τα codewords δεν μπαίνουν στη σειρά: παίρνονται ένα από κάθε μπλοκ με
        // τη σειρά. Έτσι μια γρατζουνιά στο χαρτί χτυπά λίγα codewords σε **κάθε**
        // μπλοκ αντί να καταστρέψει ένα ολόκληρο.
        val t = TABLE[version - 1]
        val ecLen = t[0]
        val blocks = ArrayList<IntArray>()
        var at = 0
        repeat(t[1]) { blocks.add(data.subList(at, at + t[2]).toIntArray()); at += t[2] }
        repeat(t[3]) { blocks.add(data.subList(at, at + t[4]).toIntArray()); at += t[4] }
        val ecBlocks = blocks.map { ecCodewords(it, ecLen) }

        val out = ArrayList<Int>()
        for (col in 0 until maxOf(t[2], t[4])) {
            for (b in blocks) if (col < b.size) out.add(b[col])
        }
        for (col in 0 until ecLen) {
            for (b in ecBlocks) out.add(b[col])
        }

        return Symbol(version, buildMatrix(version, out))
    }

    // ------------------------------------------------------------------ πλέγμα

    private fun buildMatrix(version: Int, codewords: List<Int>): Array<BooleanArray> {
        val size = version * 4 + 17
        val m = Array(size) { IntArray(size) { -1 } }
        val fixed = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, v: Int) {
            m[y][x] = v
            fixed[y][x] = true
        }

        // Ανιχνευτές θέσης μαζί με τα διαχωριστικά τους.
        for ((ox, oy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
            for (y in -1..7) {
                for (x in -1..7) {
                    val px = ox + x
                    val py = oy + y
                    if (px < 0 || py < 0 || px >= size || py >= size) continue
                    val ring = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
                    set(px, py, if (ring == 2 || ring > 3) 0 else 1)
                }
            }
        }

        // Γραμμές χρονισμού.
        for (i in 8 until size - 8) {
            set(i, 6, if (i % 2 == 0) 1 else 0)
            set(6, i, if (i % 2 == 0) 1 else 0)
        }

        // Μοτίβα ευθυγράμμισης — ποτέ πάνω σε ανιχνευτή θέσης.
        val centres = ALIGN[version - 1]
        for (cy in centres) {
            for (cx in centres) {
                if (cx == 6 && cy == 6) continue
                if (cx == 6 && cy == size - 7) continue
                if (cx == size - 7 && cy == 6) continue
                for (y in -2..2) {
                    for (x in -2..2) {
                        set(cx + x, cy + y, if (maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) == 1) 0 else 1)
                    }
                }
            }
        }

        // Η μονάδα που είναι πάντα σκούρη.
        set(8, size - 8, 1)

        // Δέσμευση των θέσεων για πληροφορία μορφής και έκδοσης, ώστε να μην
        // πέσουν εκεί δεδομένα.
        for (i in 0 until 9) {
            if (m[8][i] == -1) set(i, 8, 0)
            if (m[i][8] == -1) set(8, i, 0)
        }
        for (i in 0 until 8) {
            if (m[8][size - 1 - i] == -1) set(size - 1 - i, 8, 0)
            if (m[size - 1 - i][8] == -1) set(8, size - 1 - i, 0)
        }
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    set(size - 11 + j, i, 0)
                    set(i, size - 11 + j, 0)
                }
            }
        }

        // --- Τοποθέτηση δεδομένων σε ζιγκ-ζαγκ -------------------------------
        var bitIndex = 0
        fun nextBit(): Int {
            val i = bitIndex shr 3
            val bit = if (i < codewords.size) (codewords[i] shr (7 - (bitIndex and 7))) and 1 else 0
            bitIndex++
            return bit
        }
        var upward = true
        var right = size - 1
        while (right >= 1) {
            // Η στήλη 6 είναι χρονισμός: το ζεύγος γίνεται 5-4 και συνεχίζει.
            val col = if (right == 6) 5 else right
            for (i in 0 until size) {
                val y = if (upward) size - 1 - i else i
                for (x in intArrayOf(col, col - 1)) {
                    if (fixed[y][x]) continue
                    m[y][x] = nextBit()
                }
            }
            upward = !upward
            if (right == 6) right--
            right -= 2
        }

        // --- Μάσκα: δοκιμάζονται και οι οκτώ, κερδίζει η λιγότερο «θορυβώδης»
        var bestScore = Int.MAX_VALUE
        var best: Array<IntArray>? = null
        for (mask in 0 until 8) {
            val candidate = Array(size) { y -> m[y].copyOf() }
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (fixed[y][x]) continue
                    if (maskAt(mask, x, y)) candidate[y][x] = candidate[y][x] xor 1
                }
            }
            writeFormat(candidate, mask)
            if (version >= 7) writeVersion(candidate, version)
            val score = penalty(candidate)
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }

        val chosen = best!!
        return Array(size) { y -> BooleanArray(size) { x -> chosen[y][x] == 1 } }
    }

    private fun maskAt(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> ((y / 2) + (x / 3)) % 2 == 0
        5 -> ((x * y) % 2) + ((x * y) % 3) == 0
        6 -> (((x * y) % 2) + ((x * y) % 3)) % 2 == 0
        else -> (((x + y) % 2) + ((x * y) % 3)) % 2 == 0
    }

    /** BCH(15,5) για την πληροφορία μορφής, με τη μάσκα 0x5412 του προτύπου. */
    private fun formatBits(mask: Int): Int {
        val data = (0b00 shl 3) or mask          // 00 = επίπεδο M
        var rem = data shl 10
        for (i in 4 downTo 0) {
            if ((rem shr (10 + i)) and 1 == 1) rem = rem xor (0b10100110111 shl i)
        }
        return ((data shl 10) or rem) xor 0b101010000010010
    }

    private fun writeFormat(m: Array<IntArray>, mask: Int) {
        val bits = formatBits(mask)
        val size = m.size
        fun bit(i: Int) = (bits shr i) and 1

        // Πρώτο αντίγραφο, γύρω από τον πάνω-αριστερά ανιχνευτή. Οι δείκτες
        // είναι [γραμμή][στήλη]: το ανάποδο έβγαζε QR που δεν διαβαζόταν.
        for (i in 0..5) m[i][8] = bit(i)
        m[7][8] = bit(6)
        m[8][8] = bit(7)
        m[8][7] = bit(8)
        for (i in 9..14) m[8][14 - i] = bit(i)

        // Δεύτερο αντίγραφο, ώστε ο κώδικας να διαβάζεται και με κατεστραμμένη γωνία.
        for (i in 0..7) m[8][size - 1 - i] = bit(i)
        for (i in 8..14) m[size - 15 + i][8] = bit(i)
        m[size - 8][8] = 1
    }

    private fun writeVersion(m: Array<IntArray>, version: Int) {
        val bits = VERSION_BITS.getValue(version)
        val size = m.size
        for (i in 0 until 18) {
            val bit = (bits shr i) and 1
            val a = i / 3
            val b = i % 3
            m[a][size - 11 + b] = bit
            m[size - 11 + b][a] = bit
        }
    }

    // --------------------------------------------------- ποινές επιλογής μάσκας

    private fun penalty(m: Array<IntArray>): Int {
        val size = m.size
        var score = 0

        fun row(i: Int) = m[i]
        fun col(i: Int) = IntArray(size) { m[it][i] }

        // 1. Σειρές πέντε ή περισσότερων ίδιων μονάδων.
        for (i in 0 until size) {
            for (line in listOf(row(i), col(i))) {
                var run = 1
                for (j in 1 until size) {
                    if (line[j] == line[j - 1]) {
                        run++
                    } else {
                        if (run >= 5) score += run - 2
                        run = 1
                    }
                }
                if (run >= 5) score += run - 2
            }
        }

        // 2. Τετράγωνα 2x2 ίδιου χρώματος.
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val v = m[y][x]
                if (v == m[y][x + 1] && v == m[y + 1][x] && v == m[y + 1][x + 1]) score += 3
            }
        }

        // 3. Το μοτίβο του ανιχνευτή θέσης μέσα στα δεδομένα — μπερδεύει τον σαρωτή.
        val a = intArrayOf(1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0)
        val b = intArrayOf(0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1)
        fun matches(line: IntArray, at: Int, pattern: IntArray): Boolean {
            for (k in pattern.indices) if (line[at + k] != pattern[k]) return false
            return true
        }
        for (i in 0 until size) {
            for (line in listOf(row(i), col(i))) {
                for (j in 0..size - 11) {
                    if (matches(line, j, a)) score += 40
                    if (matches(line, j, b)) score += 40
                }
            }
        }

        // 4. Απόκλιση από την ισορροπία σκούρου/ανοιχτού.
        var dark = 0
        for (r in m) for (v in r) dark += v
        val percent = dark * 100.0 / (size * size)
        score += (kotlin.math.abs(percent - 50) / 5).toInt() * 10

        return score
    }
}
