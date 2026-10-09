package gr.scanmydata.taxcenter.debts

import gr.scanmydata.taxcenter.mail.MailTemplateStore
import gr.scanmydata.taxcenter.mail.MailTemplateStore.DebtField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Η καρτέλα οφειλών και το μήνυμα που φεύγει από αυτήν.
 *
 * Το δείγμα ΑΑΔΕ έχει **το σχήμα που γράφει πράγματι το `aade-debts`**
 * (επαληθεύτηκε ζωντανά, 2 Οκτωβρίου 2026): `sections.debts_unregulated.debts[]`
 * με `fields`, `total`, `overdueBalance`, `toCode` και `installments`, και
 * `debts_arrangement.debts[]` με άλλα ονόματα στηλών. Τα ποσά, οι κωδικοί και
 * τα ονόματα είναι συνθετικά — καμία πραγματική οφειλή δεν μπαίνει στο repo.
 */
class DebtsTest {

    private val aade = """
    {
      "portal": "AADE", "afm": "123456783", "retrievedAt": "2026-10-02T06:00:00.000Z",
      "sections": {
        "debts_unregulated": {
          "title": "Οφειλές εκτός Ρύθμισης και Πληρωμή",
          "totals": { "emprothesmo_nonOverdue": "1.642,43", "lixiprothesmo_overdue": "1.042,05" },
          "debts": [
            {
              "overdue": true,
              "fields": {
                "ΔΟΥ": "ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ", "Είδος φόρου": "ΔΗΛ.ΦΟΡΟΥ ΕΙΣΟΔ. Ν.Π.",
                "Πηγή": "ΚΕΦΟΔΕ ΑΤΤΙΚΗΣ", "Οικ. έτος": "2026", "Ημ/νία Βεβαίωσης": "22/05/2026",
                "Συνολικό Ποσό Οφειλής": "1.973,32 €"
              },
              "nonOverdue": "1.642,43 €", "overdueBalance": "328,49 €", "total": "1.973,32 €",
              "toCode": "123456783 900000001 200000000001",
              "installments": {
                "headers": ["Α/Α δόσης", "Ημ/νία λήξης δόσης", "Υπόλοιπο Δόσης",
                            "Προσαυξήσεις, Τόκοι, Τέλη", "Συνολικό Υπόλοιπο Ποσό", "Αναστολή Είσπραξης"],
                "rows": [
                  ["1", "31/07/2026", "0,00 €", "0,00 €", "0,00 €", "ΟΧΙ"],
                  ["2", "31/08/2026", "0,00 €", "0,00 €", "0,00 €", "ΟΧΙ"],
                  ["3", "30/09/2026", "328,49 €", "2,40 €", "330,89 €", "ΟΧΙ"],
                  ["4", "30/10/2026", "328,49 €", "0,00 €", "328,49 €", "ΟΧΙ"],
                  ["5", "30/11/2026", "328,49 €", "0,00 €", "328,49 €", "ΟΧΙ"]
                ]
              }
            },
            {
              "overdue": true,
              "fields": { "ΔΟΥ": "ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ", "Είδος φόρου": "ΧΡΕΩΣΤΙΚΕΣ ΔΗΛΩΣΕΙΣ ΦΠΑ", "Οικ. έτος": "2026" },
              "nonOverdue": "0,00 €", "overdueBalance": "713,56 €", "total": "726,58 €",
              "toCode": "123456783 900000002 200000000002",
              "installments": {
                "headers": ["Α/Α δόσης", "Ημ/νία λήξης δόσης", "Υπόλοιπο Δόσης",
                            "Προσαυξήσεις, Τόκοι, Τέλη", "Συνολικό Υπόλοιπο Ποσό", "Αναστολή Είσπραξης"],
                "rows": [
                  ["1", "31/07/2026", "356,78 €", "7,81 €", "364,59 €", "ΟΧΙ"],
                  ["2", "31/08/2026", "356,78 €", "5,21 €", "361,99 €", "ΟΧΙ"]
                ]
              }
            },
            {
              "overdue": false,
              "fields": { "ΔΟΥ": "ΗΛΙΟΥΠΟΛΗΣ", "Είδος φόρου": "ΕΝΦΙΑ", "Οικ. έτος": "2026" },
              "nonOverdue": "80,00 €", "overdueBalance": "0,00 €", "total": "80,00 €",
              "toCode": null
            }
          ]
        },
        "debts_arrangement": {
          "title": "Οφειλές σε Ρύθμιση και Πληρωμή",
          "debts": [
            {
              "fields": {
                "ΔΟΥ": "ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ", "Τύπος Ρύθμισης": "ΠΑΓΙΑ Ν.4646/19 ΕΩΣ 24 ΔΟΣΕΙΣ",
                "Α/Α": "500001", "Ημ/νία Ρύθμισης": "23/07/2026",
                "Συνολικό Ρυθμιζόμενο Ποσό": "1.113,37 €", "Συνολικό Υπόλοιπο": "835,03 €"
              },
              "to": { "returnView": "displayArrangementInfo.htm", "arnDoy": "1101", "arnDept": "1", "arnYear": "2026", "arrAA": "7000001" },
              "toCode": "123456783 900000003 200000000003",
              "installments": {
                "headers": ["Α/Α δόσης", "Ημ/νία λήξης δόσης", "Υπόλοιπο Ποσό Δόσης",
                            "Ποσό Επιβάρυνσης", "Συνολικό Υπόλοιπο Δόσης"],
                "rows": [
                  ["1", "28/07/2026", "0,00 €", "0,00 €", "0,00 €"],
                  ["2", "31/08/2026", "0,00 €", "0,00 €", "0,00 €"],
                  ["3", "30/10/2026", "92,78 €", "0,00 €", "92,78 €"],
                  ["4", "30/11/2026", "92,78 €", "0,00 €", "92,78 €"]
                ]
              }
            }
          ]
        },
        "debts_coresponsible": { "title": "Οφειλές από Συνυπευθυνότητα", "rows": [["κάτι"]] },
        "payments": { "title": "Στοιχεία Πληρωμών", "rows": [["κάτι"]] }
      }
    }
    """.trimIndent()

