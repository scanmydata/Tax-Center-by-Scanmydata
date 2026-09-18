package gr.scanmydata.taxcenter.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.Inflater

/**
 * Το PDF που φεύγει στον πελάτη, ελεγμένο χωρίς Android.
 *
 * Ο έλεγχος εδώ δεν είναι διακοσμητικός: η εφαρμογή γράφει **η ίδια** τη δομή
 * του αρχείου, και μια λάθος θέση στον πίνακα `xref` δίνει PDF που ανοίγει σε
 * έναν viewer και όχι σε άλλον. Γι' αυτό ο έλεγχος ξαναδιαβάζει ό,τι γράφτηκε
 * και επαληθεύει τις θέσεις μία προς μία, αντί να κοιτάζει μόνο αν το αρχείο
 * υπάρχει.
 *
 * Οι γραμματοσειρές είναι οι **πραγματικές** (από τα assets): ένα ψεύτικο
 * δείγμα θα επαλήθευε τον έλεγχο, όχι το προϊόν.
 */
class PdfFileTest {

    private val fonts: ReportFonts by lazy {
        val dir = assetsDir()
        fun font(file: String, name: String) = PdfFont(
            name = name,
            program = File(dir, "$file.ttf").readBytes(),
            metricsJson = File(dir, "$file.json").readText(Charsets.UTF_8),
        )
        ReportFonts(font("report-regular", "DejaVuSans"), font("report-bold", "DejaVuSans-Bold"))
    }

    private val report = Report(
        fileName = "test.pdf",
        title = "Ανάλυση δόσεων οφειλής",
        office = "Λογιστικό Γραφείο",
        identity = listOf(
            "Υπόχρεος" to "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ · 123456783",
            "Οφειλή" to "ΧΡΕΩΣΤΙΚΕΣ ΔΗΛΩΣΕΙΣ ΦΠΑ",
        ),
        summary = listOf("Αριθμός δόσεων" to "2", "Υπόλοιπο" to "1.005,50 €"),
        sections = listOf(
            Section(
                caption = "Δόσεις",
                table = Table(
                    headers = listOf("Α/Α δόσης", "Ημ/νία λήξης δόσης", "Προσαυξήσεις, Τόκοι, Τέλη"),
                    rows = listOf(
                        listOf("1", "31/07/2026", "0,00 €"),
                        listOf("2", "31/08/2026", "5,50 €"),
                    ),
                    totals = listOf("ΣΥΝΟΛΑ", "", "5,50"),
                    numeric = listOf(2),
                ),
                note = "Τόνοι και εισαγωγικά: άέήίόύώ «ΑΑΔΕ» —",
            ),
        ),
        footer = listOf("Ενημερωτικό έντυπο του γραφείου."),
    )

    // ------------------------------------------------------- γραμματοσειρά

    @Test
    fun `κάθε ελληνικός χαρακτήρας έχει γλυφό και πλάτος`() {
        val greek = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρστυφχψως" +
            "άέήίόύώΐΰϊϋΆΈΉΊΌΎΏ 0123456789 €.,/·«»—…"
        val notdef = fonts.regular.gid('?'.code)
        for (ch in greek) {
            if (ch == '?') continue
            assertTrue("χωρίς γλυφό: $ch", fonts.regular.gid(ch.code) != notdef)
            assertTrue("χωρίς πλάτος: $ch", ch == ' ' || fonts.regular.width(ch.code) > 0)
        }
        // Το τελικό σίγμα δεν είναι το ίδιο γλυφό με το σίγμα.
        assertTrue(fonts.regular.gid('ς'.code) != fonts.regular.gid('σ'.code))
    }

