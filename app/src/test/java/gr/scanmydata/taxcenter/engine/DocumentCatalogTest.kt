package gr.scanmydata.taxcenter.engine

import gr.scanmydata.taxcenter.data.ClientKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ο κατάλογος εγγράφων είναι ο χάρτης «τι βλέπει ο λογιστής» → «τι τρέχει ο
 * engine». Ένα λάθος εδώ δεν βγάζει σφάλμα: κατεβάζει **λάθος έντυπο**.
 */
class DocumentCatalogTest {

    @Test
    fun `κάθε έγγραφο δείχνει σε υπαρκτή διαδικασία με γνωστά διαπιστευτήρια`() {
        for (item in DocumentCatalog.ALL) {
            assertNotNull(
                "το ${item.id} δείχνει σε άγνωστη διαδικασία ${item.configId}",
                CredentialMap.forConfig(item.configId),
            )
        }
    }

    @Test
    fun `τα αναγνωριστικά και οι ετικέτες είναι μοναδικά`() {
        val ids = DocumentCatalog.ALL.map { it.id }
        assertEquals("διπλό id στον κατάλογο", ids.size, ids.toSet().size)
        val labels = DocumentCatalog.ALL.map { it.label }
        assertEquals("δύο έγγραφα με το ίδιο όνομα", labels.size, labels.toSet().size)
    }

    @Test
    fun `κάθε έγγραφο ανήκει σε ομάδα που εμφανίζεται`() {
        for (item in DocumentCatalog.ALL) {
            assertTrue(
                "η ομάδα «${item.group}» του ${item.id} δεν είναι στη λίστα εμφάνισης",
                item.group in DocumentCatalog.GROUPS,
            )
        }
    }

    @Test
    fun `τα Ε1 και Ε2 είναι ξεχωριστές επιλογές της ίδιας διαδικασίας`() {
        // Το `aade-income` κατεβάζει ό,τι του πει το input `forms`. Ο λογιστής
        // δεν σκέφτεται «τρέξε aade-income με forms=E2» — σκέφτεται «θέλω το Ε2».
        val e1 = DocumentCatalog.byId("e1")!!
        val e2 = DocumentCatalog.byId("e2")!!
        assertEquals(e1.configId, e2.configId)
        assertEquals("E1", e1.inputs["forms"])
        assertEquals("E2", e2.inputs["forms"])
        assertTrue(e1.needsYear && e2.needsYear)
    }

    @Test
    fun `το έντυπο Ν αφορά μόνο νομικά πρόσωπα`() {
        val fenp = DocumentCatalog.byId("fenp")!!
        assertTrue(fenp.matches(ClientKind.LEGAL))
        assertFalse(fenp.matches(ClientKind.PRIVATE))
        assertFalse(fenp.matches(ClientKind.SOLE))
    }

    @Test
    fun `οι διαδικασίες ΕΦΚΑ δεν ισχύουν σε νομικό πρόσωπο`() {
        // Θέλουν ΑΜΚΑ στη φόρμα ρόλου· νομικό πρόσωπο δεν έχει.
        for (id in listOf("efka-notices", "efka-certificate", "keao", "atlas", "amka")) {
            val item = DocumentCatalog.byId(id)!!
            assertFalse("το $id δεν έπρεπε να ισχύει σε νομικό πρόσωπο", item.matches(ClientKind.LEGAL))
            assertTrue("το $id έπρεπε να ισχύει σε ιδιώτη", item.matches(ClientKind.PRIVATE))
        }
    }

    @Test
    fun `άγνωστο ή κενό είδος δεν αποκλείει τίποτα`() {
        // Στην αμφιβολία δοκιμάζουμε: μια σιωπηλή παράλειψη είναι χειρότερη από
        // μια αποτυχία που εξηγείται από την ίδια την πύλη.
        for (item in DocumentCatalog.ALL) {
            assertTrue("το ${item.id} αποκλείστηκε με κενό είδος", item.matches(""))
            assertTrue("το ${item.id} αποκλείστηκε με άγνωστο είδος", item.matches("Κοινοπραξία"))
        }
    }

    @Test
    fun `η καρτέλα εργοδότη θέλει κωδικούς ΙΚΑ, όχι TAXISnet`() {
        // Αν μπερδευτούν, η σύνδεση αποτυγχάνει με «InvalidCredentials» και
        // μοιάζει με λάθος κωδικό του πελάτη.
        for (id in listOf("employer-efka", "employer-teka")) {
            val item = DocumentCatalog.byId(id)!!
            assertEquals(
                CredentialMap.Login.IKA_EMPLOYER,
                CredentialMap.forConfig(item.configId)?.login,
            )
        }
    }

    @Test
    fun `οι ενημερώσεις καρτέλας δεν θεωρούνται έγγραφα προς αποστολή`() {
        // Αλλιώς η «λήψη και αποστολή» θα έστελνε ένα κενό email σε κάθε πελάτη
        // για τον οποίο έγινε μόνο ενημέρωση στοιχείων.
        for (id in listOf("profile", "email", "amka")) {
            assertFalse(DocumentCatalog.byId(id)!!.producesDocuments)
        }
    }
}
