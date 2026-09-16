package gr.scanmydata.taxcenter.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Τα διανύσματα ελέγχου του κωδικοποιητή QR.
 *
 * Δεν είναι «δικές μας» τιμές: παρήχθησαν από πρωτότυπο σε JS που συγκρίθηκε
 * **μονάδα προς μονάδα** με τη βιβλιοθήκη αναφοράς `qrcode` και του οποίου κάθε
 * έξοδος αποκωδικοποιήθηκε με τον `jsQR` — 253 περιπτώσεις, εκδόσεις 1 έως 10,
 * και οι οκτώ μάσκες, 253 στις 253 διαβάστηκαν σωστά.
 *
 * Ένας κώδικας QR είτε διαβάζεται είτε όχι· δεν υπάρχει «σχεδόν». Χωρίς αυτά τα
 * διανύσματα, μια αλλαγή που τον χαλάει θα φαινόταν μόνο όταν κάποιος έστρεφε
 * το κινητό του πάνω στην οθόνη ενός άλλου ανθρώπου.
 */
class QrCodeTest {

    private fun render(symbol: QrCode.Symbol): List<String> =
        (0 until symbol.size).map { y ->
            (0 until symbol.size).joinToString("") { x -> if (symbol[x, y]) "#" else "." }
        }

    private fun fingerprint(symbol: QrCode.Symbol): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(render(symbol).joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    @Test
    fun `μικρό κείμενο δίνει ακριβώς το αναμενόμενο πλέγμα`() {
        val expected = listOf(
            "#######..###..#######",
            "#.....#.##....#.....#",
            "#.###.#...###.#.###.#",
            "#.###.#...#...#.###.#",
            "#.###.#.#...#.#.###.#",
            "#.....#.....#.#.....#",
            "#######.#.#.#.#######",
            "...........##........",
            "#.#.#.#..#.#....#..#.",
            ".#.###.#.##....###.##",
            "#.#.###.###.#########",
            "#...##.#.##....#...#.",
            "#..####.#...##.###.##",
            "........#.##.#..###.#",
            "#######..###...##.###",
            "#.....#....###.##....",
            "#.###.#.##.#..##.....",
            "#.###.#..#....####.#.",
            "#.###.#.###.#..###..#",
            "#.....#...#...#....#.",
            "#######.#...#.###..##",
        )
        val symbol = QrCode.encode("taxcenter")
        assertEquals(1, symbol.version)
        assertEquals(expected, render(symbol))
    }

    @Test
    fun `ο σύνδεσμος του τελευταίου release`() {
        val symbol = QrCode.encode(
            "https://github.com/scanmydata/Tax-Center-by-Scanmydata/releases/latest",
        )
        assertEquals(5, symbol.version)
        assertEquals(37, symbol.size)
        assertEquals("bc27a655a9e45053", fingerprint(symbol))
    }

    @Test
    fun `ο απευθείας σύνδεσμος του APK`() {
        val symbol = QrCode.encode(
            "https://github.com/scanmydata/Tax-Center-by-Scanmydata/releases/download/" +
                "v0.2.5/TaxCenter-v0.2.5.apk",
        )
        assertEquals(6, symbol.version)
        assertEquals(41, symbol.size)
        assertEquals("b8ff3598da0d2909", fingerprint(symbol))
    }

    /**
     * Τα ελληνικά είναι δύο byte το καθένα στο UTF-8. Αν η κωδικοποίηση περνούσε
     * από χαρακτήρες αντί για byte, το μήκος θα δηλωνόταν λάθος και ο κώδικας θα
     * ήταν άχρηστος — χωρίς να φαίνεται τίποτα στην οθόνη.
     */
    @Test
    fun `ελληνικά κωδικοποιούνται ως UTF-8`() {
        val symbol = QrCode.encode("Ελληνικά — δοκιμή UTF-8")
        assertEquals(3, symbol.version)
        assertEquals("167f566c4f258ac2", fingerprint(symbol))
    }

    @Test
    fun `η έκδοση μεγαλώνει με το μήκος`() {
        assertEquals(1, QrCode.encode("a".repeat(14)).version)
        assertEquals(2, QrCode.encode("a".repeat(20)).version)
        assertEquals(10, QrCode.encode("a".repeat(210)).version)
    }

    @Test
    fun `πολύ μεγάλο κείμενο απορρίπτεται αντί να κοπεί`() {
        val problem = runCatching { QrCode.encode("a".repeat(400)) }.exceptionOrNull()
        assertTrue("έπρεπε να πετάξει εξαίρεση", problem is IllegalArgumentException)
    }

    /**
     * Οι τρεις ανιχνευτές θέσης είναι αυτό που βρίσκει πρώτα ο σαρωτής. Αν
     * λείψουν ή μετακινηθούν, δεν υπάρχει καν κώδικας να διαβαστεί.
     */
    @Test
    fun `οι ανιχνευτές θέσης είναι στη θέση τους`() {
        val symbol = QrCode.encode("https://example.gr/")
        val n = symbol.size
        for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (y in 0..6) {
                for (x in 0..6) {
                    val ring = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
                    assertEquals(
                        "ανιχνευτής στο ($ox,$oy) μονάδα ($x,$y)",
                        ring != 2,
                        symbol[ox + x, oy + y],
                    )
                }
            }
        }
    }
}
