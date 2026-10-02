package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.requests.AadeRequests.Kind
import gr.scanmydata.taxcenter.sched.RequestWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Τα Αιτήματά μου» της ΑΑΔΕ: ανάγνωση, και — το ουσιώδες — **τι είναι νέο**.
 *
 * Το δείγμα έχει το σχήμα που γράφει το `easynotify` με `which=REQUESTS`
 * (επαληθεύτηκε ζωντανά, 2 Οκτωβρίου 2026): `aadeRequests.items[]` με
 * `messageId` ως αριθμό, ημερομηνίες «yyyy-MM-dd HH:mm:ss», `messageStatus` /
 * `messageStatusText`, `answerText`. Αριθμοί υποθέσεων, ονόματα υπηρεσιών και
 * κείμενα είναι συνθετικά — κανένα πραγματικό αίτημα δεν μπαίνει στο repo.
 *
 * Στους δοκιμαστικούς λογαριασμούς **όλα** τα αιτήματα ήταν ήδη απαντημένα. Η
 * μετάβαση από εκκρεμές σε απαντημένο, που είναι ο λόγος ύπαρξης της
 * παρακολούθησης, δεν έχει φανεί ζωντανά — ελέγχεται εδώ.
 */
class AadeRequestsTest {

    private fun item(
        id: Int,
        status: String,
        code: Int,
        answer: String = "",
        updated: String = "2026-09-30 10:00:00",
        rejected: Boolean = false,
    ) = """
        {
          "messageId": $id, "caseNumber": "100000$id/20260901/0001",
          "submittedDate": "2026-09-01 09:15:00", "updatedDate": "$updated",
          "messageStatus": $code, "messageStatusText": "$status",
          "orgGroupText": "Δ.Ο.Υ. ΔΟΚΙΜΗΣ", "protipoTmimaText": "ΤΜΗΜΑ Α",
          "thematicalGroupText": "Εισόδημα", "diadikasiaText": "Τροποποιητική δήλωση Ε1",
          "processGroupText": "", "messageText": "Παρακαλώ για την εκκαθάριση.",
          "answerText": ${if (answer.isBlank()) "null" else "\"$answer\""},
          "transactorVat": "123456783", "taxeeName": "", "rejected": $rejected
        }
    """.trimIndent()

    private fun json(vararg items: String) =
        """{"portal":"AADE (GSIS OAM)","vat":"123456783","aadeRequests":{"count":${items.size},"summary":[],"items":[${items.joinToString(",")}]}}"""

    private val pending = item(1, "Σε επεξεργασία", 2)
    private val answered = item(1, "Απαντημένο", 3, answer = "Η δήλωσή σας εκκαθαρίστηκε.", updated = "2026-10-02 08:00:00")

    // --------------------------------------------------------------- ανάγνωση

    @Test
    fun `τα αιτήματα διαβάζονται με το σχήμα της πύλης`() {
        val request = AadeRequests.parse(json(answered))!!.single()
        assertEquals("1", request.id)
        assertEquals("1000001/20260901/0001", request.caseNumber)
        assertEquals("Απαντημένο", request.status)
        assertEquals(3, request.statusCode)
        assertEquals("Τροποποιητική δήλωση Ε1", request.title)
        assertEquals("Εισόδημα", request.topic)
        assertEquals("Δ.Ο.Υ. ΔΟΚΙΜΗΣ · ΤΜΗΜΑ Α", request.service)
        assertEquals("Η δήλωσή σας εκκαθαρίστηκε.", request.answer)
        assertTrue(request.answered)
    }

    /** Το JSON null δεν πρέπει να γίνει η λέξη «null» στην οθόνη. */
    @Test
    fun `αίτημα χωρίς απάντηση δεν εφευρίσκει κείμενο`() {
        val request = AadeRequests.parse(json(pending))!!.single()
        assertEquals("", request.answer)
        assertFalse(request.answered)
    }

