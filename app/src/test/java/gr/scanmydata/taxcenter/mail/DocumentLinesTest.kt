package gr.scanmydata.taxcenter.mail

import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.debts.Debts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Η λίστα των συνημμένων στο email — και ειδικά των οφειλών.
 *
 * Το σημείο εκκίνησης ήταν ένα πραγματικό μήνυμα με πέντε γραμμές «Ταυτότητα
 * οφειλής (OFEILI_….pdf)», όλες ίδιες. Ό,τι ελέγχεται εδώ είναι ότι ο πελάτης
 * διαβάζει **τι** είναι κάθε συνημμένο, **πόσο**, **μέχρι πότε** και **με ποιον
 * κωδικό** — και ότι δεν του γράφουμε ποτέ δόση ή λήξη που δεν ξέρουμε.
 *
 * Όλα τα ποσά, οι κωδικοί και τα ΑΦΜ είναι συνθετικά.
 */
class DocumentLinesTest {

    private val today = LocalDate.of(2026, 10, 2)

    private val enfia = Debts.Line(
        id = "aade-open-0",
        group = Debts.Group.AADE_OPEN,
        title = "ΕΝ.Φ.Ι.Α. Ν.4223/2013",
        detail = "ΗΛΙΟΥΠΟΛΗΣ · έτος 2026",
        total = "211,10",
        code = "123456783 900000001 200000000001",
        unpaid = listOf(
            Debts.Instalment("3", "30/09/2026", "52,78"),
            Debts.Instalment("4", "30/10/2026", "52,78"),
            Debts.Instalment("5", "30/11/2026", "52,77"),
        ),
        instalments = 5,
        files = listOf("OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_211,10.pdf"),
    )

    private val carrier = Debts.Line(
        id = "keao-0",
        group = Debts.Group.KEAO_CARRIER,
        title = "ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ",
        detail = "Ληξιπρόθεσμο · ΑΜΟ 1000001",
        total = "3.000,00",
        codeLabel = "Ταυτότητα Οφειλέτη",
        code = "RF00000000000000001000001",
        files = listOf("KEAO_KARTELA_123456783_1000001.pdf"),
    )

    private val snapshot = Debts.Snapshot(lines = listOf(enfia, carrier))

    private fun items(vararg names: String, detailed: Boolean = true, snapshot: Debts.Snapshot = this.snapshot) =
        DocumentLines.items(names.map { it to "" }, snapshot, detailed, today)

    @Test
    fun `η οφειλή γράφεται με ονομασία, υπόλοιπο, δόση, λήξη και ταυτότητα`() {
        val item = items("OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_211,10.pdf").single()
        assertEquals(DocumentLines.GROUP_AADE, item.group)
        assertEquals("ΕΝ.Φ.Ι.Α. Ν.4223/2013 (ΗΛΙΟΥΠΟΛΗΣ · έτος 2026) — Ταυτότητα οφειλής", item.title)
        assertEquals(
            listOf(
                "Υπόλοιπο: 211,10 €",
                // Η 30/09 έχει περάσει στις 2/10: γράφεται ως ληγμένη, όχι ως «επόμενη».
                "Δόση: 52,78 € — έληξε 30/09/2026",
                "Απομένουν 3 από 5 δόσεις",
                "Ταυτότητα Οφειλής: 123456783 900000001 200000000001",
                "Συνημμένο: OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_211,10.pdf",
            ),
            item.details,
        )
    }

    @Test
    fun `δόση που δεν έχει λήξει γράφεται ως επόμενη`() {
        val later = enfia.copy(unpaid = enfia.unpaid.drop(1))
        val item = items(enfia.files[0], snapshot = Debts.Snapshot(listOf(later))).single()
        assertTrue(item.details.toString(), item.details.contains("Επόμενη δόση: 52,78 € — λήξη 30/10/2026"))
    }

    /**
     * Το έντυπο είναι παλαιότερο από την τελευταία ενημέρωση: το ποσό στο όνομά
     * του δεν ταιριάζει πια με καμία γραμμή της καρτέλας. Τότε γράφεται ό,τι
     * λέει το ίδιο το όνομα — και **τίποτα** από την καρτέλα.
     */
    @Test
    fun `συνημμένο που δεν ταιριάζει με οφειλή δεν δανείζεται στοιχεία`() {
        val item = items("OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_44,93.pdf").single()
        assertEquals("ΕΝ.Φ.Ι.Α. Ν.42232013 — Ταυτότητα οφειλής", item.title)
        assertEquals(
            listOf("Ποσό: 44,93 €", "Συνημμένο: OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_44,93.pdf"),
            item.details,
        )
        assertFalse(item.details.any { it.contains("Ταυτότητα Οφειλής") || it.contains("λήξη") })
    }

    @Test
    fun `οι οφειλές χωρίζονται σε ΑΑΔΕ και ΚΕΑΟ, πριν από τα λοιπά έντυπα`() {
        val all = items(
            "Εκκαθαριστικό_123456783_2025.pdf",
            "KEAO_KARTELA_123456783_1000001.pdf",
            "OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_211,10.pdf",
            "RYTHMISI_123456783_2026_7000001.pdf",
        )
        assertEquals(
            listOf(
                DocumentLines.GROUP_AADE, DocumentLines.GROUP_AADE,
                DocumentLines.GROUP_KEAO, DocumentLines.GROUP_OTHER,
            ),
            all.map { it.group },
        )
        val keao = all[2]
        assertTrue(keao.title.startsWith("ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ"))
        assertTrue(keao.details.contains("Ταυτότητα Οφειλέτη: RF00000000000000001000001"))
        // Χωρίς δοσολόγιο δεν γράφεται δόση.
        assertFalse(keao.details.any { it.contains("όση") })
    }