    private val keao = """
    {
      "afm": "123456783",
      "only": ["1000001"],
      "carriers": [
        {
          "Amo": "1000001",
          "CarrierDescr": "Ληξιπρόθεσμο - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ",
          "CarrierAm": "9000000001",
          "DeptorTransactions": {
            "DeptorID": "RF00000000000000001000001",
            "Debits": { "Balance": "3.000,00" },
            "Regulated": { "RegulatedData": [
              { "ResolutionInfo": "500001 07/02/2026", "Total": "3.000,00",
                "ResolutionType": "ΠΑΓΙΑ ΡΥΘΜΙΣΗ", "RegulatedStatus": "Ενεργή",
                "RegulatedInstallmentData": [
                  { "RowAA": "1", "ExpirationDate": "31/01/2026", "Amount": "250,00", "Payed": "250,00", "Balance": "0,00" },
                  { "RowAA": "2", "ExpirationDate": "28/02/2026", "Amount": "250,00", "Payed": "100,00", "Balance": "150,00" },
                  { "RowAA": "3", "ExpirationDate": "31/12/2026", "Amount": "250,00", "Payed": "0,00", "Balance": "250,00" }
                ] },
              { "ResolutionInfo": "99001 01/01/2020", "ResolutionType": "ΠΑΛΙΑ", "RegulatedStatus": "Απολεσθείσα",
                "RegulatedInstallmentData": [
                  { "RowAA": "1", "ExpirationDate": "31/01/2020", "Amount": "800,00", "Balance": "800,00" }
                ] }
            ] }
          }
        }
      ]
    }
    """.trimIndent()

    private val today = LocalDate.of(2026, 10, 2)
    private fun lines() = Debts.aade(aade) + Debts.keao(keao)

