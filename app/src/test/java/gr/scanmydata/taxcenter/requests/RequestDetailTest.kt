package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.requests.RequestDetail.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Ένα αίτημα ολόκληρο, με τα συνημμένα του.
 *
 * Το δείγμα έχει το σχήμα που γράφει το `aade-request` (επαληθεύτηκε ζωντανά,
 * 9 Οκτωβρίου 2026): `detail` όπως το δίνει το `/fetch` της πύλης, `comm` από
 * το `getMessageComm`, και `files[]` με `fileOrigin` και `savedFile`. Αριθμοί
 * υποθέσεων, ονόματα αρχείων και κείμενα είναι συνθετικά.
 *
 * Το `comm` ήταν **κενό σε κάθε αίτημα που είδαμε** — το σχήμα του δεν είναι
 * γνωστό. Τα τεστ του εδώ δείχνουν τι υποθέτει ο κώδικας (κείμενο και
 * ημερομηνία, με όποιο όνομα), όχι τι στέλνει η πύλη.
 */
class RequestDetailTest {

    private fun sample(comm: String = "[]", extra: String = "") = """
        {
          "portal": "AADE (GSIS OAM)",
          "retrievedAt": "2026-10-09T09:15:00.000Z",
          "messageId": "4242",
          "detail": {
            "caseNumber": "1000001/20260901/0001", "submittedDate": "2026-09-01",
            "messageStatus": 3, "messageReferenceId": "1000000/20260801/0001",
            "thematicalGroupText": "Εισόδημα", "diadikasiaText": "Τροποποιητική δήλωση Ε1",
            "orgGroupText": "Δ.Ο.Υ. ΔΟΚΙΜΗΣ", "protipoTmimaText": "ΤΜΗΜΑ Α",
            "messageText": "Παρακαλώ για την εκκαθάριση.",
            "answerText": "Σας επισυνάπτουμε την πράξη.",
            "supportingDocs": "1. Αίτηση<br>2. Αντίγραφο ταυτότητας",
            "reviewReason": "", "rejectReason": null, "isArchived": true
            $extra
          },
          "comm": $comm,
          "files": [
            { "fileId": 9001, "fileName": "Αίτηση πελάτη.pdf", "fileOrigin": "attachedFile",
              "fileSize": 1288743, "savedFile": "AITIMA_4242_9001_Αίτηση_πελάτη.pdf", "bytes": 1288743 },
            { "fileId": 9002, "fileName": "εξουσιοδότηση.jpg", "fileOrigin": "certificateFile",
              "fileSize": 73674, "savedFile": "AITIMA_4242_9002_εξουσιοδότηση.jpg", "bytes": 73674 },
            { "fileId": 9003, "fileName": "Πράξη διορθωτικού.pdf", "fileOrigin": "internalFile",
              "fileSize": 149347, "savedFile": "AITIMA_4242_9003_Πράξη_διορθωτικού.pdf", "bytes": 149347 },
            { "fileId": 9004, "fileName": "Συνοδευτικό.pdf", "fileOrigin": "internalFile",
              "fileSize": 800, "error": "HTTP 500" },
            { "fileId": 9005, "fileName": "extra.pdf", "fileOrigin": "commFile",
              "fileSize": null, "savedFile": "AITIMA_4242_9005_extra.pdf", "bytes": 12 }
          ]
        }
    """.trimIndent()

    // --------------------------------------------------------------- ανάγνωση

    @Test
    fun `το πλήρες αίτημα διαβάζεται με το κείμενο και την απάντησή του`() {
        val detail = RequestDetail.parse(sample())!!
        assertEquals("4242", detail.id)
        assertEquals("1000001/20260901/0001", detail.caseNumber)
        assertEquals("Τροποποιητική δήλωση Ε1", detail.process)
        assertEquals("Εισόδημα", detail.topic)
        assertEquals("Δ.Ο.Υ. ΔΟΚΙΜΗΣ · ΤΜΗΜΑ Α", detail.service)
        assertEquals("Παρακαλώ για την εκκαθάριση.", detail.text)
        assertEquals("Σας επισυνάπτουμε την πράξη.", detail.answer)
        assertEquals("1000000/20260801/0001", detail.reference)
        assertTrue(detail.archived)
        // Το JSON null δεν γίνεται η λέξη «null».
        assertEquals("", detail.rejectReason)
        assertEquals(Instant.parse("2026-10-09T09:15:00Z").toEpochMilli(), detail.retrievedAt)
    }