    /** Το `KEAO_RYTHMISI` δεν είναι `RYTHMISI`: άλλη πύλη, άλλη επικεφαλίδα. */
    @Test
    fun `η πύλη βγαίνει από το πρόθεμα του αρχείου`() {
        assertEquals(Debts.Source.KEAO, DocumentLines.source("KEAO_RYTHMISI_123456783_1000001_500001.pdf"))
        assertEquals(Debts.Source.AADE, DocumentLines.source("RYTHMISI_123456783_2026_7000001.pdf"))
        assertEquals(Debts.Source.AADE, DocumentLines.source("DOSEIS_123456783_ΦΠΑ_10,00.pdf"))
        assertEquals(null, DocumentLines.source("E1_123456783_2025.pdf"))
        // Ο φορολογικός λογαριασμός δεν είναι οφειλή προς πληρωμή.
        assertEquals(null, DocumentLines.source("FOR_LOGARIASMOS_123456783_2026_09.pdf"))
    }

    @Test
    fun `χωρίς έντυπο οφειλής η λίστα μένει όπως ήταν`() {
        val all = items("Εκκαθαριστικό_123456783_2025.pdf", "E1_123456783_2025.pdf")
        assertTrue(all.all { it.group.isBlank() && it.details.isEmpty() })
        assertEquals("Εκκαθαριστικό δήλωσης 2025 (Εκκαθαριστικό_123456783_2025.pdf)", all[0].title)
    }

    @Test
    fun `με κλειστό τον διακόπτη μένει η ονομασία και το αρχείο`() {
        val item = items(enfia.files[0], detailed = false).single()
        assertEquals(DocumentLines.GROUP_AADE, item.group)
        assertEquals(listOf("Συνημμένο: " + enfia.files[0]), item.details)
        assertTrue(item.title.startsWith("ΕΝ.Φ.Ι.Α. Ν.4223/2013"))
    }

    // ------------------------------------------------------------ το μήνυμα

    private val client = ClientEntity(afm = "123456783", name = "ΠΑΠΑΔΟΠΟΥΛΟΣ")

    private fun body(vararg names: String) = MailTemplates.documents(
        client = client,
        items = items(*names),
        note = "",
        officeName = "Λογιστικό Γραφείο",
        signature = "",
        template = MailTemplateStore.DEFAULT_DOCUMENTS.copy(intro = "Σας επισυνάπτουμε:", closing = "Στη διάθεσή σας."),
    )

    @Test
    fun `το κείμενο έχει επικεφαλίδα ανά πύλη και τα στοιχεία κάτω από κάθε οφειλή`() {
        val text = body(enfia.files[0], "Εκκαθαριστικό_123456783_2025.pdf").text
        assertEquals(
            listOf(
                "Σας επισυνάπτουμε:",
                "",
                "Οφειλές ΑΑΔΕ",
                "",
                "  • ΕΝ.Φ.Ι.Α. Ν.4223/2013 (ΗΛΙΟΥΠΟΛΗΣ · έτος 2026) — Ταυτότητα οφειλής",
                "    Υπόλοιπο: 211,10 €",
                "    Δόση: 52,78 € — έληξε 30/09/2026",
                "    Απομένουν 3 από 5 δόσεις",
                "    Ταυτότητα Οφειλής: 123456783 900000001 200000000001",
                "    Συνημμένο: OFEILI_123456783_ΕΝ.Φ.Ι.Α._Ν.42232013_211,10.pdf",
                "",
                "Λοιπά έντυπα",
                "",
                "  • Εκκαθαριστικό δήλωσης 2025 (Εκκαθαριστικό_123456783_2025.pdf)",
                "",
                "Στη διάθεσή σας.",
                "",
                "Με εκτίμηση,",
                "Λογιστικό Γραφείο",
            ),
            text.lines(),
        )
    }

    @Test
    fun `το πλήθος στο θέμα είναι τα συνημμένα, όχι οι γραμμές`() {
        val subject = body(enfia.files[0], "Εκκαθαριστικό_123456783_2025.pdf").subject
        assertTrue(subject, subject.contains("(2)"))
    }

    @Test
    fun `το HTML κλείνει ό,τι ανοίγει`() {
        val html = body(enfia.files[0], "KEAO_KARTELA_123456783_1000001.pdf", "E1_123456783_2025.pdf").html
        assertEquals(Regex("<ul").findAll(html).count(), Regex("</ul>").findAll(html).count())
        assertEquals(3, Regex("<li").findAll(html).count())
        assertTrue(html.contains("Οφειλές ΑΑΔΕ") && html.contains("Οφειλές ΚΕΑΟ") && html.contains("Λοιπά έντυπα"))
    }

    @Test
    fun `χωρίς οφειλές το μήνυμα είναι η απλή λίστα`() {
        val text = body("Εκκαθαριστικό_123456783_2025.pdf", "E1_123456783_2025.pdf").text
        assertTrue(
            text,
            text.contains(
                "Σας επισυνάπτουμε:\n\n" +
                    "  • Εκκαθαριστικό δήλωσης 2025 (Εκκαθαριστικό_123456783_2025.pdf)\n" +
                    "  • Ε1 — δήλωση φορολογίας εισοδήματος 2025 (E1_123456783_2025.pdf)\n\n" +
                    "Στη διάθεσή σας.",
            ),
        )
        assertFalse(text.contains("Λοιπά έντυπα"))
    }
}
