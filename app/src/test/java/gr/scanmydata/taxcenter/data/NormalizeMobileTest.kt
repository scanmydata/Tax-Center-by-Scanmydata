package gr.scanmydata.taxcenter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ο αριθμός που θα δεχτεί το Viber.
 *
 * Εδώ ένα λάθος δεν δίνει σφάλμα — ανοίγει συνομιλία με **άγνωστο τρίτο**, και
 * το πρώτο πράγμα που του φτάνει είναι φορολογικά έντυπα ξένου ανθρώπου. Γι'
 * αυτό ο έλεγχος είναι κλειστή λίστα μορφών και όχι «ό,τι μοιάζει με τηλέφωνο».
 */
class NormalizeMobileTest {

    @Test
    fun `δέχεται τις μορφές που φτάνουν στην πράξη`() {
        // Όπως το γράφει το `getLdapInfo` της ΑΑΔΕ.
        assertEquals("6900000000", Normalize.mobile("6900000000"))
        // Όπως το γράφει άνθρωπος.
        assertEquals("6900000000", Normalize.mobile("690 000 0000"))
        assertEquals("6900000000", Normalize.mobile("690-000-0000"))
        // Με διεθνές πρόθεμα, στις δύο συνηθισμένες γραφές.
        assertEquals("6900000000", Normalize.mobile("+30 6900000000"))
        assertEquals("6900000000", Normalize.mobile("00306900000000"))
        // Με κενό από επικόλληση.
        assertEquals("6900000000", Normalize.mobile("  6900000000 "))
    }

    @Test
    fun `το σταθερό απορρίπτεται`() {
        // Το `getLdapInfo` επιστρέφει και `phonenumber`. Δεν αντιστοιχεί σε
        // λογαριασμό Viber, και δεν πρέπει να περάσει ποτέ ως παραλήπτης.
        assertEquals("", Normalize.mobile("2100000000"))
        assertFalse(Normalize.validMobile("2100000000"))
    }

    @Test
    fun `τα μισά και τα λάθος νούμερα απορρίπτονται`() {
        assertEquals("", Normalize.mobile(null))
        assertEquals("", Normalize.mobile(""))
        assertEquals("", Normalize.mobile("690000000"))       // εννέα ψηφία
        assertEquals("", Normalize.mobile("69000000001"))     // έντεκα
        assertEquals("", Normalize.mobile("6800000000"))      // δεν αρχίζει με 69
        assertEquals("", Normalize.mobile("κινητό"))
    }

    @Test
    fun `η διεθνής μορφή χτίζεται μόνο από έγκυρο αριθμό`() {
        assertEquals("+306900000000", Normalize.mobileE164("6900000000"))
        assertEquals("+306900000000", Normalize.mobileE164("+30 690 000 0000"))
        // Ποτέ μισός σύνδεσμος: κενό μέσα, κενό έξω.
        assertEquals("", Normalize.mobileE164("690000000"))
        assertEquals("", Normalize.mobileE164("2100000000"))
        assertTrue(Normalize.mobileE164("").isEmpty())
    }
}
