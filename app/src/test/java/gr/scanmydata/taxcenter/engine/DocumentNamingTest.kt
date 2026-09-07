package gr.scanmydata.taxcenter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `τα τέλη κυκλοφορίας αναγνωρίζονται με το όνομα που γράφει το config`() {
        // Η πρώτη έκδοση είχε «TELI» από μαντεψιά, και το email έφτανε στον
        // πελάτη με το σκέτο όνομα αρχείου. Το config γράφει TELH_KYKLOFORIAS.
        assertEquals(
            "Τέλη κυκλοφορίας 2026 (TELH_KYKLOFORIAS_999999999_2026.pdf)",
            DocumentNaming.line("TELH_KYKLOFORIAS_999999999_2026.pdf"),
        )
    }

    @Test
    fun `το έτος βγαίνει από ολόκληρο κομμάτι του ονόματος`() {
        assertEquals("2024", DocumentNaming.yearIn("Εκκαθαριστικό_201900000_2024.pdf"))
        assertEquals("2025", DocumentNaming.yearIn("PERIOUSIAKI_ab123456c789_2025.pdf"))
        // Ο φορολογικός λογαριασμός έχει και μήνα μετά το έτος.
        assertEquals("2026", DocumentNaming.yearIn("FOR_LOGARIASMOS_999999999_2026_09.pdf"))
        // Όπου το config δεν βάζει έτος, δεν μαντεύουμε: το ποσό μιας οφειλής
        // μπορεί κάλλιστα να μοιάζει με χρονιά.
        assertEquals("", DocumentNaming.yearIn("OFEILI_999999999_FPA_2024.pdf"))
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
    fun `οι κωδικοί του aade-general-forms αναγνωρίζονται`() {
        // Το όνομα είναι `<κωδικός>_<ΑΦΜ>_<έτος>_<περίοδος>_<id>.pdf`.
        for (key in listOf(
            "Φ2", "Φ4", "Φ5", "ΦΜΥ", "ΕΠΙΧ", "ΜΕΡΙΣΜΑΤΑ", "ΤΟΚΟΙ",
            "ΔΙΚΑΙΩΜΑΤΑ", "ΕΡΓΟΛΑΒΩΝ", "ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ", "ΠΕΡΙΒΑΛΛΟΝ", "ΣΥΜΦΩΝΗΤΙΚΑ",
        )) {
            val name = key + "_999999999_2024_01_12345.pdf"
            assertTrue("ο κωδικός $key δεν αναγνωρίστηκε", DocumentNaming.label(name).isNotBlank())
        }
    }

    /**
     * Η δικλείδα που έλειπε.
     *
     * Διαβάζει τα **ίδια τα configs** και βρίσκει κάθε σταθερό πρόθεμα που
     * χρησιμοποιείται για να χτιστεί όνομα `.pdf`. Αν κάποιο δεν αναγνωρίζεται,
     * το email θα έστελνε σκέτο όνομα αρχείου — ακριβώς το σφάλμα που έφερε
     * αυτόν τον έλεγχο εδώ.
     */
    @Test
    fun `κάθε σταθερό πρόθεμα των configs έχει ετικέτα`() {
        val dir = configsDir()
        val missing = mutableListOf<String>()
        var checked = 0

        for (file in dir.listFiles { f -> f.name.endsWith(".js") }.orEmpty().sortedBy { it.name }) {
            for (line in file.readLines()) {
                val code = line.trim()
                if (!code.contains(".pdf")) continue
                if (code.startsWith("//") || code.startsWith("*")) continue
                // Το **πρώτο** literal της γραμμής είναι η αρχή του ονόματος·
                // τα επόμενα (π.χ. 'NOMIKO_') είναι ενδιάμεσα κομμάτια.
                val prefix = Regex("""'([0-9A-Za-zΆ-ώ_]+_)'\s*\+""").find(code)
                    ?.groupValues?.get(1) ?: continue
                checked++
                val sample = prefix + "999999999_2024.pdf"
                if (DocumentNaming.label(sample).isBlank()) {
                    missing += "${file.name}: $prefix"
                }
            }
        }

        assertTrue("δεν βρέθηκαν προθέματα — λάθος διαδρομή configs;", checked >= 10)
        assertTrue(
            "προθέματα χωρίς ετικέτα:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    /** Ο φάκελος των configs, από τον κατάλογο εργασίας του Gradle module. */
    private fun configsDir(): File {
        val candidates = listOf(
            File("src/main/assets/engine/configs"),
            File("app/src/main/assets/engine/configs"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("δεν βρέθηκε ο φάκελος configs από ${File(".").absolutePath}")
    }
}
