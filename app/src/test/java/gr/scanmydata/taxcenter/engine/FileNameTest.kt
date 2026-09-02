package gr.scanmydata.taxcenter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ο runner παράγει ελληνικά ονόματα αρχείων με κενά, κόμματα και παύλες
 * (`Φ2_999999999_2025.pdf`, `MISTH_Μισθωτής_ΟΔΟΣ 12_450,00.pdf`). Θέλουμε να τα
 * κρατήσουμε — είναι χρήσιμα για τον λογιστή — χωρίς να σπάσουμε σε
 * exFAT/SMB, και χωρίς να επιτρέψουμε γραφή εκτός του φακέλου της εκτέλεσης.
 */
class FileNameTest {

    @Test
    fun `ελληνικά ονόματα του runner μένουν αναγνωρίσιμα`() {
        assertEquals("Φ2_999999999_2025.pdf", FileBridge.sanitiseSegment("Φ2_999999999_2025.pdf"))
        assertEquals("Εκκαθαριστικ_047362769_2024.pdf", FileBridge.sanitiseSegment("Εκκαθαριστικ_047362769_2024.pdf"))
    }

    @Test
    fun `κενά και κόμματα και παύλες επιτρέπονται`() {
        // Τα ids των configs έχουν παύλες· τα PDF μισθωτηρίων έχουν κενά και κόμματα.
        assertEquals("aade-income", FileBridge.sanitiseSegment("aade-income"))
        assertEquals(
            "MISTH_Μισθωτής_ΟΔΟΣ 12_450,00.pdf",
            FileBridge.sanitiseSegment("MISTH_Μισθωτής_ΟΔΟΣ 12_450,00.pdf"),
        )
    }

    @Test
    fun `χαρακτήρες άκυροι σε Windows αντικαθίστανται`() {
        assertEquals("a_b_c_d", FileBridge.sanitiseSegment("a:b*c?d"))
        assertEquals("_", FileBridge.sanitiseSegment("/"))
        assertEquals("a_b", FileBridge.sanitiseSegment("a|b"))
        // Τα κενά ΔΕΝ πειράζονται — μόνο οι control chars και οι άκυροι χαρακτήρες.
        assertEquals("a b", FileBridge.sanitiseSegment("a b"))
    }

    @Test
    fun `το άνω-άνω εξουδετερώνεται`() {
        assertEquals("_", FileBridge.sanitiseSegment(".."))
        assertEquals("_", FileBridge.sanitiseSegment(""))
    }

    @Test
    fun `τελικές τελείες και κενά κόβονται — άκυρα σε Windows shares`() {
        assertEquals("report", FileBridge.sanitiseSegment("report. "))
        assertEquals("report", FileBridge.sanitiseSegment("report   "))
    }

    @Test
    fun `πολύ μακριά ονόματα κόβονται αλλά κρατούν την κατάληξη`() {
        val long = "Μ".repeat(300) + ".pdf"
        val out = FileBridge.sanitiseSegment(long)
        assertTrue("χάθηκε η κατάληξη: $out", out.endsWith(".pdf"))
        assertTrue("πολύ μακρύ: ${out.length}", out.length <= 120)
    }
}
