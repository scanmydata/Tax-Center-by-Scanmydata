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
        // Μοναδική επιτρεπτή εξαίρεση η «ενημέρωση καρτέλας», που δεν είναι
        // έντυπο και αποσύρθηκε από τον κατάλογο επιλογής επίτηδες. Οτιδήποτε
        // άλλο εκτός ομάδων εμφάνισης θα ήταν έντυπο που υπάρχει στον κώδικα
        // και δεν μπορεί να ζητηθεί από πουθενά — δηλαδή σιωπηλά χαμένο.
        for (item in DocumentCatalog.ALL) {
            if (item.group == DocumentCatalog.GROUP_CARD) continue
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
            "efka-certificate", "keao", "atlas", "registry-natural", "profile", "amka",
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
    fun `το μητρώο είναι δύο έντυπα, όχι δύο όψεις του ίδιου`() {
        // Το ένα είναι τα στοιχεία του ανθρώπου, το άλλο της δραστηριότητας.
        // Η ατομική επιχείρηση έχει και τα δύο· ο ιδιώτης μόνο το πρώτο, το
        // νομικό πρόσωπο μόνο το δεύτερο.
        val natural = DocumentCatalog.byId("registry-natural")!!
        val business = DocumentCatalog.byId("registry-business")!!
        assertEquals("ΦΥΣΙΚΟ", natural.inputs["type"])
        // ΕΠΙΧΕΙΡΗΣΗ και όχι ΝΟΜΙΚΟ: το δεύτερο **επιβάλλει** τη σημαία νομικού
        // προσώπου στην εκτύπωση, που για ατομική είναι λάθος τμήμα μητρώου.
        assertEquals("ΕΠΙΧΕΙΡΗΣΗ", business.inputs["type"])
        assertTrue(natural.matches(ClientKind.PRIVATE))
        assertFalse(natural.matches(ClientKind.LEGAL))
        assertFalse(business.matches(ClientKind.PRIVATE))
        assertTrue(business.matches(ClientKind.SOLE))
        assertTrue(business.matches(ClientKind.LEGAL))
    }

    @Test
    fun `η ενημέρωση καρτέλας δεν προσφέρεται ως έντυπο`() {
        // Γίνεται μόνη της από την «Άντληση στοιχείων» της καρτέλας. Δύο δρόμοι
        // για το ίδιο πράγμα σήμαιναν δύο διαφορετικά αποτελέσματα.
        assertFalse(
            "η ομάδα «ενημέρωση καρτέλας» δεν πρέπει να εμφανίζεται",
            DocumentCatalog.GROUP_CARD in DocumentCatalog.GROUPS,
        )
        // Τα ίδια τα έντυπα μένουν: τα χρησιμοποιεί ο κώδικας άντλησης.
        for (id in listOf("profile", "email", "amka")) {
            assertNotNull("το $id χάθηκε από τον κατάλογο", DocumentCatalog.byId(id))
        }
        assertTrue(
            "κανένα κρυφό έντυπο δεν πρέπει να μένει εκτός ομάδων εμφάνισης",
            DocumentCatalog.ALL.filter { it.group !in DocumentCatalog.GROUPS }
                .all { it.group == DocumentCatalog.GROUP_CARD },
        )
    }

    @Test
    fun `μόνο το ETAK μαζεύει πολλά έτη σε μία εκτέλεση`() {
        // Η ομαδοποίηση ετών δεν είναι βελτιστοποίηση αλλά ανάγκη: το ETAK
        // μπαίνει με πραγματικό browser και GSIS OAuth, και μία σύνδεση ανά
        // έτος είναι ο συντομότερος δρόμος για κλείδωμα OAM-6. Οι υπόλοιπες
        // διαδικασίες δέχονται ένα έτος τη φορά — αν σημανθούν κατά λάθος, θα
        // τους σταλεί input που δεν καταλαβαίνουν.
        val batched = DocumentCatalog.ALL.filter { it.batchYears }.map { it.id }.sorted()
        assertEquals(listOf("e9", "enfia"), batched)
        for (item in DocumentCatalog.ALL.filter { it.batchYears }) {
            assertEquals("aade-enfia", item.configId)
            assertTrue("το ${item.id} πρέπει να ζητά έτος", item.needsYear)
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
