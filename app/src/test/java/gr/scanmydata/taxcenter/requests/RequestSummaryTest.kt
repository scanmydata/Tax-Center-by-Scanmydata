package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.requests.RequestSummary.StepState
import gr.scanmydata.taxcenter.requests.RequestSummary.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Η κεφαλίδα του αιτήματος: κατάσταση με μια ματιά, και ανάθεση χωρίς επαναλήψεις.
 *
 * Τα ονόματα υπηρεσιών έχουν τη μορφή που δίνει η πύλη (Νοέμβριος 2026)· οι
 * αριθμοί είναι συνθετικοί.
 */
class RequestSummaryTest {

    private val handling = "15η ΥΠΗΡΕΣΙΑ ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ, ΕΘΝΙΚΗΣ ΑΝΤΙΣΤΑΣΕΩΣ  104, ΕΛΕΥΣΙΝΑ 19200"

    private fun request(code: Int, rejected: Boolean = false) = AadeRequests.Request(
        id = "1",
        submitted = "2026-10-21 16:30:00",
        updated = "2026-11-03 10:13:00",
        statusCode = code,
        status = AadeRequests.statusLabel(code),
        rejected = rejected,
        handlingService = handling,
    )

    @Test
    fun `η υπηρεσία χωρίζεται από τη διεύθυνσή της`() {
        val s = RequestSummary.service(handling)
        assertEquals("15η ΥΠΗΡΕΣΙΑ ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ", s.name)
        assertEquals("ΕΘΝΙΚΗΣ ΑΝΤΙΣΤΑΣΕΩΣ 104, ΕΛΕΥΣΙΝΑ 19200", s.address)
        // Κόμμα χωρίς αριθμό μετά: μέρος του ονόματος, όχι διεύθυνση.
        assertEquals("Δ.Ο.Υ. Α, Β ΑΘΗΝΩΝ", RequestSummary.service("Δ.Ο.Υ. Α, Β ΑΘΗΝΩΝ").name)
    }

    @Test
    fun `το τμήμα που επαναλαμβάνει την υπηρεσία δεν εμφανίζεται`() {
        assertEquals("", RequestSummary.department("ΤΜΗΜΑ 15ης ΥΠΗΡΕΣΙΑΣ ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ", handling))
        assertEquals(
            "ΤΜΗΜΑ Γ1 - ΜΗΤΡΩΟΥ ΦΥΣΙΚΩΝ ΠΡΟΣΩΠΩΝ",
            RequestSummary.department("ΤΜΗΜΑ Γ1 - ΜΗΤΡΩΟΥ ΦΥΣΙΚΩΝ ΠΡΟΣΩΠΩΝ", handling),
        )
    }

    @Test
    fun `απαντημένο — ολοκληρώθηκε, με ημερομηνία και έγγραφα`() {
        val h = RequestSummary.headline(request(AadeRequests.STATUS_ANSWERED), null, replies = 2)
        assertEquals(Tone.DONE, h.tone)
        assertEquals("Ολοκληρώθηκε — απάντησε η ΑΑΔΕ", h.title)
        assertEquals("Στις 03/11/2026 10:13 · με 2 έγγραφα", h.subtitle)
        assertTrue(RequestSummary.steps(request(AadeRequests.STATUS_ANSWERED)).all { it.state == StepState.DONE })
    }

    @Test
    fun `σε επεξεργασία — λέει ποιος το έχει, χωρίς τη διεύθυνση`() {
        val h = RequestSummary.headline(request(AadeRequests.STATUS_PROCESSING), null)
        assertEquals(Tone.WORKING, h.tone)
        assertEquals("Το χειρίζεται: 15η ΥΠΗΡΕΣΙΑ ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ", h.subtitle)
        val steps = RequestSummary.steps(request(AadeRequests.STATUS_PROCESSING))
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.TODO), steps.map { it.state })
    }

    @Test
    fun `εκκρεμεί ο πελάτης — το χρώμα της ενέργειας και το όνομα του βήματος`() {
        val r = request(AadeRequests.STATUS_CLIENT_PENDING).copy(askDate = "2026-10-27 10:02:00")
        val h = RequestSummary.headline(r, null)
        assertEquals(Tone.ACTION, h.tone)
        assertEquals("Ζήτησε διευκρινίσεις στις 27/10/2026 10:02", h.subtitle)
        assertEquals("Εκκρεμεί ο πελάτης", RequestSummary.steps(r)[1].label)
    }

    @Test
    fun `απορρίφθηκε — κόκκινο τελευταίο βήμα`() {
        val r = request(AadeRequests.STATUS_REJECTED, rejected = true)
        assertEquals(Tone.BAD, RequestSummary.headline(r, null).tone)
        assertEquals(StepState.FAILED, RequestSummary.steps(r).last().state)
    }

    @Test
    fun `απλώς υποβλήθηκε — μόνο το πρώτο βήμα έχει γίνει`() {
        val steps = RequestSummary.steps(request(AadeRequests.STATUS_SENT))
        assertEquals(listOf(StepState.DONE, StepState.TODO, StepState.TODO), steps.map { it.state })
        assertEquals("21/10/2026 16:30", steps.first().date)
    }
}
