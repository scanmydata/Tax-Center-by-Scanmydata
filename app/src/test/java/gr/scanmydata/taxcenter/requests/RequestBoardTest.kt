package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.requests.RequestBoard.Filter
import gr.scanmydata.taxcenter.requests.RequestBoard.Row
import gr.scanmydata.taxcenter.requests.RequestBoard.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Η συγκεντρωτική λίστα αιτημάτων: ποιο βγαίνει πρώτο, τι κρύβει κάθε φίλτρο,
 * τι βρίσκει η αναζήτηση.
 *
 * Πελάτες, ΑΦΜ και αιτήματα είναι συνθετικά.
 */
class RequestBoardTest {

    private fun row(
        clientId: Long,
        client: String,
        id: String,
        submitted: String,
        updated: String,
        status: String = "Απαντημένο",
        answer: String = "ok",
        rejected: Boolean = false,
        fresh: Boolean = false,
        process: String = "Τροποποιητική δήλωση Ε1",
    ) = Row(
        clientId = clientId,
        clientName = client,
        afm = "12345678$clientId",
        request = AadeRequests.Request(
            id = id,
            caseNumber = "100000$id/2026/0001",
            submitted = submitted,
            updated = updated,
            status = status,
            process = process,
            topic = "Εισόδημα",
            answer = answer,
            rejected = rejected,
        ),
        fresh = fresh,
    )

    // Τρεις πελάτες, πέντε αιτήματα.
    private val old = row(1, "Βασιλείου Άννα", "11", "2026-01-10 09:00:00", "2026-01-20 12:00:00")
    private val waiting = row(
        2, "Αντωνίου Μάρκος", "21", "2026-09-01 09:00:00", "2026-09-02 10:00:00",
        status = "Σε επεξεργασία", answer = "", process = "Μεταβολή στοιχείων μητρώου",
    )
    private val answeredNew = row(2, "Αντωνίου Μάρκος", "22", "2026-08-01 09:00:00", "2026-10-08 15:30:00", fresh = true)
    private val rejected = row(
        3, "Ωμέγα ΑΕ", "31", "2026-05-05 09:00:00", "2026-06-01 08:00:00",
        status = "Απορρίφθηκε", answer = "", rejected = true,
    )
    private val waitingOld = row(
        3, "Ωμέγα ΑΕ", "32", "2026-03-01 09:00:00", "2026-03-01 09:00:00",
        status = "Σε επεξεργασία", answer = "",
    )
    private val all = listOf(old, waiting, answeredNew, rejected, waitingOld)

    private fun ids(rows: List<Row>) = rows.map { it.request.id }

    // ------------------------------------------------------------ ταξινόμηση

    /** Η φυσική σειρά: ό,τι κινήθηκε τελευταίο, πρώτο. */
    @Test
    fun `χωρίς άλλη επιλογή, πρώτο βγαίνει ό,τι ενημερώθηκε πιο πρόσφατα`() {
        assertEquals(listOf("22", "21", "31", "32", "11"), ids(RequestBoard.arrange(all)))
        assertEquals(listOf("11", "32", "31", "21", "22"), ids(RequestBoard.arrange(all, reversed = true)))
    }

    @Test
    fun `ανά υποβολή, το πιο πρόσφατα υποβληθέν πρώτο`() {
        assertEquals(listOf("21", "22", "31", "32", "11"), ids(RequestBoard.arrange(all, Sort.SUBMITTED)))
        assertEquals(listOf("11", "32", "31", "22", "21"), ids(RequestBoard.arrange(all, Sort.SUBMITTED, reversed = true)))
    }

    /**
     * «Κατάσταση» σημαίνει «τι θέλει προσοχή»: η απάντηση που δεν έχει ανοιχτεί,
     * μετά ό,τι περιμένει, μετά οι απορρίψεις, τελευταία ό,τι έκλεισε. Μέσα σε
     * κάθε ομάδα, το πιο πρόσφατο πρώτο.
     */
    @Test
    fun `ανά κατάσταση, πρώτα τα νέα και όσα περιμένουν`() {
        assertEquals(listOf("22", "21", "32", "31", "11"), ids(RequestBoard.arrange(all, Sort.STATUS)))
    }

    /**
     * Η αντίστροφη φορά γυρίζει τις **ομάδες**, όχι τη σειρά μέσα τους: και
     * τότε, από δύο αιτήματα που περιμένουν, πρώτο είναι το πιο πρόσφατο.
     */
    @Test
    fun `η αντιστροφή γυρίζει το κριτήριο και όχι την ισοβαθμία`() {
        assertEquals(listOf("11", "31", "21", "32", "22"), ids(RequestBoard.arrange(all, Sort.STATUS, reversed = true)))
    }

