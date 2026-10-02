package gr.scanmydata.taxcenter.engine

import gr.scanmydata.taxcenter.engine.FetchOutcome.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Τι λέει η οθόνη όταν μια λήψη δεν φέρει έντυπο.
 *
 * Το ζητούμενο είναι ένα: ο λογιστής να ξεχωρίζει **με μια ματιά** το «δεν
 * υπάρχει έντυπο» από το «δεν έγινε σύνδεση» και από το «κάτι χάλασε». Τα τρία
 * θέλουν διαφορετική ενέργεια, και το να μπερδευτούν σημαίνει ότι θα πει στον
 * πελάτη πως δεν έχει εκκαθαριστικό ενώ απλώς άλλαξε ο κωδικός του.
 */
class FetchOutcomeTest {

    @Test
    fun `οι λάθος κωδικοί είναι αποτυχία σύνδεσης και δεν ξαναδοκιμάζονται`() {
        val explained = FetchOutcome.explain("InvalidCredentials")
        assertEquals(Kind.LOGIN, explained.kind)
        assertTrue(explained.text.contains("κλειδώνει"))
        // Η επανάληψη με τους ίδιους κωδικούς φέρνει το κλείδωμα στο GSIS.
        assertFalse(explained.retryable)
    }

    @Test
    fun `όσα θέλουν ενέργεια του πελάτη ή της καρτέλας δεν ξαναδοκιμάζονται`() {
        for (reason in listOf("KeaoLoginFailed", "AmkaRejected", "ContactNotConfirmed", "NoEdebtorRights", "NotLoggedIn")) {
            val explained = FetchOutcome.explain(reason)
            assertEquals(reason, Kind.LOGIN, explained.kind)
            assertFalse(reason, explained.retryable)
        }
    }

    /**
     * Τα μηνύματα του `ProcessRunner` είναι ήδη ελληνικά και κουβαλούν τον ΑΦΜ·
     * περνούν αυτούσια, στη σωστή κατηγορία.
     */
    @Test
    fun `στοιχεία που λείπουν από την καρτέλα λέγονται ως έχουν`() {
        val missing = FetchOutcome.explain("Λείπουν κωδικοί TAXISnet για τον πελάτη 123456783")
        assertEquals(Kind.MISSING, missing.kind)
        assertEquals("Λείπουν κωδικοί TAXISnet για τον πελάτη 123456783", missing.text)
        assertFalse(missing.retryable)
        assertEquals(Kind.MISSING, FetchOutcome.explain("Λείπει το ΑΜΚΑ για τον πελάτη 123456783").kind)
        assertEquals(Kind.MISSING, FetchOutcome.explain("NoYear").kind)
    }

    /**
     * «Δεν υπάρχει» δεν είναι βλάβη, ακόμη κι όταν το config το επιστρέφει ως
     * αποτυχία. Η οθόνη το δείχνει ως «δεν βρέθηκε», όχι ως σφάλμα.
     */
    @Test
    fun `η πύλη απάντησε και δεν υπάρχει — δεν είναι σφάλμα`() {
        for (reason in listOf("NoDeclarationsRow", "NoMonth", "YearNotAvailable", "NotFound", "NoEmail", "NoSuchRegistry")) {
            val explained = FetchOutcome.explain(reason)
            assertEquals(reason, Kind.NOT_FOUND, explained.kind)
            assertFalse("το ίδιο αίτημα θα έδινε το ίδιο τίποτα", explained.retryable)
        }
    }

    @Test
    fun `σελίδα που δεν ήταν η αναμενόμενη είναι θέμα της πύλης και ξαναδοκιμάζεται`() {
        for (reason in listOf("LandPage", "ViewState", "PageError", "NoPDF", "NoList", "EisodosButton", "API_403")) {
            val explained = FetchOutcome.explain(reason)
            assertEquals(reason, Kind.PORTAL, explained.kind)
            assertTrue(reason, explained.retryable)
            // Κανένα αγγλικό αναγνωριστικό δεν φτάνει σκέτο στον χρήστη.
            assertFalse(reason, explained.text == reason)
        }
        assertEquals(Kind.PORTAL, FetchOutcome.explain("AdfFlow:Cannot read properties of undefined").kind)
        assertEquals(Kind.PORTAL, FetchOutcome.explain("EtakEntry:_afrLoop not found").kind)
    }