    /**
     * Ο πίνακας `/W` πρέπει να δίνει ομάδες **συνεχόμενων** αριθμών. Όταν
     * γράφτηκε ως μία ενιαία ακολουθία, κάθε γλυφό μετά το πρώτο κενό έπαιρνε
     * το πλάτος του επόμενου: τα γράμματα έγραφαν το ένα πάνω στο άλλο.
     */
    @Test
    fun `τα πλάτη γράφονται σε ομάδες συνεχόμενων γλυφών`() {
        val w = fonts.regular.widths()
        assertTrue("κενός πίνακας πλατών", w.length > 100)
        val groups = Regex("""(\d+)\[([\d ]+)]""").findAll(w).toList()
        assertTrue("δεν βρέθηκαν ομάδες", groups.isNotEmpty())
        var previousEnd = -1
        for (g in groups) {
            val first = g.groupValues[1].toInt()
            val count = g.groupValues[2].trim().split(' ').size
            assertTrue("οι ομάδες πρέπει να είναι αύξουσες", first > previousEnd)
            previousEnd = first + count - 1
        }
        // Όλα τα γλυφά που αντιστοιχούν σε χαρακτήρα έχουν πλάτος — λιγότερα
        // από τους χαρακτήρες, γιατί το αδιάσπαστο κενό δείχνει στο ίδιο γλυφό
        // με το κανονικό.
        assertTrue(groups.sumOf { it.groupValues[2].trim().split(' ').size } > 200)
    }

    @Test
    fun `το ToUnicode γυρίζει από γλυφό πίσω σε χαρακτήρα`() {
        val map = fonts.regular.toUnicode()
        val alpha = fonts.regular.gid('Α'.code)
        assertTrue(map.contains("beginbfchar"))
        assertTrue(
            "λείπει η αντιστοίχιση του Α",
            map.contains("<" + hex4(alpha) + "> <0391>"),
        )
        // Χωρίς codespacerange ο viewer δεν ξέρει καν πόσα byte είναι ο κωδικός.
        assertTrue(map.contains("<0000> <FFFF>"))
    }

    // ------------------------------------------------------- νέο έγγραφο