    /** Ελληνική αλφαβητική: το «Αντωνίου» πριν από το «Βασιλείου», το «Ωμέγα» τελευταίο. */
    @Test
    fun `ανά πελάτη, αλφαβητικά και μέσα στον πελάτη το πιο πρόσφατο πρώτο`() {
        assertEquals(listOf("22", "21", "11", "31", "32"), ids(RequestBoard.arrange(all, Sort.CLIENT)))
        assertEquals(listOf("31", "32", "11", "22", "21"), ids(RequestBoard.arrange(all, Sort.CLIENT, reversed = true)))
    }

    @Test
    fun `τα τονούμενα κεφαλαία δεν πάνε στο τέλος του αλφαβήτου`() {
        val rows = listOf(
            row(1, "Ωμέγα ΑΕ", "1", "2026-01-01 09:00:00", "2026-01-01 09:00:00"),
            row(2, "Άννα Βασιλείου", "2", "2026-01-01 09:00:00", "2026-01-01 09:00:00"),
            row(3, "Βήτα ΟΕ", "3", "2026-01-01 09:00:00", "2026-01-01 09:00:00"),
        )
        assertEquals(listOf("2", "3", "1"), ids(RequestBoard.arrange(rows, Sort.CLIENT)))
    }

    /** Αίτημα χωρίς ημερομηνία ενημέρωσης κρίνεται από την υποβολή του — δεν πέφτει στον πάτο. */
    @Test
    fun `χωρίς ημερομηνία ενημέρωσης μετρά η υποβολή`() {
        val bare = row(4, "Δέλτα ΙΚΕ", "41", "2026-10-09 09:00:00", "")
        assertEquals("41", ids(RequestBoard.arrange(all + bare)).first())
    }

    // ----------------------------------------------------- φίλτρο και αναζήτηση

    @Test
    fun `τα φίλτρα κρατούν ό,τι λέει το όνομά τους`() {
        assertEquals(listOf("21", "31", "32"), ids(RequestBoard.arrange(all, filter = Filter.PENDING)))
        assertEquals(listOf("22"), ids(RequestBoard.arrange(all, filter = Filter.FRESH)))
        assertEquals(listOf("22", "11"), ids(RequestBoard.arrange(all, filter = Filter.ANSWERED)))
        assertEquals(5, RequestBoard.arrange(all, filter = Filter.ALL).size)
    }

    /** Χωρίς τόνους και χωρίς κεφαλαία: έτσι γράφει κανείς σε κινητό. */
    @Test
    fun `η αναζήτηση βρίσκει πελάτη, ΑΦΜ, είδος και αριθμό υπόθεσης`() {
        assertEquals(listOf("22", "21"), ids(RequestBoard.arrange(all, query = "αντωνιου")))
        assertEquals(listOf("31", "32"), ids(RequestBoard.arrange(all, query = "123456783")))
        assertEquals(listOf("21"), ids(RequestBoard.arrange(all, query = "ΜΗΤΡΩΟΥ")))
        assertEquals(listOf("11"), ids(RequestBoard.arrange(all, query = "10000011/")))
        assertEquals(listOf("31"), ids(RequestBoard.arrange(all, query = " απορριφθηκε ")))
        assertTrue(RequestBoard.arrange(all, query = "δεν υπάρχει").isEmpty())
    }

    @Test
    fun `φίλτρο και αναζήτηση ισχύουν μαζί`() {
        assertEquals(listOf("21"), ids(RequestBoard.arrange(all, filter = Filter.PENDING, query = "αντωνίου")))
    }

    // ------------------------------------------------------------ ανά πελάτη

    private fun clients(groups: List<RequestBoard.Group>) = groups.map { it.clientName.substringBefore(' ') }

    /**
     * Μία κάρτα ανά πελάτη, με **όλα** τα αιτήματά του μέσα. Ο πελάτης παίρνει
     * τη θέση του καλύτερου αιτήματός του: εδώ, αυτού που κινήθηκε τελευταίο.
     */
    @Test
    fun `τα αιτήματα μαζεύονται ανά πελάτη, στη θέση του πιο πρόσφατου`() {
        val groups = RequestBoard.groups(all)
        assertEquals(listOf("Αντωνίου", "Ωμέγα", "Βασιλείου"), clients(groups))
        assertEquals(listOf("22", "21"), ids(groups[0].rows))
        assertEquals(listOf("31", "32"), ids(groups[1].rows))
        assertEquals(listOf("11"), ids(groups[2].rows))
        assertEquals("2026-10-08 15:30:00", groups[0].updated)
    }

