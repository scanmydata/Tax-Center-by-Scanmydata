package gr.scanmydata.taxcenter.google

import gr.scanmydata.taxcenter.debts.Debts
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Τα κείμενα που ανεβαίνουν στο Drive. Όλα τα στοιχεία είναι συνθετικά.
 */
class DriveReportsTest {

    private val today = LocalDate.of(2026, 10, 15)

    private val snapshot = Debts.Snapshot(
        lines = listOf(
            Debts.Line(
                id = "a1",
                group = Debts.Group.AADE_OPEN,
                title = "ΔΗΛ.ΦΟΡΟΥ ΕΙΣΟΔ. Φ.Π.",
                detail = "Οικ. έτος 2026",
                total = "1.973,32",
                overdue = "328,49",
                code = "RF00000000000000001000001",
                unpaid = listOf(
                    Debts.Instalment("1", "31/07/2026", "328,49"),
                    Debts.Instalment("2", "31/08/2026", "328,49"),
                    Debts.Instalment("3", "30/11/2026", "328,49"),
                ),
                instalments = 6,
            ),
            Debts.Line(
                id = "k1",
                group = Debts.Group.KEAO_CARRIER,
                title = "e-ΕΦΚΑ (πρώην ΟΑΕΕ)",
                total = "540,00",
                codeLabel = "Ταυτότητα Οφειλέτη",
                code = "9000000001",
                carrier = "c1",
                parts = listOf(Debts.Part("Πρόσθετα τέλη", "40,00")),
            ),
            Debts.Line(
                id = "k2",
                group = Debts.Group.KEAO_INACTIVE,
                title = "Ρύθμιση 7000001",
                total = "100,00",
                status = "Απολεσθείσα",
            ),
        ),
        aadeAt = 1L,
        keaoAt = 1L,
    )

    @Test
    fun `debts report has totals, instalments and payment codes`() {
        val text = DriveReports.debts("ΠΕΛΑΤΗΣ ΔΟΚΙΜΗΣ", "123456783", snapshot, "15/10/2026 08:00", "15/10/2026 08:01", today)
        assertTrue(text.startsWith("Οφειλές — ΠΕΛΑΤΗΣ ΔΟΚΙΜΗΣ (ΑΦΜ 123456783)"))
        assertTrue(text, text.contains("ΑΑΔΕ — σύνολο 1.973,32 €"))
        // Το ΚΕΑΟ μετρά μόνο τους φορείς — όχι τη μη ενεργή ρύθμιση.
        assertTrue(text, text.contains("ΚΕΑΟ — σύνολο 540,00 €"))
        assertTrue(text, text.contains("(δεν μετρούν στο σύνολο)"))
        assertTrue(text, text.contains("Κατάσταση: Απολεσθείσα"))
        // Η παλαιότερη απλήρωτη δόση έχει λήξει.
        assertTrue(text, text.contains("Ληγμένη δόση 1 από 6: 328,49 €, έληξε 31/07/2026"))
        assertTrue(text, text.contains("Ληξιπρόθεσμες δόσεις: 2, σύνολο 656,98 €"))
        assertTrue(text, text.contains("Ταυτότητα Οφειλής: RF00000000000000001000001"))
        assertTrue(text, text.contains("Ταυτότητα Οφειλέτη: 9000000001"))
        assertTrue(text, text.contains("Πρόσθετα τέλη: 40,00 €"))
    }

    @Test
    fun `debts report says when a portal was never read`() {
        val text = DriveReports.debts("Χ", "123456783", snapshot.copy(keaoAt = 0L), "15/10/2026 08:00", "", today)
        assertTrue(text, text.contains("ΚΕΑΟ: δεν έχει ενημερωθεί"))
        assertTrue(text, text.contains("Δεν έχει γίνει ενημέρωση από την πύλη."))
        // Χωρίς ενημέρωση δεν γράφεται σύνολο: το «0,00 €» θα ήταν ψέμα.
        assertFalse(text, text.contains("ΚΕΑΟ — σύνολο"))
    }

    private val request = AadeRequests.Request(
        id = "4242",
        caseNumber = "1000001/20260901/0001",
        submitted = "2026-09-01 10:00:00",
        updated = "2026-09-20 12:30:00",
        status = "Απαντημένο",
        topic = "Φορολογία εισοδήματος",
        process = "Τροποποιητική δήλωση Ε1",
        service = "ΚΕ.Φ.Ο.ΔΕ.",
        text = "Παρακαλώ…",
        answer = "Το αίτημα ικανοποιήθηκε.",
    )

    private val detail = RequestDetail.Detail(
        id = "4242",
        caseNumber = "1000001/20260901/0001",
        process = "Τροποποιητική δήλωση Ε1",
        text = "Κείμενο του αιτήματος.",
        answer = "Το αίτημα ικανοποιήθηκε.",
        attachments = listOf(
            RequestDetail.Attachment("9001", "Ε1.pdf", RequestDetail.Origin.SUBMITTED, saved = "AITIMA_4242_9001_E1.pdf"),
            RequestDetail.Attachment("9002", "Απάντηση.pdf", RequestDetail.Origin.REPLY),
        ),
    )

    @Test
    fun `request report has the case, the answer and the attachments`() {
        val text = DriveReports.request("ΠΕΛΑΤΗΣ ΔΟΚΙΜΗΣ", "123456783", request, detail, "20/09/2026 13:00")
        assertTrue(text.startsWith("Αίτημα προς την ΑΑΔΕ — Τροποποιητική δήλωση Ε1"))
        assertTrue(text, text.contains("Αρ. υπόθεσης: 1000001/20260901/0001"))
        assertTrue(text, text.contains("Υποβολή: 01/09/2026 10:00"))
        assertTrue(text, text.contains("Κατάσταση: Απαντημένο"))
        assertTrue(text, text.contains("Απάντηση\n--------\nΤο αίτημα ικανοποιήθηκε."))
        assertTrue(text, text.contains("• Ε1.pdf — υποβλήθηκε με το αίτημα\n"))
        assertTrue(text, text.contains("• Απάντηση.pdf — έγγραφο της απάντησης (δεν κατέβηκε)"))
    }

    @Test
    fun `request list is newest first`() {
        val older = request.copy(id = "1", process = "Παλιό", updated = "2026-01-01 00:00:00")
        val text = DriveReports.requests("Χ", "123456783", listOf(older, request), "15/10/2026")
        assertTrue(text, text.indexOf("Τροποποιητική") < text.indexOf("Παλιό"))
        assertTrue(text, text.contains("2 αιτήματα"))
    }

    @Test
    fun `request folder name has no slashes and is short`() {
        assertEquals(
            "1000001-20260901-0001 — Τροποποιητική δήλωση Ε1",
            DriveReports.requestFolder("4242", "1000001/20260901/0001", "Τροποποιητική δήλωση Ε1"),
        )
        assertEquals("Αίτημα 4242", DriveReports.requestFolder("4242", "", ""))
        val long = DriveReports.requestFolder("4242", "", "Α".repeat(200))
        assertTrue(long, long.length < 80 && long.endsWith("…"))
    }
}
