package gr.scanmydata.taxcenter.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Τι μετρά ως «κατέβηκε σε αυτή την εκτέλεση».
 *
 * Από εδώ κρίνεται τι μπαίνει στα Έγγραφα και τι φεύγει με την αυτόματη
 * αποστολή. Ένα έντυπο που ξαναγράφτηκε στο ίδιο όνομα και δεν μετρήθηκε είναι
 * φρέσκο έντυπο που δεν στάλθηκε ποτέ.
 */
class WrittenFilesTest {

    @Test
    fun `νέο αρχείο μετρά`() {
        val before = mapOf("a.pdf" to (1L to 10L))
        val after = before + ("b.pdf" to (5L to 20L))
        assertEquals(setOf("b.pdf"), ProcessRunner.written(before, after))
    }

    @Test
    fun `ξαναγραμμένο αρχείο με το ίδιο όνομα μετρά`() {
        // Δεύτερη λήψη της ίδιας καρτέλας ΚΕΑΟ: ίδιο όνομα, νέο περιεχόμενο.
        val before = mapOf("KEAO_KARTELA_1_2.pdf" to (1000L to 4000L))
        val after = mapOf("KEAO_KARTELA_1_2.pdf" to (9000L to 4000L))
        assertEquals(setOf("KEAO_KARTELA_1_2.pdf"), ProcessRunner.written(before, after))
    }

    @Test
    fun `αρχείο που δεν άγγιξε η εκτέλεση δεν μετρά`() {
        // Π.χ. το δοσολόγιο μιας ρύθμισης που δεν είναι πια ενεργή: μένει στον
        // φάκελο από παλιότερη λήψη, αλλά δεν ξαναστέλνεται.
        val before = mapOf("KEAO_RYTHMISI_1_2_3.pdf" to (1000L to 4000L))
        assertEquals(emptySet<String>(), ProcessRunner.written(before, before))
    }
}