    /**
     * Το είδαμε ζωντανά: κατάσταση «Απαντημένο» με **κενό** κείμενο απάντησης.
     * Μετρά ως απαντημένο — η κατάσταση είναι της πύλης, όχι δική μας εκτίμηση.
     */
    @Test
    fun `απαντημένο χωρίς κείμενο μετρά ως απαντημένο`() {
        assertTrue(AadeRequests.parse(json(item(1, "Απαντημένο", 3)))!!.single().answered)
        assertTrue(AadeRequests.parse(json(item(1, "ΑΠΑΝΤΗΜΕΝΟ", 3)))!!.single().answered)
    }

    @Test
    fun `τα νεότερα έρχονται πρώτα`() {
        val list = AadeRequests.parse(
            json(
                item(1, "Απαντημένο", 3, updated = "2026-08-01 10:00:00"),
                item(2, "Σε επεξεργασία", 2, updated = "2026-10-01 10:00:00"),
            ),
        )!!
        assertEquals(listOf("2", "1"), list.map { it.id })
    }

    /**
     * Το `easynotify` επιστρέφει `ok` ακόμη κι όταν το τμήμα των αιτημάτων
     * έσκασε. Αυτό **δεν** είναι «κανένα αίτημα».
     */
    @Test
    fun `τμήμα που έσκασε δεν διαβάζεται ως κενή λίστα`() {
        val broken = """{"vat":"123456783","aadeRequests":"ERR:filterMessages 500"}"""
        assertNull(AadeRequests.parse(broken))
        assertEquals("filterMessages 500", AadeRequests.error(broken))
        assertNull(AadeRequests.parse(""))
        assertNull(AadeRequests.parse("""{"gemiMessages":[]}"""))
        // Πραγματικά κανένα αίτημα: κενή λίστα, όχι null.
        assertEquals(0, AadeRequests.parse(json())!!.size)
    }

    // ------------------------------------------------------------ τι είναι νέο

    private fun stamps(vararg items: String) = AadeRequests.stamps(AadeRequests.parse(json(*items))!!)
    private fun diff(known: Map<String, String>?, vararg now: String) =
        AadeRequests.diff(known, AadeRequests.parse(json(*now))!!)

    /**
     * Ο πελάτης που μπαίνει σήμερα στην παρακολούθηση με δέκα αιτήματα
     * απαντημένα από πέρσι δεν στέλνει δέκα ειδοποιήσεις.
     */
    @Test
    fun `ο πρώτος έλεγχος δεν αναφέρει τίποτα`() {
        assertTrue(diff(null, answered, item(2, "Απαντημένο", 3, answer = "ΟΚ")).isEmpty())
    }

    @Test
    fun `εκκρεμές που απαντήθηκε είναι νέα απάντηση`() {
        val change = diff(stamps(pending), answered).single()
        assertEquals(Kind.ANSWERED, change.kind)
        assertEquals("1", change.request.id)
    }

    @Test
    fun `ό,τι δεν άλλαξε δεν αναφέρεται`() {
        assertTrue(diff(stamps(answered), answered).isEmpty())
        // Ούτε όταν αλλάζει μόνο η ημερομηνία ενημέρωσης.
        val touched = item(1, "Απαντημένο", 3, answer = "Η δήλωσή σας εκκαθαρίστηκε.", updated = "2026-10-03 09:00:00")
        assertTrue(diff(stamps(answered), touched).isEmpty())
    }

    /** Υποβλήθηκε και απαντήθηκε ανάμεσα σε δύο ελέγχους. */
    @Test
    fun `άγνωστο αίτημα που βρέθηκε απαντημένο είναι νέα απάντηση`() {
        val change = diff(stamps(answered), answered, item(2, "Απαντημένο", 3, answer = "Εγκρίθηκε.")).single()
        assertEquals("2", change.request.id)
        assertEquals(Kind.ANSWERED, change.kind)
    }

    /** Το υπέβαλε ο ίδιος ο λογιστής — δεν είναι είδηση. */
    @Test
    fun `νέο αίτημα που ακόμη περιμένει δεν αναφέρεται`() {
        assertTrue(diff(stamps(answered), answered, item(2, "Υποβληθέν", 1)).isEmpty())
    }

