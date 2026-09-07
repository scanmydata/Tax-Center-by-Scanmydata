package gr.scanmydata.taxcenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Κενά σε κωδικούς και διευθύνσεις.
 *
 * Το σφάλμα που έφερε αυτά τα tests: στο Android το πληκτρολόγιο βάζει κενό
 * μετά από κάθε πρόταση autocomplete και η επικόλληση από Excel φέρνει NBSP.
 * Ο κωδικός αποθηκευόταν με το κενό, η καρτέλα έδειχνε σωστούς κωδικούς, και η
 * σύνδεση αποτύγχανε με «λάθος συνθηματικό» χωρίς κανένα ίχνος του γιατί.
 */
class NormalizeSecretTest {

    @Test
    fun `κόβονται τα κενά στις άκρες`() {
        assertEquals("ab123456c789", Normalize.secret(" ab123456c789 "))
        assertEquals("pw0001", Normalize.secret("pw0001\n"))
        assertEquals("pw0001", Normalize.secret("\tpw0001"))
    }

    @Test
    fun `κόβονται και οι αόρατοι χαρακτήρες`() {
        // Το NBSP φέρνει η επικόλληση από κελί του Excel· τους χαρακτήρες
        // μηδενικού πλάτους και το BOM τα φέρνει η επικόλληση από ιστοσελίδα
        // ή από αρχείο.
        assertEquals("pw0001", Normalize.secret("\u00A0pw0001\u00A0"))
        assertEquals("pw0001", Normalize.secret("\uFEFFpw0001"))
        assertEquals("pw0001", Normalize.secret("pw0001\u200B"))
    }

    @Test
    fun `το trim του Kotlin δεν αρκεί μόνο του`() {
        // Το NBSP το πιάνει: το `Char.isWhitespace()` του Kotlin περιλαμβάνει
        // το `isSpaceChar`, σε αντίθεση με το `String.trim()` της Java.
        assertEquals("pw0001", "\u00A0pw0001\u00A0".trim())
        // Το BOM όχι — είναι κατηγορίας format, όχι κενό. Αυτό ακριβώς είναι το
        // κενό που καλύπτει το `secret()`.
        assertEquals("\uFEFFpw0001", "\uFEFFpw0001".trim())
        assertEquals("pw0001", Normalize.secret("\uFEFFpw0001"))
    }

    @Test
    fun `το κενό στη μέση δεν πειράζεται`() {
        // Μπορεί κάλλιστα να ανήκει στον κωδικό· δεν το κρίνουμε εμείς.
        assertEquals("pass word", Normalize.secret("  pass word  "))
    }

    @Test
    fun `κενό ή null δίνει κενό`() {
        assertEquals("", Normalize.secret(null))
        assertEquals("", Normalize.secret("   "))
        assertEquals("", Normalize.secret("\u00A0"))
    }

    @Test
    fun `η διεύθυνση καθαρίζεται και πεζογράφεται`() {
        assertEquals("nikos@example.gr", Normalize.email("  Nikos@Example.GR "))
        assertEquals("nikos@example.gr", Normalize.email("<nikos@example.gr>"))
        assertEquals("nikos@example.gr", Normalize.email("nikos@example.gr;"))
    }

    @Test
    fun `οι προφανώς λάθος διευθύνσεις απορρίπτονται`() {
        assertTrue(Normalize.validEmail("nikos@example.gr"))
        assertTrue(Normalize.validEmail(" NIKOS@EXAMPLE.GR "))
        assertFalse(Normalize.validEmail(""))
        assertFalse(Normalize.validEmail("nikos"))
        assertFalse(Normalize.validEmail("nikos@"))
        assertFalse(Normalize.validEmail("@example.gr"))
        assertFalse(Normalize.validEmail("nikos@example"))
        assertFalse(Normalize.validEmail("nikos@@example.gr"))
        assertFalse(Normalize.validEmail("nikos@example..gr"))
        assertFalse(Normalize.validEmail("nikos @example.gr"))
    }
}
