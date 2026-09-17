package gr.scanmydata.taxcenter.keao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Οι **δομές** είναι από πραγματικό λογαριασμό (δύο φορείς με τον ίδιο Αριθμό
 * Μητρώου, ρύθμιση με πληρωμένες και απλήρωτες δόσεις)· τα **ποσά και τα
 * ονόματα** είναι συνθετικά. Καμία πραγματική οφειλή πελάτη δεν μπαίνει σε
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
          "Amo": "3143975",
          "CarrierDescr": "Ληξιπρόθεσμο - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ",
          "CarrierAm": "9310464020",
          "CompanyName": "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
          "DeptorTransactions": {
            "DeptorID": "RF09902208120000003143975",
            "BranchName": "ΚΕΑΟ ΑΘΗΝΩΝ",
            "Debits": {
              "Debit": "4.200,00", "Credit": "1.200,00", "Deleted": "0,00",
              "AdditionalFeesReduction": "0,00", "AdditionalFeesPaid": "120,00",
              "Reduction": "50,00", "Balance": "3.000,00", "DebitData": []
            },
            "Credits": { "TotalAmount": "1.200,00", "CreditData": [] },
            "Regulated": {
              "RegulatedData": [
                {
                  "Branch": "3000000444", "ResolutionInfo": "137612 07/02/2026",
                  "RegulatePrimary": "2.500,00", "Additional": "300,00", "Interest": "200,00",
                  "Total": "3.000,00",
                  "ResolutionType": "66 Ν.4152/13 ΠΑΡ.ΙΑ.ΙΑ1 ΠΑΓΙΑ ΡΥΘΜΙΣΗ",
                  "PayType": "Σε 24 Δόσεις", "RegulatedStatus": "Ενεργή",
                  "RegulatedInstallmentData": [
                    { "RowAA": "1", "ExpirationDate": "31/01/2026", "Amount": "250,00",
                      "Payed": "250,00", "Balance": "0,00", "Increments": "0,00" },
                    { "RowAA": "2", "ExpirationDate": "28/02/2026", "Amount": "250,00",
                      "Payed": "0,00", "Balance": "250,00", "Increments": "1,50" },
                    { "RowAA": "3", "ExpirationDate": "31/03/2026", "Amount": "1.250,00",
                      "Payed": "0,00", "Balance": "1.250,00", "Increments": "0,00" }
                  ]
                },
                {
                  "ResolutionInfo": "99001 01/01/2020", "Total": "800,00",
                  "ResolutionType": "ΠΑΛΙΑ ΡΥΘΜΙΣΗ", "PayType": "Σε 12 Δόσεις",
                  "RegulatedStatus": "Απολεσθείσα",
                  "RegulatedInstallmentData": [
                    { "RowAA": "1", "ExpirationDate": "31/01/2020", "Amount": "800,00",
                      "Payed": "0,00", "Balance": "800,00", "Increments": "0,00" }
                  ]
                }
              ]
            }
          },
          "Payments": {
            "CovidDeptorID": "",
            "OutOfRegulatedDepts": [
              { "IssueDate": "10/03/2026", "DocumentInfo": "ΠΕΕΤ ΑΚ/438558", "Primary": "400,00",
                "Additional": "40,00", "Total": "440,00" },
              { "IssueDate": "10/04/2026", "DocumentInfo": "ΠΕΕΤ ΑΚ/438559", "Primary": "100,50",
                "Additional": "10,25", "Total": "110,75" }
            ]
          }
        },
        {
          "Amo": "5546808",
          "CarrierDescr": "Ληξιπρόθεσμο - ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΤΕΚΑ",
          "CarrierAm": "9310464020",
          "CompanyName": "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
          "DeptorTransactions": {
            "DeptorID": "RF28902208120000005546808",
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

    private fun reports(scope: String) = KeaoCard.reports(
        carriers = KeaoCard.parse(sample),
        clientName = "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ",
        afm = "123456783",
        office = "Λογιστικό Γραφείο",
        retrievedAt = "17/09/2026 11:00",
        scope = scope,
    )

    // ----------------------------------------------------------- ανάγνωση

    @Test
    fun `διαβάζονται όλοι οι φορείς με την ταυτότητα οφειλέτη τους`() {
        val carriers = KeaoCard.parse(sample)
        assertEquals(2, carriers.size)
        assertEquals("RF09902208120000003143975", carriers[0].debtorId)
        assertEquals("ΚΕΑΟ ΑΘΗΝΩΝ", carriers[0].branch)
        assertEquals("3.000,00", carriers[0].totals.balance)
        assertEquals("RF28902208120000005546808", carriers[1].debtorId)
    }

    /**
     * Η **επόμενη** δόση είναι η πρώτη με υπόλοιπο, όχι η πρώτη της λίστας.
     * Αν έδειχνε την πληρωμένη, ο πελάτης θα πλήρωνε δεύτερη φορά δόση που ήδη
     * έκλεισε και θα άφηνε την επόμενη να λήξει.
     */
    @Test
    fun `η επόμενη δόση είναι η πρώτη απλήρωτη`() {
        val regulation = KeaoCard.parse(sample)[0].regulated.first()
        assertEquals("Ενεργή", regulation.status)
        assertEquals(3, regulation.installments)
        assertEquals(2, regulation.pending.size)
        assertEquals("28/02/2026", regulation.nextDue)
        assertEquals("250,00", regulation.nextAmount)
        assertEquals("31/03/2026", regulation.lastDue)
        assertTrue(regulation.active)
    }

    @Test
    fun `χωρίς ρυθμίσεις δεν εφευρίσκεται δόση`() {
        val teka = KeaoCard.parse(sample)[1]
        assertTrue(teka.regulated.isEmpty())
        assertTrue(teka.outstanding.isEmpty())
    }

    // -------------------------------------------------------------- έντυπα

    /**
     * Ένα έντυπο ανά φορέα, με **ξεχωριστό αρχείο**. Το κρίσιμο δεν είναι το
     * πλήθος αλλά ότι κάθε έντυπο κουβαλά τη δική του ταυτότητα πληρωμής:
     * ένα συγκεντρωτικό με δύο ταυτότητες είναι ο ασφαλέστερος τρόπος να
     * πληρωθεί λάθος φορέας.
     */
    @Test
    fun `βγαίνει ένα έντυπο ανά φορέα`() {
        val reports = reports(KeaoCard.SCOPE_REGULATED)
        assertEquals(2, reports.size)
        // Με τον ΑΜΟ και όχι με τον ΑΜ: οι δύο φορείς μοιράζονται τον ίδιο ΑΜ.
        assertEquals("KEAO_KARTELA_123456783_3143975.pdf", reports[0].fileName)
        assertEquals("KEAO_KARTELA_123456783_5546808.pdf", reports[1].fileName)
        assertEquals("RF09902208120000003143975", reports[0].debtorId)
        assertEquals("RF28902208120000005546808", reports[1].debtorId)
        // Και ο ΑΜ όντως συμπίπτει: αυτό ακριβώς εξαφάνιζε την καρτέλα ΤΕΚΑ.
        assertEquals(
            reports[0].identity.toMap()["Αριθμός Μητρώου"],
            reports[1].identity.toMap()["Αριθμός Μητρώου"],
        )
    }

    @Test
    fun `το έντυπο δείχνει υπόλοιπο και στοιχεία φορέα`() {
        val report = reports(KeaoCard.SCOPE_REGULATED).first()
        val facts = report.identity.toMap()
        assertEquals("ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ", facts["Φορέας"])
        assertEquals("Ληξιπρόθεσμο", facts["Κατηγορία"])
        assertEquals("3143975", facts["ΑΜΟ"])
        assertTrue(report.summary.any { it.first == "Υπόλοιπο" && it.second == "3.000,00" })
        assertTrue(report.footer.any { it.contains("17/09/2026") })
    }

    /**
     * Η προεπιλογή: ρυθμίσεις και συνολική οφειλή, **χωρίς** τις οφειλές εκτός
     * ρύθμισης. Επειδή όμως υπάρχουν, το έντυπο το λέει — αλλιώς ο πελάτης
     * διαβάζει «αυτά χρωστάω» και λείπουν κομμάτια.
     */
    @Test
    fun `με την προεπιλογή μένουν έξω οι οφειλές εκτός ρύθμισης`() {
        val report = reports(KeaoCard.SCOPE_REGULATED).first()
        assertFalse(report.sections.any { it.caption == "Οφειλές εκτός ρύθμισης" })
        assertTrue(
            "το έντυπο πρέπει να δηλώνει ότι κάτι μένει απ' έξω",
            report.footer.any { it.contains("εκτός ρύθμισης") },
        )
    }

    @Test
    fun `με την πλήρη επιλογή μπαίνουν και οι οφειλές εκτός ρύθμισης`() {
        val report = reports(KeaoCard.SCOPE_ALL).first()
        val section = report.sections.first { it.caption == "Οφειλές εκτός ρύθμισης" }
        val table = section.table!!
        assertEquals(2, table.rows.size)
        // Σύνολα: 400,00 + 100,50 = 500,50 κύρια · 40,25 πρόσθετα · 550,75 σύνολο.
        assertEquals(listOf("ΣΥΝΟΛΑ", "", "500,50", "50,25", "550,75"), table.totals)
        // Και τότε δεν χρειάζεται προειδοποίηση ότι λείπει κάτι.
        assertFalse(report.footer.any { it.contains("δεν περιλαμβάνονται") })
    }

    /**
     * Ο αναλυτικός πίνακας δόσεων: **μόνο οι εκκρεμείς**, και μόνο για ενεργή
     * ρύθμιση. Μια απολεσθείσα ρύθμιση με δεκάδες δόσεις θα έθαβε τη δόση που
     * πρέπει να πληρωθεί αυτόν τον μήνα.
     */
    @Test
    fun `οι δόσεις αναλύονται μόνο για την ενεργή ρύθμιση`() {
        val report = reports(KeaoCard.SCOPE_REGULATED).first()
        val captions = report.sections.map { it.caption }
        assertEquals("Ρυθμίσεις", captions.first())
        assertEquals(
            "μόνο η ενεργή ρύθμιση αναλύεται",
            1,
            captions.count { it.startsWith("Ρύθμιση ") },
        )

        val detail = report.sections.first { it.caption.startsWith("Ρύθμιση ") }
        assertTrue(detail.caption.contains("ΠΑΓΙΑ ΡΥΘΜΙΣΗ"))
        assertEquals("137612 07/02/2026", detail.facts.toMap()["Αρ. / ημ. απόφασης"])
        assertEquals("31/03/2026", detail.facts.toMap()["Τελευταία δόση"])

        val table = detail.table!!
        assertEquals("η πληρωμένη δόση δεν τυπώνεται", 2, table.rows.size)
        assertEquals(listOf("2", "28/02/2026", "250,00", "1,50", "250,00"), table.rows.first())
        assertEquals(listOf("ΣΥΝΟΛΑ", "", "1.500,00", "1,50", "1.500,00"), table.totals)
    }

    @Test
    fun `η συγκεντρωτική γραμμή δείχνει πόσες δόσεις μένουν`() {
        val overview = reports(KeaoCard.SCOPE_REGULATED).first().sections.first()
        assertEquals(2, overview.table!!.rows.size)
        assertEquals("2/3", overview.table!!.rows.first()[3])
    }

    @Test
    fun `φορέας χωρίς ρυθμίσεις δεν βγάζει κενές ενότητες`() {
        val teka = reports(KeaoCard.SCOPE_ALL)[1]
        assertTrue(teka.sections.isEmpty())
        assertEquals("RF28902208120000005546808", teka.debtorId)
    }

    // ------------------------------------------------------------ λεπτομέρειες

    /**
     * Το πραγματικό σφάλμα, κλειδωμένο: δύο φορείς με **ίδιο ΑΜΟ** δεν
     * επιτρέπεται να δώσουν το ίδιο αρχείο. Ο ΑΜΟ φαίνεται μοναδικός, αλλά το
     * ίδιο πιστεύαμε και για τον ΑΜ.
     */
    @Test
    fun `ίδιος ΑΜΟ δεν σβήνει το προηγούμενο έντυπο`() {
        val json = """{"carriers":[
            {"Amo":"7","CarrierDescr":"Α","DeptorTransactions":{"DeptorID":"RF1"}},
            {"Amo":"7","CarrierDescr":"Β","DeptorTransactions":{"DeptorID":"RF2"}}]}"""
        val reports = KeaoCard.reports(KeaoCard.parse(json), "Χ", "123456783", "", "")
        assertEquals("KEAO_KARTELA_123456783_7.pdf", reports[0].fileName)
        assertEquals("KEAO_KARTELA_123456783_7_2.pdf", reports[1].fileName)
    }

    @Test
    fun `όνομα αρχείου χωρίς ΑΜΟ και χωρίς αριθμό μητρώου`() {
        val json = """{"carriers":[{"Amo":"","CarrierDescr":"ΤΑΜΕΙΟ","CarrierAm":"",
            "DeptorTransactions":{"DeptorID":"1","Debits":{"Balance":"1,00"}}}]}"""
        val report = KeaoCard.reports(KeaoCard.parse(json), "Χ", "123456783", "", "").single()
        assertEquals("KEAO_KARTELA_123456783_1.pdf", report.fileName)
    }

    @Test
    fun `ο τίτλος του φορέα καθαρίζεται χωρίς να χαθεί η κατηγορία`() {
        val teka = KeaoCard.carrierName("Ληξιπρόθεσμο - ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΤΕΚΑ")
        assertEquals("ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΤΕΚΑ", teka.title)
        assertEquals("Ληξιπρόθεσμο", teka.category)

        // Η λίστα του ΚΕΑΟ γράφει τον φορέα δύο φορές· στο έντυπο μία.
        val efka = KeaoCard.carrierName(
            "Ληξιπρόθεσμο - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ",
        )
        assertEquals("ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ", efka.title)

        // Ό,τι δεν έχει πρόθεμα μένει ακέραιο.
        assertEquals("ΤΑΜΕΙΟ", KeaoCard.carrierName("ΤΑΜΕΙΟ").title)
        assertEquals("", KeaoCard.carrierName("ΤΑΜΕΙΟ").category)
    }

    /**
     * Τα ποσά έρχονται σε ελληνική μορφή και επιστρέφουν σε ελληνική μορφή. Ένα
     * σύνολο που θα τύπωνε «1234.5» σε έντυπο προς πελάτη είναι λάθος που
     * φαίνεται· ένα που μπερδεύει τελεία και κόμμα είναι λάθος κατά χίλια.
     */
    @Test
    fun `τα ποσά διαβάζονται και γράφονται σε ελληνική μορφή`() {
        assertEquals(1234.56, KeaoCard.amount("1.234,56"), 0.001)
        assertEquals(0.0, KeaoCard.amount(""), 0.001)
        assertEquals(0.0, KeaoCard.amount("—"), 0.001)
        assertEquals(55.76, KeaoCard.amount("55,76"), 0.001)
        assertEquals(293872.41, KeaoCard.amount("293.872,41"), 0.001)

        assertEquals("1.234,56", KeaoCard.money(1234.56))
        assertEquals("0,00", KeaoCard.money(0.0))
        assertEquals("55,76", KeaoCard.money(55.76))
        assertEquals("293.872,41", KeaoCard.money(293872.41))
        assertEquals("1.000.000,00", KeaoCard.money(1000000.0))
    }

    @Test
    fun `κενό JSON δεν σκάει`() {
        assertTrue(KeaoCard.parse("{}").isEmpty())
        assertTrue(KeaoCard.parse("""{"carriers":[]}""").isEmpty())
        assertNull(KeaoCard.parse("{}").firstOrNull())
    }
}