    @Test
    fun `το δίκτυο ξεχωρίζει από την πύλη`() {
        val reasons = listOf(
            "Λήξη χρόνου μετά από 600s",
            "Unable to resolve host \"www1.aade.gr\": No address associated with hostname",
            "Failed to connect to login.gsis.gr/1.2.3.4:443",
            "timeout",
            "Software caused connection abort",
        )
        for (reason in reasons) {
            val explained = FetchOutcome.explain(reason)
            assertEquals(reason, Kind.NETWORK, explained.kind)
            assertTrue(explained.retryable)
        }
    }

    @Test
    fun `πολλές ανοιχτές συνεδρίες λέγονται ως αυτό που είναι`() {
        val explained = FetchOutcome.explain("OAM-6: μέγιστος αριθμός περιόδων λειτουργίας")
        assertEquals(Kind.LOGIN, explained.kind)
        assertTrue(explained.text.contains("συνεδρίες"))
    }

    /**
     * Άγνωστος λόγος: δεν κρύβεται και δεν μεταφράζεται στα τυφλά. Μπαίνει
     * μέσα σε πρόταση που λέει τι να κάνει ο χρήστης, με τον ίδιο τον λόγο σε
     * παρένθεση για όποιον χρειαστεί να τον αναφέρει.
     */
    @Test
    fun `άγνωστος λόγος εξηγείται χωρίς να χαθεί`() {
        val explained = FetchOutcome.explain("SomethingNew")
        assertEquals(Kind.PORTAL, explained.kind)
        assertTrue(explained.text.contains("SomethingNew"))
        assertTrue(explained.text.contains("Δοκίμασε ξανά"))
        assertEquals(Kind.PORTAL, FetchOutcome.explain("").kind)
    }

    /**
     * Το κενό αποτέλεσμα δεν σημαίνει το ίδιο παντού: στο Ε1 «δεν υποβλήθηκε»,
     * στις οφειλές «δεν χρωστάει».
     */
    @Test
    fun `το «δεν βρέθηκε» λέει τι σημαίνει για κάθε διαδικασία`() {
        assertTrue(FetchOutcome.empty("aade-income").contains("για αυτό το έτος"))
        assertTrue(FetchOutcome.empty("aade-debts").contains("δεν έχει οφειλές"))
        assertTrue(FetchOutcome.empty("keao-debts").contains("ΚΕΑΟ"))
        assertTrue(FetchOutcome.empty("efka-notices").contains("ειδοποιητήρια"))
        // Όλα ξεκινούν λέγοντας ότι η σύνδεση **έγινε** — αυτό είναι που το
        // ξεχωρίζει από την αποτυχία.
        for (config in listOf("aade-income", "aade-debts", "keao-debts", "κάτι-άλλο")) {
            assertTrue(config, FetchOutcome.empty(config).startsWith("Η σύνδεση έγινε"))
        }
    }

    @Test
    fun `κάθε κατηγορία έχει τίτλο που διαβάζεται μόνος του`() {
        assertEquals("Δεν έγινε σύνδεση", Kind.LOGIN.title)
        for (kind in Kind.entries) assertTrue(kind.name, kind.title.isNotBlank())
    }

    /** Το παλιό `describe` μένει ως είσοδος για όσους δείχνουν μόνο κείμενο. */
    @Test
    fun `το describe του controller δίνει το ίδιο κείμενο`() {
        assertEquals(
            FetchOutcome.explain("InvalidCredentials").text,
            FetchController.describe("InvalidCredentials"),
        )
    }
}
