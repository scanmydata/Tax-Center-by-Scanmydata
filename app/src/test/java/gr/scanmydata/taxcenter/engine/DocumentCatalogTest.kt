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
    fun `ο ιδιώτης δεν βλέπει έντυπα επιχειρηματικής δραστηριότητας`() {
        // Ο ιδιώτης είναι φυσικό πρόσωπο **χωρίς** δραστηριότητα: δεν τηρεί
        // βιβλία, άρα δεν υπάρχει Ε3, ούτε ΦΠΑ, ούτε παρακρατούμενοι. Πριν του
        // προσφέρονταν όλα, και η πύλη γύριζε άδειο χωρίς εξήγηση.
        val business = listOf(
            "e3", "e3-mydata", "f2", "f4", "f5", "fmy", "epix", "merismata",
            "tokoi", "dikaiomata", "ergolavon", "anthektikotitas", "perivallon",
            "symfonitika", "employer-efka", "employer-teka",
        )
        for (id in business) {
            val item = DocumentCatalog.byId(id)!!
            assertFalse("το $id δεν αφορά ιδιώτη", item.matches(ClientKind.PRIVATE))
            assertTrue("το $id αφορά ατομική επιχείρηση", item.matches(ClientKind.SOLE))
            assertTrue("το $id αφορά νομικό πρόσωπο", item.matches(ClientKind.LEGAL))
        }
    }

    @Test
    fun `στον ιδιώτη μένει ό,τι όντως τον αφορά`() {
        // Ο κίνδυνος του φίλτρου είναι να κρύψει κάτι υπαρκτό. Τα ασφαλιστικά
        // κρέμονται από τον ΑΜΚΑ και όχι από τα βιβλία: ένας ιδιώτης μπορεί
        // κάλλιστα να έχει ασφαλιστικό ιστορικό ή παλιά οφειλή στο ΚΕΑΟ.
        val stays = listOf(
            "e1", "e2", "ekkatharistiko", "enfia", "e9", "property", "lease",
            "debts", "tax-account", "traffic-fees", "efka-notices",
            "efka-certificate", "keao", "atlas", "registry", "profile", "amka",
        )
        for (id in stays) {
            assertTrue(
                "το $id έπρεπε να ισχύει σε ιδιώτη",
                DocumentCatalog.byId(id)!!.matches(ClientKind.PRIVATE),
            )
        }
    }

    @Test
    fun `το φίλτρο ομάδας ακολουθεί το είδος του υπόχρεου`() {
        assertTrue(
            "ο ιδιώτης δεν έχει έντυπα ΦΠΑ",
            DocumentCatalog.inGroup(DocumentCatalog.GROUP_VAT, ClientKind.PRIVATE).isEmpty(),
        )
        assertTrue(
            DocumentCatalog.inGroup(DocumentCatalog.GROUP_VAT, ClientKind.SOLE).isNotEmpty(),
        )
        // Κενό είδος = καμία γνώση = κανένα φιλτράρισμα.
        assertEquals(
            DocumentCatalog.inGroup(DocumentCatalog.GROUP_VAT).size,
            DocumentCatalog.inGroup(DocumentCatalog.GROUP_VAT, "").size,
        )
        assertTrue("ο ιδιώτης όντως στενεύει τον κατάλογο", DocumentCatalog.narrows(ClientKind.PRIVATE))
        assertFalse("χωρίς είδος δεν στενεύει τίποτα", DocumentCatalog.narrows(""))
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