    // ------------------------------------------------------------------ ΑΑΔΕ

    @Test
    fun `κάθε οφειλή ΑΑΔΕ γίνεται μία γραμμή με την ταυτότητά της`() {
        val all = Debts.aade(aade)
        assertEquals(4, all.size)
        val income = all[0]
        assertEquals(Debts.Group.AADE_OPEN, income.group)
        assertEquals("ΔΗΛ.ΦΟΡΟΥ ΕΙΣΟΔ. Ν.Π.", income.title)
        assertEquals("ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ · έτος 2026", income.detail)
        assertEquals("1.973,32", income.total)
        assertEquals("328,49", income.overdue)
        assertEquals("123456783 900000001 200000000001", income.code)
        assertEquals(5, income.instalments)
    }

    /**
     * Η δόση που χρωστιέται **τώρα** είναι η παλαιότερη απλήρωτη, και το ποσό
     * της είναι το συνολικό υπόλοιπο μαζί με τις προσαυξήσεις — όχι το σκέτο
     * «Υπόλοιπο Δόσης», που θα έστελνε τον πελάτη να πληρώσει λιγότερα.
     */
    @Test
    fun `επόμενη δόση είναι η παλαιότερη απλήρωτη, με τις προσαυξήσεις της`() {
        val income = Debts.aade(aade)[0]
        assertEquals(3, income.unpaid.size)
        assertEquals("30/09/2026", income.next?.due)
        assertEquals("330,89", income.next?.amount)
        assertEquals("330,89", income.payable)
        // Η 30/09 έχει περάσει στις 2/10· η 30/10 όχι.
        assertEquals(1, income.late(today).size)
    }

    @Test
    fun `οι ληξιπρόθεσμες δόσεις μετριούνται και αθροίζονται`() {
        val vat = Debts.aade(aade)[1]
        assertEquals(2, vat.late(today).size)
        assertEquals("726,58", vat.lateTotal(today))
        assertEquals("31/07/2026", vat.next?.due)
    }

    @Test
    fun `οφειλή χωρίς δοσολόγιο και χωρίς ταυτότητα δεν εφευρίσκει τίποτα`() {
        val enfia = Debts.aade(aade)[2]
        assertNull(enfia.next)
        assertEquals("", enfia.code)
        // Χωρίς δόσεις, πληρωτέο είναι ολόκληρο το υπόλοιπο.
        assertEquals("80,00", enfia.payable)
        assertTrue(enfia.late(today).isEmpty())
    }

    /**
     * Η ρύθμιση έχει **άλλα ονόματα στηλών** από την οφειλή. Οι στήλες
     * αναγνωρίζονται από το περιεχόμενο, οπότε διαβάζεται με τον ίδιο κώδικα.
     */
    @Test
    fun `η ρύθμιση διαβάζεται με τις δικές της στήλες και το υπόλοιπό της`() {
        val arranged = Debts.aade(aade)[3]
        assertEquals(Debts.Group.AADE_ARRANGED, arranged.group)
        assertEquals("ΠΑΓΙΑ Ν.4646/19 ΕΩΣ 24 ΔΟΣΕΙΣ", arranged.title)
        // Το υπόλοιπο, όχι το αρχικά ρυθμισμένο ποσό.
        assertEquals("835,03", arranged.total)
        assertEquals("Ταυτότητα Ρυθμισμένης Οφειλής", arranged.codeLabel)
        assertEquals("30/10/2026", arranged.next?.due)
        assertEquals("92,78", arranged.next?.amount)
        assertTrue("καμία δόση δεν έχει λήξει", arranged.late(today).isEmpty())
    }

    // ------------------------------------------------------------------ ΚΕΑΟ

