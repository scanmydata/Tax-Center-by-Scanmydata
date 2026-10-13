package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.requests.AadeRequests.Change
import gr.scanmydata.taxcenter.requests.AadeRequests.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Τι γράφεται στο ημερολόγιο αποστολών για τα αιτήματα: η ειδοποίηση της
 * συσκευής και η κοινοποίηση ενός συνημμένου.
 *
 * Όλα τα στοιχεία συνθετικά.
 */
class RequestSendsTest {

    private fun request(id: String, process: String, status: String, answer: String = "") = AadeRequests.Request(
        id = id,
        caseNumber = "100000$id/2026/0001",
        status = status,
        process = process,
        answer = answer,
    )

    private val answered = Change(request("1", "Τροποποιητική δήλωση Ε1", "Απαντημένο", "Εγκρίθηκε."), Kind.ANSWERED)
    private val moved = Change(request("2", "Μεταβολή στοιχείων μητρώου", "Σε επεξεργασία"), Kind.STATUS)

    private fun notice(changes: List<Change>, delivered: Boolean = true) =
        RequestSends.notice(7L, "123456783", "Δοκιμαστικός Πελάτης", changes, at = 1_000L, delivered = delivered)

    @Test
    fun `η ειδοποίηση γράφεται ανά πελάτη, με το τι άλλαξε σε κάθε αίτημα`() {
        val entry = notice(listOf(answered, moved))
        assertEquals(7L, entry.clientId)
        assertEquals("123456783", entry.afm)
        assertEquals("Δοκιμαστικός Πελάτης", entry.clientName)
        assertEquals(SendEntity.KIND_REQUEST_NOTICE, entry.kind)
        assertEquals(RequestSends.TO_DEVICE, entry.toEmail)
        assertEquals(1_000L, entry.sentAt)
        assertEquals(2, entry.itemCount)
        assertEquals(
            listOf(
                "Τροποποιητική δήλωση Ε1 — νέα απάντηση (Αρ. 1000001/2026/0001)",
                "Μεταβολή στοιχείων μητρώου — κατάσταση: Σε επεξεργασία (Αρ. 1000002/2026/0001)",
            ),
            entry.items.lines(),
        )
        assertTrue(entry.aboutRequests)
        assertEquals("Ειδοποίηση για αίτημα ΑΑΔΕ", entry.requestLabel)
        assertEquals("ειδοποίηση", entry.channel)
    }

    /** Το κείμενο της απάντησης δεν μπαίνει στο ημερολόγιο — μόνο ότι υπήρξε. */
    @Test
    fun `το ημερολόγιο δεν κρατά το περιεχόμενο της απάντησης`() {
        val entry = notice(listOf(answered))
        assertFalse(entry.items.contains("Εγκρίθηκε"))
        assertFalse(entry.subject.contains("Εγκρίθηκε"))
    }

    @Test
    fun `το θέμα λέει πόσα και τι είδους`() {
        assertEquals("Νέα απάντηση της ΑΑΔΕ σε αίτημα", notice(listOf(answered)).subject)
        assertEquals("Νέες απαντήσεις της ΑΑΔΕ σε 2 αιτήματα", notice(listOf(answered, answered)).subject)
        assertEquals("Αλλαγή κατάστασης σε αίτημα ΑΑΔΕ", notice(listOf(moved)).subject)
        assertEquals("Αλλαγή κατάστασης σε 2 αιτήματα ΑΑΔΕ", notice(listOf(moved, moved)).subject)
        assertEquals("Αλλαγές σε 2 αιτήματα ΑΑΔΕ", notice(listOf(answered, moved)).subject)
    }

    /**
     * Με κλειστές ειδοποιήσεις στο Android, η αλλαγή βρέθηκε αλλά κανείς δεν
     * ειδοποιήθηκε. Το ημερολόγιο πρέπει να το γράφει ως αποτυχία — όχι ως
     * ειδοποίηση που «στάλθηκε».
     */
    @Test
    fun `με κλειστές ειδοποιήσεις γράφεται αποτυχία`() {
        val ok = notice(listOf(answered))
        assertEquals(SendEntity.STATUS_SENT, ok.status)
        assertFalse(ok.failed)
        assertEquals("", ok.error)

        val silent = notice(listOf(answered), delivered = false)
        assertTrue(silent.failed)
        assertEquals(RequestSends.NOTIFICATIONS_OFF, silent.error)
    }

    /** Από τον επιλογέα κοινοποίησης και πέρα δεν ξέρουμε τι έγινε: «παραδόθηκε», όχι «στάλθηκε». */
    @Test
    fun `το συνημμένο που κοινοποιήθηκε γράφεται ως παραδομένο σε άλλη εφαρμογή`() {
        val entry = RequestSends.file(7L, "123456783", "Δοκιμαστικός Πελάτης", "1000001/2026/0001", "Πράξη.pdf", at = 2_000L)
        assertEquals(SendEntity.KIND_REQUEST_FILE, entry.kind)
        assertEquals(RequestSends.TO_APP, entry.toEmail)
        assertEquals("Συνημμένο αιτήματος ΑΑΔΕ 1000001/2026/0001", entry.subject)
        assertEquals("Πράξη.pdf", entry.items)
        assertEquals(1, entry.itemCount)
        assertTrue(entry.handed)
        assertFalse(entry.failed)
        assertTrue(entry.aboutRequests)
        assertEquals("Συνημμένο αιτήματος ΑΑΔΕ", entry.requestLabel)
        assertEquals("κοινοποίηση", entry.channel)

        val failed = RequestSends.file(7L, "123456783", "Δοκιμαστικός Πελάτης", "", "Πράξη.pdf", at = 2_000L, error = "x")
        assertTrue(failed.failed)
        assertEquals("Συνημμένο αιτήματος ΑΑΔΕ", failed.subject)
    }

    /** Οι κινήσεις των αιτημάτων δεν μπερδεύονται με email, Viber, SMS ή οφειλές. */
    @Test
    fun `οι κινήσεις αιτημάτων δεν μετρούν ως άλλο κανάλι`() {
        val entry = notice(listOf(answered))
        assertFalse(entry.viaViber)
        assertFalse(entry.viaSms)
        assertFalse(entry.toPhone)
        assertFalse(entry.aboutDebts)

        val email = entry.copy(kind = SendEntity.KIND_DOCUMENTS)
        assertFalse(email.aboutRequests)
        assertEquals("email", email.channel)
    }
}