    /**
     * Ο λόγος ύπαρξης αυτής της οθόνης: ό,τι υπέβαλε ο πελάτης χωριστά από ό,τι
     * έστειλε η υπηρεσία. Η εξουσιοδότηση πάει με τα του πελάτη.
     */
    @Test
    fun `τα συνημμένα χωρίζονται σε όσα υποβλήθηκαν και όσα ήρθαν με την απάντηση`() {
        val detail = RequestDetail.parse(sample())!!
        assertEquals(5, detail.attachments.size)
        assertEquals(listOf("9001", "9002"), detail.submitted.map { it.id })
        assertEquals(listOf(Origin.SUBMITTED, Origin.CERTIFICATE), detail.submitted.map { it.origin })
        assertEquals(listOf("9003", "9004"), detail.replies.map { it.id })
        assertEquals(listOf("9005"), detail.threadFiles.map { it.id })

        val first = detail.attachments[0]
        // Στην οθόνη το όνομα της πύλης· στον δίσκο το όνομα που έγραψε το config.
        assertEquals("Αίτηση πελάτη.pdf", first.name)
        assertEquals("AITIMA_4242_9001_Αίτηση_πελάτη.pdf", first.saved)
        assertEquals(1288743L, first.size)
        assertTrue(first.downloaded)
    }

    @Test
    fun `συνημμένο που δεν κατέβηκε λέει γιατί και μετριέται`() {
        val detail = RequestDetail.parse(sample())!!
        val failed = detail.attachments.first { it.id == "9004" }
        assertFalse(failed.downloaded)
        assertEquals("HTTP 500", failed.error)
        assertEquals(1, detail.missing)
        // Άγνωστο μέγεθος δεν γίνεται αρνητικό ούτε «null».
        assertEquals(0L, detail.attachments.first { it.id == "9005" }.size)
    }

    @Test
    fun `προέλευση που δεν ξέρουμε μένει με τα υποβληθέντα και δεν χάνεται`() {
        val json = sample().replace("\"fileOrigin\": \"attachedFile\"", "\"fileOrigin\": \"somethingNew\"")
        val detail = RequestDetail.parse(json)!!
        assertEquals(Origin.OTHER, detail.attachments[0].origin)
        assertTrue(detail.attachments[0] in detail.submitted)
    }

    @Test
    fun `τα κείμενα της πύλης καθαρίζονται από σήμανση`() {
        val detail = RequestDetail.parse(sample())!!
        assertEquals("1. Αίτηση\n2. Αντίγραφο ταυτότητας", detail.supporting)
        assertEquals("Α & Β", RequestDetail.plain("Α &amp; Β"))
        assertEquals("γραμμή 1\nγραμμή 2", RequestDetail.plain("<p>γραμμή 1</p><p>γραμμή 2</p>"))
        // Η διπλή κωδικοποίηση ξετυλίγεται **μία** φορά.
        assertEquals("&lt;b&gt;", RequestDetail.plain("&amp;lt;b&amp;gt;"))
        // Ένα «<» που δεν είναι ετικέτα μένει όπως είναι.
        assertEquals("ποσό < 100", RequestDetail.plain("ποσό < 100"))
    }

