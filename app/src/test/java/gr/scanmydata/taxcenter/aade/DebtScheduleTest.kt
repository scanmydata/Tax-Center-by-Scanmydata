package gr.scanmydata.taxcenter.aade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Το δοσολόγιο των οφειλών ΑΑΔΕ, από το JSON του `aade-debts` στη σελίδα που
 * προσαρτάται στην Ταυτότητα Οφειλής.
 *
 * Οι **δομές** είναι από πραγματικό λογαριασμό: οι επικεφαλίδες των πινάκων
 * είναι λέξη προς λέξη αυτές που στέλνει η πύλη (μαζί με το «Αναστολή
 * Είσπραξης» που δεν είναι ποσό και το «€» κολλημένο στα ποσά), και η γενική
 * εικόνα δόσεων έρχεται ως ζεύγη ετικέτα-τιμή. Τα **ποσά και τα ονόματα** είναι
 * συνθετικά.
 */
class DebtScheduleTest {

    private val sample = """
    {
      "portal": "AADE Προσωποποιημένη Πληροφόρηση",
      "afm": "123456783",
      "sections": {
        "debts_unregulated": {
          "title": "Οφειλές εκτός Ρύθμισης και Πληρωμή",
          "debts": [
            {
              "overdue": false,
              "fields": {
                "ΔΟΥ": "ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ",
                "Είδος φόρου": "ΧΡΕΩΣΤΙΚΕΣ ΔΗΛΩΣΕΙΣ ΦΠΑ",
                "Πηγή": "ΚΕΦΟΔΕ ΑΤΤΙΚΗΣ",
                "Οικ. έτος": "2026",
                "Ημ/νία Βεβαίωσης": "27/07/2026",
                "Συνολικό Ποσό Οφειλής": "1.005,50 €"
              },
              "total": "1.005,50 €",
              "toPdf": "OFEILI_123456783_ΧΡΕΩΣΤΙΚΕΣ_ΔΗΛΩΣΕΙΣ_ΦΠΑ_1.005,50.pdf",
              "general": {
                "rows": [
                  ["Αριθμός δόσεων", "2"],
                  ["Ημ/νία πρώτης δόσης", "31/07/2026"],
                  ["Ποσό έκπτωσης", "0,00 €"]
                ]
              },
              "installments": {
                "headers": [
                  "Α/Α δόσης", "Ημ/νία λήξης δόσης", "Υπόλοιπο Δόσης",
                  "Προσαυξήσεις, Τόκοι, Τέλη", "Συνολικό Υπόλοιπο Ποσό", "Αναστολή Είσπραξης"
                ],
                "rows": [
                  ["1", "31/07/2026", "0,00 €", "0,00 €", "0,00 €", "ΟΧΙ"],
                  ["2", "31/08/2026", "1.000,00 €", "5,50 €", "1.005,50 €", "ΟΧΙ"]
                ]
              }
            },
            {
              "fields": { "Είδος φόρου": "ΕΝΦΙΑ" },
              "total": "80,00 €",
              "toPdf": "OFEILI_123456783_ΕΝΦΙΑ_80,00.pdf"
            }
          ]
        },
        "debts_arrangement": {
          "title": "Οφειλές σε Ρύθμιση και Πληρωμή",
          "debts": [
            {
              "fields": { "Τύπος Ρύθμισης": "ΠΑΓΙΑ ΡΥΘΜΙΣΗ 12 ΔΟΣΕΩΝ", "Οικ. έτος": "2026" },
              "total": "600,00 €",
              "toCode": "RF45000000000000000000123",
              "toPdf": "RYTHMISI_123456783_2026_137612.pdf",
              "installments": {
                "headers": ["Α/Α δόσης", "Ημ/νία λήξης δόσης", "Υπόλοιπο Δόσης", "Συνολικό Υπόλοιπο Ποσό"],
                "rows": [
                  ["1", "31/03/2026", "0,00 €", "0,00 €"],
                  ["2", "30/04/2026", "300,00 €", "300,00 €"]
                ]
              }
            }
          ]
        },
        "payments": { "title": "Στοιχεία Πληρωμών", "rows": [["κάτι"]] }
      }
    }
    """.trimIndent()

    private fun attachments() = DebtSchedule.attachments(
        json = sample,
        clientName = "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
        afm = "123456783",
        office = "Λογιστικό Γραφείο",
        retrievedAt = "18/09/2026 09:00",
    )

