package gr.scanmydata.taxcenter.keao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Η καρτέλα οφειλέτη ΚΕΑΟ, από το JSON του config στο έντυπο.
 *
 * Το δείγμα έχει τη δομή που γράφει πράγματι το `keao-debts` (τα ονόματα πεδίων
 * είναι της `KeaoResult` του hyperserver, αγγλικά και με τα δικά της
 * ορθογραφικά: `DeptorTransactions`, `DeptorID`). Ένα «διορθωμένο» δείγμα θα
 * περνούσε το τεστ και θα άφηνε την εφαρμογή να διαβάζει κενή καρτέλα.
 *
 * Τα ποσά είναι **συνθετικά** — καμία πραγματική οφειλή πελάτη δεν μπαίνει σε
 * αρχείο του αποθετηρίου.
 */
class KeaoCardTest {

    private val sample = """
    {
      "portal": "e-EFKA / ΚΕΑΟ",
      "afm": "123456783",
      "retrievedAt": "2026-09-17T08:00:00.000Z",
      "carriers": [
        {
          "Amo": "100",
          "CarrierDescr": "ΟΑΕΕ",
          "CarrierAm": "0123456",
          "CompanyName": "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
          "DeptorTransactions": {
            "DeptorID": "1234 5678 9012 3456",
            "BranchName": "ΚΕΑΟ ΑΘΗΝΩΝ",
            "Debits": {
              "Debit": "4.200,00", "Credit": "1.200,00", "Deleted": "0,00",
              "AdditionalFeesReduction": "0,00", "AdditionalFeesPaid": "120,00",
              "Reduction": "50,00", "Balance": "3.000,00",
              "DebitData": [
                { "Branch": "ΑΘΗΝΩΝ", "IssueDate": "01/02/2024", "DocumentInfo": "ΠΒΟ 55/2024",
                  "ConfirmInfo": "ΤΑΜΕΙΑΚΗ", "Debit": "4.200,00", "Balance": "3.000,00",
                  "Credit": "1.200,00", "Reduction": "50,00", "DeletedAmount": "0,00",
                  "Additional": "300,00", "AdditionalReduction": "0,00" }
              ]
            },
            "Credits": { "TotalAmount": "1.200,00", "AdditionalFees": "120,00",
                         "PrimaryAmount": "1.080,00", "Increments": "0,00", "CreditData": [] },
            "Regulated": {
              "RegulatedData": [
                {
                  "Branch": "ΑΘΗΝΩΝ", "ResolutionInfo": "Ρύθμιση 12 δόσεων 2025",
                  "RegulatePrimary": "2.500,00", "Additional": "300,00", "Interest": "200,00",
                  "Total": "3.000,00", "ResolutionType": "ΠΑΓΙΑ", "PayType": "ΤΑΥΤΟΤΗΤΑ",
                  "RegulatedStatus": "ΕΝΕΡΓΗ",
                  "RegulatedInstallmentData": [
                    { "RowAA": "1", "ExpirationDate": "31/01/2026", "Amount": "250,00",
                      "Payed": "250,00", "Balance": "0,00", "Increments": "0,00" },
                    { "RowAA": "2", "ExpirationDate": "28/02/2026", "Amount": "250,00",
                      "Payed": "0,00", "Balance": "250,00", "Increments": "0,00" }
                  ]
                }
              ]
            }
          },
          "Payments": {
            "CovidDeptorID": "",
            "OutOfRegulatedDepts": [
              { "IssueDate": "10/03/2026", "DocumentInfo": "ΠΒΟ 91/2026", "Primary": "400,00",
                "Additional": "40,00", "Total": "440,00" }
            ]
          }
        },
        {
          "Amo": "200",
          "CarrierDescr": "ΙΚΑ-ΕΤΑΜ",
          "CarrierAm": "0987654",
          "CompanyName": "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
          "DeptorTransactions": {
            "DeptorID": "9999 8888 7777 6666",
            "BranchName": "ΚΕΑΟ ΠΕΙΡΑΙΑ",
            "Debits": { "Debit": "800,00", "Credit": "800,00", "Balance": "0,00",
                        "Reduction": "", "Deleted": "", "DebitData": [] },
            "Credits": { "CreditData": [] },
            "Regulated": { "RegulatedData": [] }
          },
          "Payments": { "CovidDeptorID": "", "OutOfRegulatedDepts": [] }
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `διαβάζονται όλοι οι φορείς με την ταυτότητα οφειλέτη τους`() {
        val carriers = KeaoCard.parse(sample)
        assertEquals(2, carriers.size)
        assertEquals("ΟΑΕΕ", carriers[0].description)
        assertEquals("1234 5678 9012 3456", carriers[0].debtorId)
        assertEquals("ΚΕΑΟ ΑΘΗΝΩΝ", carriers[0].branch)
        assertEquals("3.000,00", carriers[0].totals.balance)
        assertEquals("9999 8888 7777 6666", carriers[1].debtorId)
    }

    /**
     * Η **επόμενη** δόση είναι η πρώτη με υπόλοιπο, όχι η πρώτη της λίστας.
     * Αν έδειχνε την πληρωμένη, ο πελάτης θα πλήρωνε δεύτερη φορά δόση που ήδη
     * έκλεισε και θα άφηνε την επόμενη να λήξει.
     */
    @Test
    fun `η επόμενη δόση είναι η πρώτη απλήρωτη`() {
        val regulation = KeaoCard.parse(sample)[0].regulated.single()
        assertEquals("ΕΝΕΡΓΗ", regulation.status)
        assertEquals(2, regulation.installments)
        assertEquals("28/02/2026", regulation.nextDue)
        assertEquals("250,00", regulation.nextAmount)
    }

    @Test
    fun `χωρίς ρυθμίσεις δεν εφευρίσκεται δόση`() {
        assertTrue(KeaoCard.parse(sample)[1].regulated.isEmpty())
        assertTrue(KeaoCard.parse(sample)[1].outstanding.isEmpty())
    }

    /**
     * Ένα έντυπο ανά φορέα, με **ξεχωριστό αρχείο**. Το κρίσιμο δεν είναι το
     * πλήθος αλλά ότι κάθε έντυπο κουβαλά τη δική του ταυτότητα πληρωμής:
     * ένα συγκεντρωτικό με δύο ταυτότητες είναι ο ασφαλέστερος τρόπος να
     * πληρωθεί λάθος φορέας.
     */
    @Test
    fun `βγαίνει ένα έντυπο ανά φορέα`() {
        val reports = KeaoCard.reports(
            carriers = KeaoCard.parse(sample),
            clientName = "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
            afm = "123456783",
            office = "Λογιστικό Γραφείο",
            retrievedAt = "17/09/2026 11:00",
        )
        assertEquals(2, reports.size)
        assertEquals("KEAO_KARTELA_123456783_0123456.pdf", reports[0].fileName)
        assertEquals("KEAO_KARTELA_123456783_0987654.pdf", reports[1].fileName)
        assertEquals("1234 5678 9012 3456", reports[0].debtorId)
        assertEquals("9999 8888 7777 6666", reports[1].debtorId)
        // Δύο διαφορετικά αρχεία — αλλιώς το δεύτερο σβήνει το πρώτο.
        assertTrue(reports[0].fileName != reports[1].fileName)
    }

    @Test
    fun `το έντυπο δείχνει υπόλοιπο, ρυθμίσεις και οφειλές εκτός ρύθμισης`() {
        val report = KeaoCard.reports(
            KeaoCard.parse(sample), "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ", "123456783", "", "17/09/2026 11:00",
        ).first()

        assertTrue("λείπει το υπόλοιπο", report.summary.any { it.first == "Υπόλοιπο" && it.second == "3.000,00" })
        assertEquals(listOf("Ρυθμίσεις", "Οφειλές εκτός ρύθμισης"), report.tables.map { it.caption })

        val regulated = report.tables.first()
        assertEquals(regulated.headers.size, regulated.weights.size)
        assertEquals(1, regulated.rows.size)
        assertEquals(regulated.headers.size, regulated.rows.first().size)
        assertTrue(regulated.rows.first().any { it.contains("28/02/2026") })

        assertTrue(report.identity.any { it.second.contains("123456783") })
        assertTrue(report.footer.any { it.contains("17/09/2026") })
    }

    /**
     * Ο φορέας χωρίς ΑΜ δεν πρέπει να δώσει αρχείο που τελειώνει σε `_` ούτε να
     * συγκρουστεί με τον επόμενο — τα ονόματα καταλήγουν όλα στον ίδιο φάκελο.
     */
    @Test
    fun `όνομα αρχείου χωρίς αριθμό μητρώου`() {
        val json = """{"carriers":[{"Amo":"","CarrierDescr":"ΤΑΜΕΙΟ","CarrierAm":"",
            "DeptorTransactions":{"DeptorID":"1","Debits":{"Balance":"1,00"}}}]}"""
        val report = KeaoCard.reports(KeaoCard.parse(json), "Χ", "123456783", "", "").single()
        assertEquals("KEAO_KARTELA_123456783_1.pdf", report.fileName)
    }

    @Test
    fun `κενό JSON δεν σκάει`() {
        assertTrue(KeaoCard.parse("{}").isEmpty())
        assertTrue(KeaoCard.parse("""{"carriers":[]}""").isEmpty())
    }
}