    /**
     * Το σχήμα της αλληλογραφίας δεν είναι γνωστό. Ο κώδικας παίρνει από κάθε
     * εγγραφή το πρώτο πεδίο «…Text» με περιεχόμενο και την πρώτη ημερομηνία —
     * και μια εγγραφή χωρίς κείμενο δεν εμφανίζεται καθόλου.
     */
    @Test
    fun `η αλληλογραφία διαβάζεται χωρίς να υποθέτει ονόματα πεδίων`() {
        val comm = """
            [
              { "id": 1, "messageCommText": "Συμπληρωματικά στοιχεία.", "createdDate": "2026-09-10 12:00:00" },
              { "id": 2, "answerText": "  ", "replyText": "Ελήφθησαν." },
              { "id": 3, "status": 2 }
            ]
        """.trimIndent()
        val detail = RequestDetail.parse(sample(comm))!!
        assertEquals(2, detail.thread.size)
        assertEquals("Συμπληρωματικά στοιχεία.", detail.thread[0].text)
        assertEquals("2026-09-10 12:00:00", detail.thread[0].date)
        assertEquals("Ελήφθησαν.", detail.thread[1].text)
        assertEquals("", detail.thread[1].date)

        // Ό,τι δεν είναι πίνακας δεν σκάει — απλώς δεν δίνει αλληλογραφία.
        assertTrue(RequestDetail.parse(sample("{\"error\":\"x\"}"))!!.thread.isEmpty())
        assertTrue(RequestDetail.parse(sample("null"))!!.thread.isEmpty())
    }

    @Test
    fun `ό,τι δεν είναι αίτημα δίνει null και όχι άδειο αίτημα`() {
        assertNull(RequestDetail.parse(""))
        assertNull(RequestDetail.parse("{}"))
        assertNull(RequestDetail.parse("""{"messageId":"1"}"""))
        assertNull(RequestDetail.parse("""{"detail":{}}"""))
    }

    @Test
    fun `το αρχείο του αιτήματος ονομάζεται όπως το γράφει το config`() {
        assertEquals("AITIMA_4242.json", RequestDetail.fileName("4242"))
        // Ο αριθμός δεν μπορεί να βγάλει τη διαδρομή από τον φάκελο.
        assertEquals("AITIMA_42.json", RequestDetail.fileName("../4/2"))
    }

    // ------------------------------------------------------- χρειάζεται νέα λήψη;

    private val athens = ZoneId.of("Europe/Athens")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /**
     * Το αίτημα κατέβηκε όσο περίμενε, και μετά απαντήθηκε. Τα παλιά συνημμένα
     * θα έδειχναν ένα αίτημα χωρίς απάντηση — πρέπει να ξανακατέβει.
     */
    @Test
    fun `αίτημα που ενημερώθηκε μετά τη λήψη θέλει νέα λήψη`() {
        // 09:15Z = 12:15 ώρα Ελλάδας (καλοκαίρι, UTC+3).
        val retrieved = at("2026-10-09T09:15:00Z")
        assertTrue(RequestDetail.stale(retrieved, "2026-10-09 12:16:00", athens))
        assertFalse(RequestDetail.stale(retrieved, "2026-10-09 12:14:00", athens))
        assertFalse(RequestDetail.stale(retrieved, "2026-09-30 10:00:00", athens))
    }

    @Test
    fun `χωρίς ώρα λήψης ξανακατεβαίνει, χωρίς ώρα ενημέρωσης όχι`() {
        assertTrue(RequestDetail.stale(0L, "2026-09-30 10:00:00", athens))
        // Η λίστα δεν έδωσε (ή δεν διαβάζεται) ημερομηνία: δεν υπάρχει λόγος για σύνδεση.
        assertFalse(RequestDetail.stale(at("2026-10-09T09:15:00Z"), "", athens))
        assertFalse(RequestDetail.stale(at("2026-10-09T09:15:00Z"), "χθες", athens))
    }

    // ---------------------------------------------------------------- μέγεθος

    @Test
    fun `το μέγεθος γράφεται όπως το διαβάζει άνθρωπος`() {
        assertEquals("", RequestDetail.size(0))
        assertEquals("800 B", RequestDetail.size(800))
        assertEquals("72 KB", RequestDetail.size(73674))
        assertEquals("1,2 MB", RequestDetail.size(1288743))
        assertEquals("4,0 MB", RequestDetail.size(4165458))
    }
}