    @Test
    fun `το ΚΕΑΟ δίνει υπόλοιπο φορέα και μία γραμμή ανά ενεργή ρύθμιση`() {
        val all = Debts.keao(keao)
        assertEquals("η απολεσθείσα ρύθμιση μένει έξω", 2, all.size)

        val carrier = all[0]
        assertEquals(Debts.Group.KEAO_CARRIER, carrier.group)
        assertEquals("ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ", carrier.title)
        assertEquals("3.000,00", carrier.total)
        assertEquals("RF00000000000000001000001", carrier.code)

        val regulation = all[1]
        assertEquals(Debts.Group.KEAO_ARRANGED, regulation.group)
        // Πληρώνεται με την ταυτότητα του φορέα της.
        assertEquals(carrier.code, regulation.code)
        assertEquals(2, regulation.unpaid.size)
        // Η μισοπληρωμένη δόση χρωστά ό,τι **μένει**, όχι το ονομαστικό ποσό.
        assertEquals("150,00", regulation.next?.amount)
        assertEquals("400,00", regulation.total)
        assertEquals(3, regulation.instalments)
    }

    @Test
    fun `η μερική λήψη ΚΕΑΟ δηλώνεται`() {
        assertEquals(listOf("1000001"), Debts.keaoFilter(keao))
        assertTrue(Debts.keaoFilter("""{"carriers":[]}""").isEmpty())
    }

    // ------------------------------------------------------- ΚΕΑΟ ανά φορέα

    /** Δεύτερος φορέας πριν από τον πρώτο, χωρίς ρύθμιση. Συνθετικά στοιχεία. */
    private fun withSecondCarrier(description: String) = keao.replace(
        "\"carriers\": [",
        "\"carriers\": [ { \"Amo\": \"1000002\", \"CarrierDescr\": \"$description\", " +
            "\"CarrierAm\": \"9000000002\", \"DeptorTransactions\": { " +
            "\"DeptorID\": \"RF00000000000000001000002\", \"Debits\": { \"Balance\": \"500,00\" } } },",
    )

    /**
     * Το ΚΕΑΟ είναι πολλοί πιστωτές κάτω από μία πύλη. Η καρτέλα τους ανοίγει
     * και τους κλείνει έναν-έναν, άρα κάθε γραμμή πρέπει να ξέρει σε ποιον
     * ανήκει — και η ρύθμιση ανήκει στον φορέα της, όχι σε «όλο το ΚΕΑΟ».
     */
    @Test
    fun `κάθε γραμμή ΚΕΑΟ ξέρει τον φορέα της`() {
        val snapshot = Debts.Snapshot(lines = Debts.keao(withSecondCarrier("Ληξιπρόθεσμο - ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΜΙΣΘΩΤΟΙ ΤΕΚΑ")))
        val carriers = snapshot.carriers
        assertEquals(listOf("ΜΙΣΘΩΤΟΙ ΤΕΚΑ", "ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ"), carriers.map { it.label })
        assertEquals(listOf("500,00", "3.000,00"), carriers.map { it.total })
        assertEquals(2, carriers.map { it.key }.toSet().size)

        val (teka, efka) = carriers
        assertEquals("μόνο το υπόλοιπό του", 1, snapshot.lines.count { it.carrier == teka.key })
        assertEquals("το υπόλοιπο και η ενεργή ρύθμισή του", 2, snapshot.lines.count { it.carrier == efka.key })
        assertTrue("καμία γραμμή ΚΕΑΟ χωρίς φορέα", snapshot.lines.none { it.carrier.isBlank() })
        // Στην ΑΑΔΕ δεν υπάρχει φορέας: ο διακόπτης δεν πρέπει να κρύψει ποτέ γραμμή της.
        assertTrue(Debts.aade(aade).all { it.carrier.isBlank() })
    }

