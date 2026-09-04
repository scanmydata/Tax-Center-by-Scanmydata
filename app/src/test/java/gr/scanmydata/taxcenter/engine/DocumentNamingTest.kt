package gr.scanmydata.taxcenter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Το κείμενο που φτάνει **στον πελάτη**.
 *
 * Ένα λάθος εδώ δεν σπάει τίποτα τεχνικά: στέλνει σωστό αρχείο με λάθος όνομα,
 * που είναι χειρότερο από τεχνικό όνομα — ο πελάτης νομίζει ότι έλαβε κάτι που
 * δεν έλαβε.
 */
class DocumentNamingTest {

    @Test
    fun `το εκκαθαριστικό συζύγου δεν διαβάζεται ως εκκαθαριστικό`() {
        // Η σειρά των προθεμάτων είναι το μόνο που το εγγυάται: το ένα όνομα
        // ξεκινά κυριολεκτικά με το άλλο.
        assertEquals(
            "Εκκαθαριστικό συζύγου",
            DocumentNaming.label("Εκκαθαριστικό_συζύγου_999999999_2024.pdf"),
        )
        assertEquals(
            "Εκκαθαριστικό δήλωσης",
            DocumentNaming.label("Εκκαθαριστικό_999999999_2024.pdf"),
        )
    }

    @Test
    fun `το Ε1 δεν καταπίνει το Ε1 συνοπτικό`() {
        assertEquals("Ε1 — συνοπτική εικόνα", DocumentNaming.label("E1_Synopsi_999999999_2024.pdf"))
        assertEquals(
            "Ε1 — δήλωση φορολογίας εισοδήματος",
            DocumentNaming.label("E1_999999999_2024.pdf"),
        )
    }

    @Test
    fun `το έτος βγαίνει από το τέλος, όχι από τον ΑΦΜ`() {
        // Ένας ΑΦΜ μπορεί να ξεκινά με ψηφία που μοιάζουν με χρονιά· το έτος
        // είναι πάντα το τελευταίο κομμάτι του ονόματος.
        assertEquals("2024", DocumentNaming.yearIn("Εκκαθαριστικό_201900000_2024.pdf"))
        assertEquals("2025", DocumentNaming.yearIn("PERIOUSIAKI_ab123456c789_2025.pdf"))
        assertEquals("", DocumentNaming.yearIn("STOIXEIA_FYSIKOU_999999999.pdf"))
    }

    @Test
    fun `η γραμμή λέει τι είναι και ποιο αρχείο`() {
        assertEquals(
            "Εκκαθαριστικό δήλωσης 2024 (Εκκαθαριστικό_999999999_2024.pdf)",
            DocumentNaming.line("Εκκαθαριστικό_999999999_2024.pdf"),
        )
        // Το έτος της καρτέλας υπερισχύει του ονόματος όταν υπάρχει.
        assertEquals(
            "Ε9 — περιουσιακή κατάσταση 2025 (PERIOUSIAKI_ab123456c789_2025.pdf)",
            DocumentNaming.line("PERIOUSIAKI_ab123456c789_2025.pdf", "2025"),
        )
    }

    @Test
    fun `άγνωστο αρχείο μένει ακριβώς όπως είναι`() {
        // Τεχνικό όνομα είναι κακό· λάθος όνομα είναι χειρότερο.
        val odd = "ΚΑΤΙ_ΑΛΛΟ_2024.pdf"
        assertEquals("", DocumentNaming.label(odd))
        assertEquals(odd, DocumentNaming.line(odd))
    }

    @Test
    fun `κάθε πρόθεμα του καταλόγου παράγει ετικέτα`() {
        // Δικλείδα για μελλοντικές προσθήκες: ένα πρόθεμα που δεν ταιριάζει με
        // τον εαυτό του σημαίνει τυπογραφικό λάθος στον πίνακα.
        for (name in listOf(
            "ENFIA_EKK_x_2022.pdf",
            "STOIXEIA_EPIXEIRISIS_NOMIKO_999445413.pdf",
            "STOIXEIA_EPIXEIRISIS_999999999.pdf",
            "Φ2_999999999_2024.pdf",
            "E2_Spouse_999999999_2024.pdf",
        )) {
            assertTrue("το $name δεν αναγνωρίστηκε", DocumentNaming.label(name).isNotBlank())
        }
    }

    @Test
    fun `το νομικό πρόσωπο δεν διαβάζεται ως απλή επιχείρηση`() {
        assertEquals(
            "Στοιχεία μητρώου — νομικού προσώπου",
            DocumentNaming.label("STOIXEIA_EPIXEIRISIS_NOMIKO_999445413.pdf"),
        )
    }
}
