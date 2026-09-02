package gr.scanmydata.taxcenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Τα δεδομένα εδώ είναι **συνθετικά**. Το πραγματικό «Κωδικοί_Υπόχρεων.xlsx»
 * περιέχει ζωντανούς κωδικούς πελατών και δεν μπαίνει ποτέ σε fixture.
 */
class XlsxImportTest {

    // ------------------------------------------------------------- Normalize

    @Test
    fun `οι τελείες σβήνονται, δεν γίνονται κενά`() {
        // Αν γίνονταν κενά, το «Α.Φ.Μ.» θα γινόταν «α φ μ» και δεν θα ταίριαζε.
        assertEquals("αφμ", Normalize.header("Α.Φ.Μ."))
        assertEquals("δου", Normalize.header("Δ.Ο.Υ."))
        assertEquals("αμκα", Normalize.header("Α.Μ.Κ.Α."))
    }

    @Test
    fun `τόνοι και τελικό σίγμα εξομαλύνονται`() {
        assertEquals("επωνυμια επωνυμο", Normalize.header("Επωνυμία/Επώνυμο"))
        assertEquals("ενεργοσ ανενεργοσ", Normalize.header("Ενεργός/Ανενεργός"))
        assertEquals("κλειδαριθμοσ", Normalize.header("Κλειδάριθμος"))
        assertEquals("ονομα χρηστη εργοδοτη ικα", Normalize.header("Όνομα χρήστη (Εργοδότη) Ι.Κ.Α."))
    }

    @Test
    fun `το ΑΦΜ καθαρίζεται από μορφοποίηση του Excel`() {
        assertEquals("999999999", Normalize.afm("999999999"))
        assertEquals("999999999", Normalize.afm("999999999.0"))
        assertEquals("999999999", Normalize.afm(" 999999999 "))
        // Το Excel τρώει το αρχικό μηδέν όταν το ΑΦΜ αποθηκευτεί ως αριθμός.
        assertEquals("047362769", Normalize.afm("47362769"))
        assertEquals("", Normalize.afm(""))
        assertEquals("", Normalize.afm("άσχετο"))
    }

    @Test
    fun `το ψηφίο ελέγχου ΑΦΜ λειτουργεί`() {
        assertTrue(Normalize.validAfm("123456783"))
        assertFalse(Normalize.validAfm("123456789"))
        assertFalse(Normalize.validAfm("000000000"))
        assertFalse(Normalize.validAfm("12345678"))
    }

    // ---------------------------------------------------------- ColumnAliases

    @Test
    fun `οι στήλες άλλων προϊόντων δεν χαρτογραφούνται ποτέ`() {
        // Το export έχει και στήλες που ανήκουν σε ΑΛΛΑ προγράμματα. Ένα
        // substring match στο «subscription key» θα άρπαζε το κλειδί του
        // e-timologio — το bug που θα έδινε 403 σε κάθε πελάτη, σιωπηλά.
        // Ο denylist μένει ακόμη κι αφού βγήκαν τα πεδία myDATA: οι στήλες
        // υπάρχουν στο αρχείο και πρέπει να αναφέρονται ως αγνοημένες.
        assertNull(ColumnAliases.match("Subscription key e-timologio"))
        assertNull(ColumnAliases.match("Όνομα χρήστη e-timologio"))
        assertNull(ColumnAliases.match("Συνθηματικό e-timologio"))
        assertNotNull(ColumnAliases.forbiddenReason("Subscription key e-timologio"))
    }

    @Test
    fun `οι στήλες myDATA δεν εισάγονται πια`() {
        // Τα πεδία myDATA αφαιρέθηκαν: καμία διαδικασία δεν τα χρησιμοποιεί,
        // και ένα κλειδί API που δεν χρειάζεται είναι μόνο ρίσκο.
        assertNull(ColumnAliases.match("Api myData"))
        assertNull(ColumnAliases.match("Όνομα χρήστη myData"))
        assertNull(ColumnAliases.match("Συνθηματικό myData"))
    }

    @Test
    fun `οι στήλες του προτύπου αντιστοιχίζονται σωστά`() {
        assertEquals(ColumnAliases.Field.AFM, ColumnAliases.match("Α.Φ.Μ."))
        assertEquals(ColumnAliases.Field.NAME, ColumnAliases.match("Επωνυμία/Επώνυμο"))
        assertEquals(ColumnAliases.Field.FIRST_NAME, ColumnAliases.match("Όνομα"))
        assertEquals(ColumnAliases.Field.TAXIS_USER, ColumnAliases.match("Όνομα χρήστη TAXISNET"))
        assertEquals(ColumnAliases.Field.TAXIS_PASS, ColumnAliases.match("Συνθηματικό TAXISNET"))
        assertEquals(ColumnAliases.Field.TAXIS_KLIDARITHMOS, ColumnAliases.match("Κλειδάριθμος"))
        assertEquals(ColumnAliases.Field.IKA_EMPLOYER_USER, ColumnAliases.match("Όνομα χρήστη (Εργοδότη) Ι.Κ.Α."))
    }