    /**
     * Με κλειστό φορέα, το σύνολο που δείχνει η σύνοψη είναι **μόνο** των
     * ανοιχτών — και πάντα υπόλοιπα, ποτέ υπόλοιπα συν ρυθμίσεις.
     */
    @Test
    fun `το υπόλοιπο του ΚΕΑΟ βγαίνει και για μέρος των φορέων`() {
        val snapshot = Debts.Snapshot(lines = Debts.keao(withSecondCarrier("Ληξιπρόθεσμο - ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΜΙΣΘΩΤΟΙ ΤΕΚΑ")))
        val (teka, efka) = snapshot.carriers
        assertEquals("3.500,00", snapshot.total(Debts.Source.KEAO))
        assertEquals("3.500,00", snapshot.keaoTotal(setOf(teka.key, efka.key)))
        assertEquals("500,00", snapshot.keaoTotal(setOf(teka.key)))
        assertEquals("η ρύθμιση των 400 δεν προστίθεται", "3.000,00", snapshot.keaoTotal(setOf(efka.key)))
        assertEquals("0,00", snapshot.keaoTotal(emptySet()))
    }

    /** Ο ίδιος φορέας με δύο μητρώα: το όνομα μόνο του δεν λέει ποιος είναι ποιος. */
    @Test
    fun `φορείς με το ίδιο όνομα ξεχωρίζουν από το μητρώο τους`() {
        val same = "Ληξιπρόθεσμο - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ - ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ"
        val labels = Debts.Snapshot(lines = Debts.keao(withSecondCarrier(same))).carriers.map { it.label }
        assertEquals(2, labels.toSet().size)
        assertTrue(labels[0], labels[0].endsWith("ΑΜΟ 1000002"))
        assertTrue(labels[1], labels[1].endsWith("ΑΜΟ 1000001"))

        // Ένας φορέας μόνος του δεν χρειάζεται διευκρίνιση.
        assertEquals(
            listOf("ΕΝΙΑΙΟΣ ΦΟΡΕΑΣ ΚΟΙΝΩΝΙΚΗΣ ΑΣΦΑΛΙΣΗΣ"),
            Debts.Snapshot(lines = Debts.keao(keao)).carriers.map { it.label },
        )
    }

    // ------------------------------------------------------------ στιγμιότυπο

    @Test
    fun `τα σύνολα βγαίνουν ανά ομάδα`() {
        val snapshot = Debts.Snapshot(lines = lines())
        assertEquals("2.779,90", snapshot.total(Debts.Group.AADE_OPEN))
        assertEquals("835,03", snapshot.total(Debts.Group.AADE_ARRANGED))
        assertEquals("1.042,05", snapshot.aadeOverdue)
        assertEquals("3.000,00", snapshot.total(Debts.Group.KEAO_CARRIER))
    }

    /**
     * ΑΑΔΕ και ΚΕΑΟ είναι άλλος πιστωτής: χωριστό σύνολο, χωριστή όψη.
     *
     * Στο ΚΕΑΟ το σύνολο είναι **μόνο** τα υπόλοιπα των φορέων — οι ρυθμίσεις
     * είναι μέρος τους, και το άθροισμα και των δύο θα έδειχνε τη ρυθμισμένη
     * οφειλή δύο φορές.
     */
    @Test
    fun `οι οφειλές χωρίζονται ανά πύλη, με δικό τους σύνολο`() {
        val snapshot = Debts.Snapshot(lines = lines(), aadeAt = 10L, keaoAt = 20L)
        assertEquals(4, snapshot.inSource(Debts.Source.AADE).size)
        assertEquals(2, snapshot.inSource(Debts.Source.KEAO).size)
        assertEquals("3.614,93", snapshot.total(Debts.Source.AADE))
        assertEquals("3.000,00", snapshot.total(Debts.Source.KEAO))
        assertEquals(10L, snapshot.at(Debts.Source.AADE))
        assertEquals(20L, snapshot.at(Debts.Source.KEAO))
        assertTrue(Debts.Group.entries.all { it.label.startsWith(it.source.label) })
    }

