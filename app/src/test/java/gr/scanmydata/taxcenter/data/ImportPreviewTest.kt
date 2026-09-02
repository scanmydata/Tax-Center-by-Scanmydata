package gr.scanmydata.taxcenter.data

import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Συνθετικά δεδομένα — ποτέ το πραγματικό αρχείο κωδικών. */
class ImportPreviewTest {

    private val headers = mapOf(
        "A" to "Κωδικός",
        "B" to "Α.Φ.Μ.",
        "C" to "Επωνυμία/Επώνυμο",
        "D" to "Όνομα",
        "M" to "Όνομα χρήστη TAXISNET",
        "N" to "Συνθηματικό TAXISNET",
        "BG" to "Όνομα χρήστη myData",
        "BI" to "Api myData",
        "BL" to "Subscription key e-timologio",
    )

    private fun sheet(vararg rows: Map<String, String>) =
        listOf(XlsxReader.Sheet("sheet1", listOf(headers) + rows.toList()))

    @Test
    fun `νέος πελάτης με κωδικούς TAXISnet`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456783", "C" to "ΠΑΠΑΔΟΠΟΥΛΟΣ", "M" to "user1", "N" to "pass1")),
            existingAfms = emptySet(),
        )
        assertEquals(1, result.rows.size)
        val row = result.rows.single()
        assertEquals(ImportPreview.Action.NEW, row.action)
        assertEquals("123456783", row.afm)
        assertTrue(row.hasTaxisCredentials)
        assertEquals(1, result.newCount)
        assertEquals(1, result.withTaxis)
        assertTrue("δεν περίμενα προειδοποιήσεις: ${row.warnings}", row.warnings.isEmpty())
    }

    @Test
    fun `υπάρχων πελάτης με νέα δεδομένα είναι ενημέρωση`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456783", "C" to "ΠΑΠΑΔΟΠΟΥΛΟΣ", "N" to "νέος κωδικός")),
            existingAfms = setOf("123456783"),
        )
        assertEquals(ImportPreview.Action.UPDATE, result.rows.single().action)
    }

    @Test
    fun `υπάρχων πελάτης χωρίς δεδομένα είναι αμετάβλητος`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456783")),
            existingAfms = setOf("123456783"),
        )
        assertEquals(ImportPreview.Action.UNCHANGED, result.rows.single().action)
        assertEquals(1, result.unchangedCount)
    }

    @Test
    fun `διπλές γραμμές συγχωνεύονται με την τελευταία μη κενή τιμή`() {
        val result = ImportPreview.build(
            sheet(
                mapOf("B" to "123456783", "C" to "ΠΑΠΑΔΟΠΟΥΛΟΣ", "M" to "user1"),
                // δεύτερη γραμμή: κενή επωνυμία, νέος κωδικός
                mapOf("B" to "123456783", "N" to "pass2"),
            ),
            existingAfms = emptySet(),
        )
        assertEquals("συγχωνεύτηκαν σε μία γραμμή", 1, result.rows.size)
        val row = result.rows.single()
        // Η κενή επωνυμία της 2ης γραμμής ΔΕΝ έσβησε την τιμή της 1ης.
        assertEquals("ΠΑΠΑΔΟΠΟΥΛΟΣ", row.values[Field.NAME])
        assertEquals("user1", row.values[Field.TAXIS_USER])
        assertEquals("pass2", row.values[Field.TAXIS_PASS])
        assertTrue(row.warnings.any { it.contains("2 γραμμές") })
    }

    @Test
    fun `το κλειδί του e-timologio δεν μπαίνει ποτέ ως myDATA`() {
        val result = ImportPreview.build(
            sheet(
                mapOf(
                    "B" to "123456783",
                    "BG" to "aadeuser",
                    "BI" to "0123456789abcdef0123456789abcdef",
                    "BL" to "ffffffffffffffffffffffffffffffff", // e-timologio!
                ),
            ),
            existingAfms = emptySet(),
        )
        val row = result.rows.single()
        assertEquals("0123456789abcdef0123456789abcdef", row.values[Field.MYDATA_KEY])
        assertTrue(
            "η στήλη e-timologio έπρεπε να αναφερθεί ως αγνοημένη",
            result.ignoredColumns.any { it.first.contains("e-timologio") },
        )
    }

    @Test
    fun `κλειδί myDATA με λάθος μορφή πετιέται με προειδοποίηση`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456783", "BG" to "aadeuser", "BI" to "όχι-κλειδί")),
            existingAfms = emptySet(),
        )
        val row = result.rows.single()
        assertNull("το άκυρο κλειδί έπρεπε να αφαιρεθεί", row.values[Field.MYDATA_KEY])
        assertTrue(row.warnings.any { it.contains("32 δεκαεξαδικά") })
    }

    @Test
    fun `ύποπτο ΑΦΜ σημαίνεται αλλά δεν απορρίπτεται`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456789", "C" to "ΔΟΚΙΜΗ")),
            existingAfms = emptySet(),
        )
        assertEquals("η γραμμή έπρεπε να εισαχθεί", 1, result.rows.size)
        assertTrue(result.rows.single().warnings.any { it.contains("ψηφίου") })
    }

    @Test
    fun `γραμμές χωρίς ΑΦΜ μετρώνται ως παραλειπόμενες`() {
        val result = ImportPreview.build(
            sheet(
                mapOf("C" to "ΧΩΡΙΣ ΑΦΜ"),
                mapOf("B" to "123456783", "C" to "ΕΓΚΥΡΟΣ"),
                mapOf("B" to "   "),
            ),
            existingAfms = emptySet(),
        )
        assertEquals(1, result.rows.size)
        assertEquals(2, result.skippedRows)
    }

    @Test
    fun `χρήστης TAXISnet χωρίς συνθηματικό προειδοποιεί`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456783", "M" to "user1")),
            existingAfms = emptySet(),
        )
        val row = result.rows.single()
        assertFalse(row.hasTaxisCredentials)
        assertTrue(row.warnings.any { it.contains("χωρίς συνθηματικό") })
    }

    @Test
    fun `οι επικεφαλίδες βρίσκονται και όταν δεν είναι στην πρώτη γραμμή`() {
        val sheets = listOf(
            XlsxReader.Sheet(
                "sheet1",
                listOf(
                    mapOf("A" to "ΚΑΤΑΣΤΑΣΗ ΚΩΔΙΚΩΝ ΥΠΟΧΡΕΩΝ"),
                    emptyMap(),
                    headers,
                    mapOf("B" to "123456783", "C" to "ΠΑΠΑΔΟΠΟΥΛΟΣ"),
                ),
            ),
        )
        val result = ImportPreview.build(sheets, emptySet())
        assertEquals(1, result.rows.size)
        assertEquals("ΠΑΠΑΔΟΠΟΥΛΟΣ", result.rows.single().values[Field.NAME])
    }

    @Test
    fun `χρησιμοποιείται το πρώτο φύλλο που έχει στήλη ΑΦΜ`() {
        val sheets = listOf(
            XlsxReader.Sheet("άσχετο", listOf(mapOf("A" to "Τίτλος", "B" to "Σημειώσεις"))),
            XlsxReader.Sheet("κωδικοί", listOf(headers, mapOf("B" to "123456783"))),
        )
        val result = ImportPreview.build(sheets, emptySet())
        assertEquals("κωδικοί", result.sheetName)
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `αρχείο χωρίς στήλη ΑΦΜ δίνει κατανοητό σφάλμα`() {
        val sheets = listOf(XlsxReader.Sheet("s", listOf(mapOf("A" to "Ονοματεπώνυμο", "B" to "Τηλέφωνο"))))
        val e = assertThrows(ImportPreview.NoUsableSheetException::class.java) {
            ImportPreview.build(sheets, emptySet())
        }
        assertTrue(e.message!!.contains("Α.Φ.Μ."))
    }

    @Test
    fun `η προεπισκόπηση δεν αποκαλύπτει κωδικούς`() {
        val result = ImportPreview.build(
            sheet(mapOf("B" to "123456783", "C" to "ΠΑΠΑΔΟΠΟΥΛΟΣ", "M" to "user1", "N" to "μυστικό-κωδικός")),
            existingAfms = emptySet(),
        )
        val row = result.rows.single()
        val shown = row.masked(Field.TAXIS_PASS)
        assertFalse("ο κωδικός φάνηκε: $shown", shown.contains("μυστικό-κωδικός"))
        // Το όνομα χρήστη ΔΕΝ είναι μυστικό — ο λογιστής πρέπει να το βλέπει.
        assertEquals("user1", row.masked(Field.TAXIS_USER))
    }
}