    @Test
    fun `άσχετες στήλες αγνοούνται — κρατάμε 14 από τις 83`() {
        assertNull(ColumnAliases.match("Όνομα χρήστη ΔΑΠΕΕΠ"))
        assertNull(ColumnAliases.match("PIN Ε.Ε. Αθηνών"))
        assertNull(ColumnAliases.match("Όνομα Πατρός"))
        assertNull(ColumnAliases.match("Κατ. Βιβλίων"))
    }

    @Test
    fun `ο δείκτης στήλης υπολογίζεται σωστά`() {
        assertEquals(0, ColumnAliases.columnIndex("A"))
        assertEquals(25, ColumnAliases.columnIndex("Z"))
        assertEquals(26, ColumnAliases.columnIndex("AA"))
        assertEquals(60, ColumnAliases.columnIndex("BI")) // «Api myData» στο πρότυπο
    }

    // ------------------------------------------------------------ XlsxReader

    @Test
    fun `διαβάζει φύλλο με ελληνικά, κενά κελιά και shared strings`() {
        val bytes = buildXlsx(
            headers = listOf("Α.Φ.Μ.", "Επωνυμία/Επώνυμο", "Όνομα χρήστη TAXISNET", "Συνθηματικό TAXISNET"),
            rows = listOf(
                listOf("123456783", "ΠΑΠΑΔΟΠΟΥΛΟΣ", "testuser1", "κωδικός1"),
                // κενή επωνυμία: το κελί λείπει τελείως από το XML
                listOf("999999999", "", "testuser2", "κωδικός2"),
            ),
        )
        val sheets = XlsxReader.read(ByteArrayInputStream(bytes))
        assertEquals(1, sheets.size)

        val rows = sheets[0].rows
        assertEquals(3, rows.size) // επικεφαλίδες + 2 γραμμές

        assertEquals("Α.Φ.Μ.", rows[0]["A"])
        assertEquals("Συνθηματικό TAXISNET", rows[0]["D"])

        assertEquals("123456783", rows[1]["A"])
        assertEquals("ΠΑΠΑΔΟΠΟΥΛΟΣ", rows[1]["B"])
        assertEquals("κωδικός1", rows[1]["D"])

        // Το κενό κελί απλώς δεν υπάρχει — δεν είναι κενό string.
        assertNull(rows[2]["B"])
        assertEquals("testuser2", rows[2]["C"])
    }

    @Test
    fun `η γραμμή επικεφαλίδων χαρτογραφείται σε πεδία`() {
        val headers = mapOf(
            "A" to "Κωδικός",
            "B" to "Α.Φ.Μ.",
            "C" to "Επωνυμία/Επώνυμο",
            "BI" to "Api myData",
            "BL" to "Subscription key e-timologio",
        )
        val map = ColumnAliases.mapHeaderRow(headers)
        assertEquals(ColumnAliases.Field.AFM, map["B"])
        assertEquals(ColumnAliases.Field.NAME, map["C"])
        assertNull("οι στήλες myDATA δεν εισάγονται πια", map["BI"])
        assertNull("η στήλη του e-timologio δεν πρέπει να χαρτογραφηθεί", map["BL"])
        assertNull("η στήλη Κωδικός δεν μας αφορά", map["A"])
    }

    @Test
    fun `αρχείο που δεν είναι xlsx δίνει κατανοητό μήνυμα`() {
        val notZip = "Α.Φ.Μ.;Επωνυμία\n123456783;ΔΟΚΙΜΗ\n".toByteArray()
        val e = runCatching { XlsxReader.read(ByteArrayInputStream(notZip)) }.exceptionOrNull()
        assertTrue("περίμενα NotAnXlsxException, πήρα $e", e is XlsxReader.NotAnXlsxException)
        assertTrue(e!!.message!!.contains(".xlsx"))
    }

    // ------------------------------------------------------------------ utils

    /** Χτίζει ελάχιστο, έγκυρο .xlsx με shared strings — όπως το παράγουν τα προγράμματα. */
    private fun buildXlsx(headers: List<String>, rows: List<List<String>>): ByteArray {
        val all = ArrayList<String>()
        val index = HashMap<String, Int>()
        fun idx(s: String): Int = index.getOrPut(s) { all.add(s); all.size - 1 }

        val sheet = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>""")
        fun emit(rowNum: Int, values: List<String>) {
            sheet.append("""<row r="$rowNum">""")
            values.forEachIndexed { i, v ->
                if (v.isEmpty()) return@forEachIndexed // το Excel παραλείπει τα κενά κελιά
                val col = ('A' + i).toString()
                sheet.append("""<c t="s" r="$col$rowNum"><v>${idx(v)}</v></c>""")
            }
            sheet.append("</row>")
        }
        emit(1, headers)
        rows.forEachIndexed { i, r -> emit(i + 2, r) }
        sheet.append("</sheetData></worksheet>")

        val shared = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><sst>""")
        all.forEach { shared.append("<si><t>").append(escape(it)).append("</t></si>") }
        shared.append("</sst>")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