    /**
     * Με αυτά τα ονόματα το email βρίσκει ποια οφειλή είναι κάθε συνημμένο.
     * Πρέπει να βγαίνουν **όπως τα γράφει το config** — και όταν το JSON δεν
     * έχει `toPdf`, δηλαδή μετά από ενημέρωση καρτέλας χωρίς έντυπα.
     */
    @Test
    fun `κάθε οφειλή ξέρει πώς λέγεται το έντυπό της`() {
        val all = lines()
        assertTrue(all[0].files.toString(), "OFEILI_123456783_ΔΗΛ.ΦΟΡΟΥ_ΕΙΣΟΔ._Ν.Π._1.973,32.pdf" in all[0].files)
        assertTrue(all[3].files.toString(), "RYTHMISI_123456783_2026_7000001.pdf" in all[3].files)
        assertEquals(listOf("KEAO_KARTELA_123456783_1000001.pdf"), all[4].files)
        assertEquals(listOf("KEAO_RYTHMISI_123456783_1000001_500001.pdf"), all[5].files)

        // Το όνομα που έγραψε το ίδιο το config προηγείται, όταν υπάρχει.
        val named = Debts.aade(aade.replace("\"toCode\": null", "\"toCode\": null, \"toPdf\": \"OFEILI_x.pdf\""))
        assertEquals("OFEILI_x.pdf", named[2].files.first())
    }

    /** Το `san` του `aade-debts.js`: κενά και «€» σε `_`, η κάθετος φεύγει. */
    @Test
    fun `το όνομα αρχείου χτίζεται όπως στο config`() {
        assertEquals("ΕΝ.Φ.Ι.Α._Ν.42232013", Debts.san("ΕΝ.Φ.Ι.Α. Ν.4223/2013"))
        assertEquals("211,10", Debts.san("211,10 €"))
        assertEquals("ΧΡΕΩΣΤΙΚΕΣ_ΔΗΛΩΣΕΙΣ_ΦΠΑ", Debts.san("  ΧΡΕΩΣΤΙΚΕΣ   ΔΗΛΩΣΕΙΣ ΦΠΑ "))
        assertEquals(70, Debts.san("Α".repeat(90)).length)
    }

    @Test
    fun `το αποτύπωμα αλλάζει μόνο όταν αλλάζει κάτι που αφορά τον πελάτη`() {
        val before = Debts.Snapshot(lines = lines(), aadeAt = 1L)
        val sameLater = Debts.Snapshot(lines = lines(), aadeAt = 999L)
        assertEquals("η ώρα λήψης δεν είναι αλλαγή", before.fingerprint, sameLater.fingerprint)

        val changed = Debts.Snapshot(lines = Debts.aade(aade.replace("1.973,32 €", "1.975,00 €")))
        assertFalse(before.fingerprint == changed.fingerprint)
    }

    @Test
    fun `άδειο ή χαλασμένο JSON δεν σκάει`() {
        assertTrue(Debts.aade("").isEmpty())
        assertTrue(Debts.aade("{").isEmpty())
        assertTrue(Debts.aade("""{"sections":{}}""").isEmpty())
        assertTrue(Debts.keao("").isEmpty())
        assertTrue(Debts.Snapshot().empty)
    }

    // ---------------------------------------------------------------- μήνυμα

    private fun message(vararg off: DebtField, lines: List<Debts.Line> = listOf(Debts.aade(aade)[0])): String {
        val template = MailTemplateStore.DEFAULT_DEBT.let { t ->
            t.copy(fields = t.fields - off.map { it.key }.toSet())
        }
        return DebtMessage.text("ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ", "123456783", lines, template, "Λογιστικό Γραφείο", today)
    }

    /**
     * Το προεπιλεγμένο μήνυμα: τι, πόσο, μέχρι πότε, με ποιον κωδικό — τα
     * τέσσερα που ζητήθηκαν, με αυτή τη σειρά.
     */
    @Test
    fun `το μήνυμα λέει τι, πόσο, μέχρι πότε και με ποιον κωδικό`() {
        assertEquals(
            listOf(
                "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ, σας ενημερώνουμε για την πληρωμή:",
                "",
                "ΔΗΛ.ΦΟΡΟΥ ΕΙΣΟΔ. Ν.Π. (ΑΑΔΕ)",
                "Ποσό δόσης: 330,89 €",
                "Έληξε: 30/09/2026",
                "Ταυτότητα Οφειλής: 123456783 900000001 200000000001",
                "",
                "Λογιστικό Γραφείο",
            ),
            message().lines(),
        )
    }