    @Test
    fun `δοσολόγιο βγαίνει μόνο για όσες οφειλές έχουν δόσεις`() {
        val all = attachments()
        assertEquals("η οφειλή χωρίς δόσεις δεν παράγει σελίδα", 2, all.size)
        assertEquals("OFEILI_123456783_ΧΡΕΩΣΤΙΚΕΣ_ΔΗΛΩΣΕΙΣ_ΦΠΑ_1.005,50.pdf", all[0].target)
        assertEquals("RYTHMISI_123456783_2026_137612.pdf", all[1].target)
        assertEquals("Ανάλυση δόσεων οφειλής", all[0].report.title)
        assertEquals("Δοσολόγιο ρύθμισης", all[1].report.title)
    }

    @Test
    fun `η σελίδα λέει ποιανού είναι και ποια οφειλή αφορά`() {
        val report = attachments().first().report
        val identity = report.identity.toMap()
        assertEquals("ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ · 123456783", identity["Υπόχρεος"])
        assertEquals("ΧΡΕΩΣΤΙΚΕΣ ΔΗΛΩΣΕΙΣ ΦΠΑ", identity["Οφειλή"])
        assertEquals("ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ", identity["ΔΟΥ"])
        assertEquals("27/07/2026", identity["Ημ/νία Βεβαίωσης"])
        assertEquals("1.005,50 €", identity["Συνολικό ποσό"])
        // Η ταυτότητα πληρωμής είναι ήδη τυπωμένη στη σελίδα της ΑΑΔΕ που
        // προηγείται — δεύτερη εμφάνιση μοιάζει με δεύτερο κωδικό.
        assertEquals("", report.debtorId)
        assertTrue(report.footer.any { it.contains("18/09/2026 09:00") })
    }

    @Test
    fun `η γενική εικόνα δόσεων μπαίνει στη σύνοψη`() {
        val summary = attachments().first().report.summary.toMap()
        assertEquals("2", summary["Αριθμός δόσεων"])
        assertEquals("31/07/2026", summary["Ημ/νία πρώτης δόσης"])
    }

    /**
     * Τα σύνολα μπαίνουν **μόνο** στις στήλες που είναι ποσά, και το κρίνει το
     * περιεχόμενο: η «Αναστολή Είσπραξης» λέει ΟΧΙ και ο «Α/Α δόσης» μετράει
     * δόσεις. Ένας πίνακας αντιστοίχισης ονομάτων θα άθροιζε τα Α/Α την πρώτη
     * φορά που η ΑΑΔΕ θα άλλαζε μια λέξη στην επικεφαλίδα.
     */
    @Test
    fun `τα σύνολα αθροίζουν μόνο τις στήλες με ποσά`() {
        val table = attachments().first().report.sections.single().table!!
        assertEquals(6, table.headers.size)
        assertEquals("Α/Α δόσης", table.headers.first())
        assertEquals(2, table.rows.size)
        assertEquals(
            listOf("ΣΥΝΟΛΑ", "", "1.000,00", "5,50", "1.005,50", ""),
            table.totals,
        )
    }

    @Test
    fun `η ρύθμιση κρατά τις δικές της στήλες`() {
        val table = attachments()[1].report.sections.single().table!!
        assertEquals(4, table.headers.size)
        assertEquals(listOf("ΣΥΝΟΛΑ", "", "300,00", "300,00"), table.totals)
    }

    /** Όνομα για δικό του αρχείο, όταν το έντυπο της πύλης δεν βγήκε. */
    @Test
    fun `το εφεδρικό όνομα λέει τι είναι και ποιανού`() {
        assertEquals(
            "DOSEIS_123456783_ΧΡΕΩΣΤΙΚΕΣ_ΔΗΛΩΣΕΙΣ_ΦΠΑ_1.005,50.pdf",
            attachments().first().fallback,
        )
        assertEquals(
            "DOSEIS_123456783_ΦΠΑ.pdf",
            DebtSchedule.fallbackName("123456783", "ΦΠΑ", ""),
        )
    }

    @Test
    fun `άδειο ή χαλασμένο JSON δεν σκάει`() {
        val blanks = listOf("", "{}", "όχι json", """{"sections":{}}""")
        for (json in blanks) {
            assertTrue(json, DebtSchedule.attachments(json, "Χ", "123456783", "", "").isEmpty())
        }
    }
}