    /** «2 αιτήματα · 1 σε αναμονή · 1 απαντημένο» — και κάθε αίτημα μετριέται μία φορά. */
    @Test
    fun `κάθε πελάτης γράφει πόσα αιτήματα έχει και σε ποια κατάσταση`() {
        val (antoniou, omega, vasileiou) = RequestBoard.groups(all)
        assertEquals("2 αιτήματα", antoniou.count)
        assertEquals("1 σε αναμονή · 1 απαντημένο", antoniou.statuses)
        assertEquals(1, antoniou.fresh)

        // Η απόρριψη δεν είναι «σε αναμονή», ακόμη κι αν δεν έχει κείμενο απάντησης.
        assertEquals("1 σε αναμονή · 1 απορρίφθηκε", omega.statuses)
        assertEquals(0, omega.fresh)

        assertEquals("1 αίτημα", vasileiou.count)
        assertEquals("1 απαντημένο", vasileiou.statuses)

        for (group in listOf(antoniou, omega, vasileiou)) {
            assertEquals(group.rows.size, group.waiting + group.rejected + group.answered)
        }
    }

    @Test
    fun `οι πληθυντικοί των καταστάσεων`() {
        val rows = listOf(
            row(1, "Α", "1", "2026-01-01 09:00:00", "2026-01-01 09:00:00"),
            row(1, "Α", "2", "2026-01-02 09:00:00", "2026-01-02 09:00:00"),
            row(1, "Α", "3", "2026-01-03 09:00:00", "2026-01-03 09:00:00", status = "Απορρίφθηκε", rejected = true),
            row(1, "Α", "4", "2026-01-04 09:00:00", "2026-01-04 09:00:00", status = "Απορρίφθηκε", rejected = true),
            row(1, "Α", "5", "2026-01-05 09:00:00", "2026-01-05 09:00:00", status = "Νέο", answer = ""),
            row(1, "Α", "6", "2026-01-06 09:00:00", "2026-01-06 09:00:00", status = "Νέο", answer = ""),
        )
        val group = RequestBoard.groups(rows).single()
        assertEquals("6 αιτήματα", group.count)
        assertEquals("2 σε αναμονή · 2 απορρίφθηκαν · 2 απαντημένα", group.statuses)
    }

    /** Με «κατάσταση», πρώτος ο πελάτης που έχει κάτι να δει κανείς· με «πελάτη», αλφαβητικά. */
    @Test
    fun `οι πελάτες ακολουθούν την ταξινόμηση που διάλεξε ο χρήστης`() {
        assertEquals(
            listOf("Αντωνίου", "Ωμέγα", "Βασιλείου"),
            clients(RequestBoard.groups(all, Sort.STATUS)),
        )
        assertEquals(
            listOf("Αντωνίου", "Βασιλείου", "Ωμέγα"),
            clients(RequestBoard.groups(all, Sort.CLIENT)),
        )
        assertEquals(
            listOf("Ωμέγα", "Βασιλείου", "Αντωνίου"),
            clients(RequestBoard.groups(all, Sort.CLIENT, reversed = true)),
        )
        // Παλαιότερη ενημέρωση πρώτα: ο πελάτης με το παλαιότερο αίτημα.
        assertEquals(
            listOf("Βασιλείου", "Ωμέγα", "Αντωνίου"),
            clients(RequestBoard.groups(all, reversed = true)),
        )
    }

    /** Με φίλτρο, ο πελάτης δείχνει μόνο όσα ταιριάζουν — και μετρά αυτά. */
    @Test
    fun `το φίλτρο κόβει μέσα στον πελάτη, και όποιος δεν έχει τίποτα φεύγει`() {
        val pending = RequestBoard.groups(all, filter = Filter.PENDING)
        assertEquals(listOf("Αντωνίου", "Ωμέγα"), clients(pending))
        assertEquals(listOf("21"), ids(pending[0].rows))
        assertEquals("1 αίτημα", pending[0].count)
        assertEquals("1 σε αναμονή", pending[0].statuses)

        val found = RequestBoard.groups(all, query = "ωμεγα")
        assertEquals(listOf("Ωμέγα"), clients(found))
        assertEquals(2, found[0].rows.size)
        assertTrue(RequestBoard.groups(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- σύνοψη

    @Test
    fun `η σύνοψη μετρά αιτήματα, πελάτες, εκκρεμή και νέα`() {
        val summary = RequestBoard.summary(all)
        assertEquals(5, summary.requests)
        assertEquals(3, summary.clients)
        assertEquals(3, summary.pending)
        assertEquals(1, summary.fresh)
        assertEquals(RequestBoard.Summary(0, 0, 0, 0), RequestBoard.summary(emptyList()))
    }

    /** Ο ίδιος αριθμός αιτήματος σε δύο πελάτες είναι δύο αιτήματα. */
    @Test
    fun `το κλειδί μιας γραμμής περιέχει και τον πελάτη`() {
        val a = row(1, "Α", "7", "2026-01-01 09:00:00", "2026-01-01 09:00:00")
        val b = row(2, "Β", "7", "2026-01-01 09:00:00", "2026-01-01 09:00:00")
        assertTrue(a.key != b.key)
        assertEquals(2, RequestBoard.arrange(listOf(a, b)).size)
    }
}