    @Test
    fun `δόση που δεν έχει λήξει γράφεται ως λήξη`() {
        val text = message(lines = listOf(Debts.aade(aade)[3]))
        assertTrue(text, text.contains("Λήξη: 30/10/2026"))
        assertTrue(text.contains("Ποσό δόσης: 92,78 €"))
        assertTrue(text.contains("Ταυτότητα Ρυθμισμένης Οφειλής: 123456783 900000003 200000000003"))
        assertFalse(text.contains("Έληξε"))
    }

    @Test
    fun `πολλές ληξιπρόθεσμες δόσεις αναφέρονται με το σύνολό τους`() {
        val text = message(lines = listOf(Debts.aade(aade)[1]))
        assertTrue(text, text.contains("Ληξιπρόθεσμες δόσεις: 2, σύνολο 726,58 €"))
    }

    @Test
    fun `οφειλή χωρίς δόσεις στέλνει το υπόλοιπο και όχι ημερομηνία`() {
        val text = message(lines = listOf(Debts.aade(aade)[2]))
        assertTrue(text, text.contains("Ποσό: 80,00 €"))
        assertFalse(text.contains("Λήξη"))
        // Χωρίς κωδικό δεν τυπώνεται κενή ετικέτα.
        assertFalse(text.contains("Ταυτότητα"))
    }

    @Test
    fun `ό,τι κλείσει στο πρότυπο δεν φεύγει`() {
        val text = message(DebtField.CODE, DebtField.OFFICE, DebtField.DUE)
        assertFalse(text.contains("Ταυτότητα"))
        assertFalse(text.contains("Λογιστικό Γραφείο"))
        assertFalse(text.contains("Έληξε"))
        assertTrue(text.contains("Ποσό δόσης: 330,89 €"))
    }

    @Test
    fun `πολλές οφειλές μπαίνουν σε ένα μήνυμα, χωρισμένες`() {
        val text = message(lines = Debts.aade(aade).take(2))
        assertEquals(2, Regex("Ποσό δόσης").findAll(text).count())
        assertTrue(text.contains("200000000001\n\nΧΡΕΩΣΤΙΚΕΣ ΔΗΛΩΣΕΙΣ ΦΠΑ (ΑΑΔΕ)"))
    }

    @Test
    fun `το ημερολόγιο γράφει ποια οφειλή στάλθηκε`() {
        assertEquals(
            listOf("ΔΗΛ.ΦΟΡΟΥ ΕΙΣΟΔ. Ν.Π. (ΑΑΔΕ) — 330,89 €"),
            DebtMessage.items(listOf(Debts.aade(aade)[0])),
        )
    }

    // ------------------------------------------------------------------- SMS

    @Test
    fun `ένα πεζό ελληνικό γράμμα γυρίζει το SMS σε τμήματα των 70`() {
        val latin = DebtMessage.sms("A".repeat(160))
        assertFalse(latin.unicode)
        assertEquals(1, latin.parts)
        assertEquals(2, DebtMessage.sms("A".repeat(161)).parts)

        val greek = DebtMessage.sms("α".repeat(70))
        assertTrue(greek.unicode)
        assertEquals(1, greek.parts)
        assertEquals(2, DebtMessage.sms("α".repeat(71)).parts)
        assertEquals(3, DebtMessage.sms("α".repeat(135)).parts)

        // Το «€» υπάρχει στο GSM αλλά μετρά για δύο.
        assertEquals(4, DebtMessage.sms("5 €").chars)
    }

    @Test
    fun `το προεπιλεγμένο μήνυμα μετριέται ως πολλά τμήματα`() {
        val cost = DebtMessage.sms(message())
        assertTrue(cost.unicode)
        assertTrue("τμήματα: ${cost.parts}", cost.parts in 3..4)
    }
}