    @Test
    fun `το έγγραφο ξαναδιαβάζεται και οι θέσεις του δείχνουν σωστά`() {
        val file = temp("solo.pdf")
        PdfFile.write(report, fonts, file)

        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))

        for ((number, offset) in xref(text)) {
            assertTrue(
                "το αντικείμενο $number δεν βρίσκεται στη θέση $offset",
                text.startsWith("$number 0 obj", offset),
            )
        }

        val base = PdfBase.read(bytes)
        assertNotNull("το ίδιο μας το αρχείο πρέπει να διαβάζεται", base)
        assertEquals(1, Regex("""/Count\s+(\d+)""").find(base!!.pagesDict)!!.groupValues[1].toInt())
    }

    @Test
    fun `το κείμενο γράφεται ως γλυφά, όχι ως εικόνα`() {
        val file = temp("text.pdf")
        PdfFile.write(report, fonts, file)
        val content = streams(file.readBytes()).joinToString("\n")

        // «Δόσεις»: ο τίτλος της ενότητας, σε δεκαεξαδικούς αριθμούς γλυφών.
        assertTrue("δεν βρέθηκε το κείμενο της ενότητας", content.contains(fonts.bold.hex("Δόσεις")))
        assertTrue("δεν βρέθηκε το υποσέλιδο", content.contains(fonts.regular.hex("Ενημερωτικό")))
        // Οι αριθμοί του PDF θέλουν τελεία — με ελληνικό locale θα έβγαινε κόμμα
        // και το αρχείο δεν θα άνοιγε καθόλου.
        assertTrue("λείπει το περιθώριο στις συντεταγμένες", content.contains(" 42.0 "))
        assertTrue("δεν επιτρέπονται κόμματα σε συντεταγμένες", !Regex("""\d+,\d+ Tm""").containsMatchIn(content))
    }

    @Test
    fun `οι εντολές κειμένου έχουν τη μορφή που ορίζει το πρότυπο`() {
        val file = temp("ops.pdf")
        PdfFile.write(report, fonts, file)
        val content = streams(file.readBytes()).first { it.contains("Tj") }

        // BT <χρώμα> rg /<γραμματοσειρά> <μέγεθος> Tf 1 0 0 1 <x> <y> Tm <γλυφά> Tj ET
        val draw = Regex(
            """BT \d\.\d+ \d\.\d+ \d\.\d+ rg /F[12] \d+(\.\d+)? Tf """ +
                """1 0 0 1 -?\d+(\.\d+)? -?\d+(\.\d+)? Tm <([0-9a-f]{4})+> Tj ET""",
        )
        val lines = content.trim().lines()
        val texts = lines.filter { it.startsWith("BT ") }
        assertTrue("δεν γράφτηκε καθόλου κείμενο", texts.size > 15)
        for (line in texts) assertTrue("κακοσχηματισμένη εντολή: " + line, draw.matches(line))

        // <x> <y> <πλάτος> <ύψος> re f — οι γραμμές και τα πλαίσια.
        val fills = Regex(
            """\d\.\d+ \d\.\d+ \d\.\d+ rg -?\d+(\.\d+)? -?\d+(\.\d+)? \d+(\.\d+)? \d+(\.\d+)? re f""",
        )
        for (line in lines.filter { it.endsWith("re f") }) {
            assertTrue("κακοσχηματισμένο σχήμα: " + line, fills.matches(line))
        }
    }

    /**
     * Ένας πίνακας 120 δόσεων σπάει σε σελίδες. Ο έλεγχος κοιτάζει τα δύο που
     * πονάνε στην πράξη: ότι δεν γράφεται τίποτα έξω από το χαρτί, και ότι κάθε
     * σελίδα ξαναγράφει την κεφαλίδα — αλλιώς η δεύτερη σελίδα είναι στήλες
     * αριθμών χωρίς όνομα.
     */
    @Test
    fun `ο πίνακας σπάει σε σελίδες και μένει μέσα στο χαρτί`() {
        val many = report.copy(
            sections = listOf(
                Section(
                    caption = "Δόσεις",
                    table = Table(
                        headers = listOf("Α/Α δόσης", "Ημ/νία λήξης δόσης", "Υπόλοιπο Δόσης"),
                        rows = (1..120).map { listOf(it.toString(), "31/07/2026", "1.000,00 €") },
                        numeric = listOf(2),
                    ),
                ),
            ),
        )
        val file = temp("long.pdf")
        PdfFile.write(many, fonts, file)
        val pages = streams(file.readBytes()).filter { it.contains("Tj") }
        assertTrue("ο πίνακας έπρεπε να σπάσει σε σελίδες", pages.size >= 3)

        val header = fonts.bold.hex("Υπόλοιπο")
        for ((index, page) in pages.withIndex()) {
            assertTrue("η σελίδα " + (index + 1) + " δεν ξαναγράφει την κεφαλίδα", page.contains(header))
        }

        val where = Regex("""1 0 0 1 (-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?) Tm""")
        for (match in where.findAll(pages.joinToString("\n"))) {
            val x = match.groupValues[1].toDouble()
            val y = match.groupValues[2].toDouble()
            assertTrue("κείμενο έξω από τη σελίδα: " + x + "," + y, x >= 40.0 && x <= 560.0)
            assertTrue("κείμενο έξω από τη σελίδα: " + x + "," + y, y >= 30.0 && y <= 805.0)
        }
    }

    // --------------------------------------------------------- προσάρτηση

    @Test
    fun `η προσάρτηση αφήνει το πρωτότυπο άθικτο και προσθέτει σελίδα`() {
        val file = temp("merge.pdf")
        PdfFile.write(report, fonts, file)
        val original = file.readBytes()

        assertTrue("η προσάρτηση απέτυχε", PdfFile.append(report, fonts, file))
        val merged = file.readBytes()

        // Το πρωτότυπο μένει byte προς byte — αυτό είναι όλο το νόημα.
        assertTrue(merged.size > original.size)
        assertEquals(
            String(original, Charsets.ISO_8859_1),
            String(merged.copyOfRange(0, original.size), Charsets.ISO_8859_1),
        )

        val base = PdfBase.read(merged)
        assertNotNull("το συνενωμένο αρχείο δεν ξαναδιαβάζεται", base)
        assertEquals(2, Regex("""/Count\s+(\d+)""").find(base!!.pagesDict)!!.groupValues[1].toInt())
        assertTrue("λείπει η αναφορά στον προηγούμενο πίνακα", String(merged, Charsets.ISO_8859_1).contains("/Prev "))

        for ((number, offset) in xref(String(merged, Charsets.ISO_8859_1)).filter { it.key >= 1 }) {
            val text = String(merged, Charsets.ISO_8859_1)
            assertTrue(
                "το αντικείμενο $number δεν βρίσκεται στη θέση $offset",
                text.startsWith("$number 0 obj", offset),
            )
        }
    }

    @Test
    fun `δεν αγγίζουμε αρχεία που δεν καταλαβαίνουμε`() {
        assertNull(PdfBase.read("δεν είναι PDF".toByteArray()))
        assertNull(PdfBase.read(ByteArray(0)))
        // Κρυπτογραφημένο: το περιεχόμενο θα έπρεπε να κρυπτογραφηθεί κι αυτό.
        val encrypted = ("%PDF-1.4\n1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n" +
            "xref\n0 1\n0000000000 65535 f \ntrailer\n<</Size 1/Root 1 0 R/Encrypt 9 0 R>>\n" +
            "startxref\n9\n%%EOF\n")
        assertNull(PdfBase.read(encrypted.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `αρχείο που δεν είναι PDF δεν αλλάζει`() {
        val file = temp("not.pdf")
        file.writeText("τίποτα")
        assertTrue(!PdfFile.append(report, fonts, file))
        assertEquals("τίποτα", file.readText())
    }

    // ------------------------------------------------------------- βοηθοί

    /** Ανεξάρτητη ανάγνωση του πίνακα θέσεων — όχι ο κώδικας που τον έγραψε. */
    private fun xref(text: String): Map<Int, Int> {
        val start = text.substring(text.lastIndexOf("startxref") + 9).trim()
            .takeWhile { it.isDigit() }.toInt()
        val out = LinkedHashMap<Int, Int>()
        val tokens = text.substring(start + 4).split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var i = 0
        while (i + 1 < tokens.size && tokens[i] != "trailer") {
            val first = tokens[i].toInt()
            val count = tokens[i + 1].toInt()
            i += 2
            for (n in 0 until count) {
                val offset = tokens[i].toInt()
                val kind = tokens[i + 2]
                if (kind == "n" && offset > 0) out[first + n] = offset
                i += 3
            }
        }
        return out
    }

    /** Οι αποσυμπιεσμένες ροές περιεχομένου του αρχείου. */
    private fun streams(bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val out = ArrayList<String>()
        var at = 0
        while (true) {
            val begin = text.indexOf("stream\n", at)
            if (begin < 0) break
            val end = text.indexOf("\nendstream", begin)
            if (end < 0) break
            val data = bytes.copyOfRange(begin + 7, end)
            runCatching { out += inflate(data) }
            at = end + 9
        }
        return out
    }

    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = ByteArray(64 * 1024)
        val sb = StringBuilder()
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n <= 0) break
            sb.append(String(buffer, 0, n, Charsets.ISO_8859_1))
        }
        inflater.end()
        return sb.toString()
    }

    private fun hex4(v: Int): String = v.toString(16).padStart(4, '0')

    private fun temp(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "taxcenter-test-$name").apply { delete() }

    private fun assetsDir(): File = listOf(
        File("src/main/assets/fonts"),
        File("app/src/main/assets/fonts"),
    ).firstOrNull { it.isDirectory } ?: error("δεν βρέθηκαν οι γραμματοσειρές από ${File(".").absolutePath}")
}
