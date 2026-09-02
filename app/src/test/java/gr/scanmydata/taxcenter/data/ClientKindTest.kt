package gr.scanmydata.taxcenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Το είδος υπόχρεου κρίνει αν εμφανίζεται το πεδίο ΑΜΚΑ και αν ισχύουν οι
 * διαδικασίες ΕΦΚΑ/ΑΤΛΑΣ/ΚΕΑΟ. Μια λάθος κατηγοριοποίηση δεν βγάζει σφάλμα —
 * κρύβει ένα πεδίο, και ο λογιστής ψάχνει γιατί δεν τρέχει η διαδικασία.
 */
class ClientKindTest {

    @Test
    fun `οι τιμές του Μητρώου περνούν αυτούσιες`() {
        // Το `aade-profile` επιστρέφει ακριβώς αυτά τα τρία — ένα σχήμα, όχι δύο.
        for (kind in ClientKind.ALL) {
            assertEquals(kind, ClientKind.normalise(kind))
        }
    }

    @Test
    fun `τα exports των λογιστικών προγραμμάτων χαρτογραφούνται`() {
        assertEquals(ClientKind.PRIVATE, ClientKind.normalise("Φυσικό Πρόσωπο"))
        assertEquals(ClientKind.PRIVATE, ClientKind.normalise("ΦΥΣΙΚΟ ΠΡΟΣΩΠΟ"))
        assertEquals(ClientKind.PRIVATE, ClientKind.normalise("ιδιώτης"))
        assertEquals(ClientKind.SOLE, ClientKind.normalise("Ατομική Επιχείρηση"))
        assertEquals(ClientKind.SOLE, ClientKind.normalise("ΑΤΟΜΙΚΗ"))
        assertEquals(ClientKind.LEGAL, ClientKind.normalise("Νομικό Πρόσωπο"))
    }

    @Test
    fun `η ατομική κερδίζει από το φυσικό όταν υπάρχουν και τα δύο`() {
        // «Φυσικό πρόσωπο με ατομική επιχείρηση» είναι και τα δύο· η ατομική
        // είναι η πληροφορία που ξεχωρίζει, το φυσικό ισχύει έτσι κι αλλιώς.
        assertEquals(ClientKind.SOLE, ClientKind.normalise("Φυσικό πρόσωπο - ατομική επιχείρηση"))
    }

    @Test
    fun `άγνωστο κείμενο δεν πετιέται`() {
        // Το να «καθαρίσουμε» ό,τι δεν αναγνωρίζουμε θα έσβηνε πληροφορία που
        // κάποιος έγραψε επίτηδες. Μένει ως έχει και το βλέπει ο χρήστης.
        assertEquals("Κοινοπραξία", ClientKind.normalise("Κοινοπραξία"))
        assertEquals("", ClientKind.normalise("   "))
    }

    @Test
    fun `ΑΜΚΑ έχουν μόνο όσοι έχουν φυσικό πρόσωπο από πίσω`() {
        assertTrue(ClientKind.hasAmka(ClientKind.PRIVATE))
        assertTrue(ClientKind.hasAmka(ClientKind.SOLE))
        assertFalse(ClientKind.hasAmka(ClientKind.LEGAL))
        assertFalse(ClientKind.hasAmka("Νομικό Πρόσωπο"))
    }

    @Test
    fun `άγνωστο ή κενό είδος δεν κρύβει το ΑΜΚΑ`() {
        // Στην αμφιβολία δείχνουμε το πεδίο: το να λείπει ένα ΑΜΚΑ που υπάρχει
        // σπάει τις διαδικασίες ΕΦΚΑ· το να φαίνεται ένα πεδίο που δεν
        // χρειάζεται, όχι.
        assertTrue(ClientKind.hasAmka(""))
        assertTrue(ClientKind.hasAmka("Κοινοπραξία"))
    }
}