    @Test
    fun `δεύτερη απάντηση στο ίδιο αίτημα αναφέρεται ξανά`() {
        val again = item(1, "Απαντημένο", 3, answer = "Συμπληρωματικά: προσκομίστε το έντυπο.")
        assertEquals(Kind.ANSWERED, diff(stamps(answered), again).single().kind)
        // Και όταν το «απαντημένο χωρίς κείμενο» αποκτήσει κείμενο.
        assertEquals(Kind.ANSWERED, diff(stamps(item(1, "Απαντημένο", 3)), answered).single().kind)
    }

    @Test
    fun `αλλαγή κατάστασης χωρίς απάντηση είναι αλλαγή, όχι απάντηση`() {
        assertEquals(Kind.STATUS, diff(stamps(item(1, "Υποβληθέν", 1)), pending).single().kind)
        val closed = item(1, "Ολοκληρωμένο", 4, answer = "Η δήλωσή σας εκκαθαρίστηκε.")
        assertEquals(Kind.STATUS, diff(stamps(answered), closed).single().kind)
        val rejected = item(1, "Σε επεξεργασία", 2, rejected = true)
        assertEquals(Kind.STATUS, diff(stamps(pending), rejected).single().kind)
    }

    /** Η μνήμη κρατά αποτύπωμα, όχι το κείμενο της απάντησης. */
    @Test
    fun `το αποτύπωμα δεν περιέχει το κείμενο της απάντησης`() {
        val stamp = stamps(answered).getValue("1")
        assertFalse(stamp, stamp.contains("εκκαθαρίστηκε"))
        assertTrue(stamp, stamp.startsWith("απαντημενο|"))
    }

    @Test
    fun `οι ημερομηνίες γράφονται ελληνικά`() {
        assertEquals("30/09/2026 12:34", AadeRequests.date("2026-09-30 12:34:56"))
        assertEquals("30/09/2026", AadeRequests.date("2026-09-30"))
        assertEquals("κάτι άλλο", AadeRequests.date("κάτι άλλο"))
    }

    // ------------------------------------------------------------ απολογισμός

    @Test
    fun `η ειδοποίηση γράφει πλήθη και όχι ονόματα`() {
        val tally = RequestWatch.Tally(checked = 5, answered = 2, clients = 2)
        assertTrue(tally.news)
        assertEquals("Απαντήθηκαν 2 αιτήματα ΑΑΔΕ", tally.headline())
        assertEquals("2 νέες απαντήσεις σε 2 πελάτες · ελέγχθηκαν 5 πελάτες", tally.summary())
        assertEquals("Απαντήθηκε 1 αίτημα ΑΑΔΕ", RequestWatch.Tally(checked = 1, answered = 1, clients = 1).headline())
    }

    @Test
    fun `έλεγχος χωρίς νέα δεν είναι είδηση`() {
        val tally = RequestWatch.Tally(checked = 3, failed = 1)
        assertFalse(tally.news)
        assertEquals("Καμία νέα απάντηση · ελέγχθηκαν 3 πελάτες · 1 απέτυχαν", tally.summary())
    }

    @Test
    fun `ο απολογισμός ταξιδεύει ακέραιος από κομμάτι σε κομμάτι`() {
        val tally = RequestWatch.Tally(4, 2, 1, 3, 1, 1, 2)
        assertEquals(tally, RequestWatch.Tally.of(tally.toArray()))
        assertEquals(RequestWatch.Tally(), RequestWatch.Tally.of(null))
        assertEquals(RequestWatch.Tally(), RequestWatch.Tally.of(intArrayOf(1, 2)))
    }

    @Test
    fun `το μήνυμα της καρτέλας λέει τι βρέθηκε`() {
        assertEquals(
            "Ενημερώθηκε — καμία αλλαγή από την προηγούμενη φορά.",
            RequestsRefresh.describe(emptyList()),
        )
        val changes = diff(stamps(pending, item(2, "Υποβληθέν", 1)), answered, item(2, "Σε επεξεργασία", 2))
        assertEquals("Ενημερώθηκε — 1 νέα απάντηση · 1 αλλαγή κατάστασης.", RequestsRefresh.describe(changes))
    }
}
