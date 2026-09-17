package gr.scanmydata.taxcenter.keao

import org.json.JSONArray
import org.json.JSONObject

/**
 * Η **καρτέλα οφειλέτη ΚΕΑΟ**, από το JSON του `keao-debts` σε έντυπο ανά φορέα.
 *
 * ## Γιατί φτιάχνουμε εμείς το PDF
 *
 * Η Ηλεκτρονική Πλατφόρμα Οφειλετών δεν δίνει εκτύπωση της καρτέλας: δίνει
 * οθόνες. Το config τις διαβάζει και γυρίζει δομημένα δεδομένα — αλλά δεδομένα
 * δεν στέλνονται σε πελάτη. Αυτό που ο λογιστής θέλει να στείλει είναι ένα
 * χαρτί που λέει **πόσα χρωστάς, σε ποιον φορέα, και με ποια ταυτότητα τα
 * πληρώνεις**.
 *
 * ## Ένα έντυπο ανά φορέα, όχι ένα συνολικό
 *
 * Η **Ταυτότητα Οφειλέτη είναι διαφορετική σε κάθε φορέα** — είναι ο κωδικός
 * πληρωμής εκείνης της οφειλής. Ένα συγκεντρωτικό έντυπο με τρεις ταυτότητες
 * μέσα είναι ο ασφαλέστερος τρόπος να πληρώσει κάποιος τη λάθος: τα ποσά
 * καταλήγουν σε άλλο ταμείο και η οφειλή που έτρεχε μένει ανοιχτή.
 *
 * Ο διαχωρισμός σε [Carrier] (δεδομένα) και [Report] (τι τυπώνεται) είναι
 * σκόπιμος: το δεύτερο ελέγχεται ολόκληρο χωρίς Android, και η ζωγραφική στο
 * [KeaoPdf] δεν παίρνει καμία απόφαση.
 */
object KeaoCard {

    data class Totals(
        val debit: String = "",
        val credit: String = "",
        val balance: String = "",
        val reduction: String = "",
        val deleted: String = "",
    )

    data class Instalment(
        val no: String = "",
        val due: String = "",
        val amount: String = "",
        val paid: String = "",
        val balance: String = "",
        val increments: String = "",
    ) {
        /**
         * Απλήρωτη; Τα ποσά είναι ελληνικά διαμορφωμένες συμβολοσειρές
         * («1.234,56»), οπότε ο έλεγχος δεν είναι αριθμητικός: υπόλοιπο «0,00»
         * σημαίνει κλεισμένη. Ό,τι δεν μοιάζει με μηδενικό μετρά ως ανοιχτό —
         * μια δόση που παραλείπεται κατά λάθος είναι χειρότερη από μία που
         * δείχνεται περιττά.
         */
        val open: Boolean
            get() = balance.isNotBlank() &&
                balance.filter { it.isDigit() }.any { it != '0' }
    }

    data class Regulation(
        val info: String = "",
        val total: String = "",
        val status: String = "",
        val payType: String = "",
        val resolutionType: String = "",
        val primary: String = "",
        val additional: String = "",
        val interest: String = "",
        val instalments: List<Instalment> = emptyList(),
    ) {
        val installments: Int get() = instalments.size

        /** Οι δόσεις που μένουν — αυτές που αφορούν τον πελάτη από δω και πέρα. */
        val pending: List<Instalment> get() = instalments.filter { it.open }

        /** Η πρώτη απλήρωτη, δηλαδή αυτή που τρέχει. */
        val next: Instalment? get() = pending.firstOrNull()
        val nextDue: String get() = next?.due.orEmpty()
        val nextAmount: String get() = next?.amount.orEmpty()

        /** Πότε τελειώνει η ρύθμιση, όπως τη δείχνει και η πύλη. */
        val lastDue: String get() = instalments.lastOrNull()?.due.orEmpty()

        /** Ενεργή ρύθμιση — μόνο αυτές αναλύονται σε δόσεις στο έντυπο. */
        val active: Boolean get() = status.contains("Ενεργ", ignoreCase = true)
    }

    data class Outstanding(
        val issueDate: String = "",
        val document: String = "",
        val primary: String = "",
        val additional: String = "",
        val total: String = "",
    )

    data class Carrier(
        val amo: String = "",
        val description: String = "",
        val carrierAm: String = "",
        val companyName: String = "",
        val debtorId: String = "",
        val branch: String = "",
        val totals: Totals = Totals(),
        val regulated: List<Regulation> = emptyList(),
        val outstanding: List<Outstanding> = emptyList(),
    )

    // ------------------------------------------------------------- ανάγνωση

    /**
     * Διαβάζει το `KEAO_ofeiles_<ΑΦΜ>.json` που γράφει το config.
     *
     * Τα ονόματα πεδίων είναι αυτούσια από τη δομή `KeaoResult` του
     * hyperserver, γι' αυτό γράφονται εδώ με την αγγλική τους μορφή: μια
     * «διόρθωση» σε ελληνικά ονόματα θα έσπαγε τη σύνδεση με το config χωρίς να
     * το δείξει τίποτα — το JSON απλώς θα διαβαζόταν κενό.
     */
    fun parse(json: String): List<Carrier> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("carriers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::carrier) }
    }

    private fun carrier(o: JSONObject): Carrier {
        val trans = o.optJSONObject("DeptorTransactions")
        val debits = trans?.optJSONObject("Debits")
        val payments = o.optJSONObject("Payments")
        return Carrier(
            amo = o.optString("Amo").trim(),
            description = o.optString("CarrierDescr").trim(),
            carrierAm = o.optString("CarrierAm").trim(),
            companyName = o.optString("CompanyName").trim(),
            debtorId = trans?.optString("DeptorID").orEmpty().trim(),
            branch = trans?.optString("BranchName").orEmpty().trim(),
            totals = Totals(
                debit = debits?.optString("Debit").orEmpty().trim(),
                credit = debits?.optString("Credit").orEmpty().trim(),
                balance = debits?.optString("Balance").orEmpty().trim(),
                reduction = debits?.optString("Reduction").orEmpty().trim(),
                deleted = debits?.optString("Deleted").orEmpty().trim(),
            ),
            regulated = regulations(trans?.optJSONObject("Regulated")?.optJSONArray("RegulatedData")),
            outstanding = outstanding(payments?.optJSONArray("OutOfRegulatedDepts")),
        )
    }

    private fun regulations(arr: JSONArray?): List<Regulation> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Regulation(
                info = o.optString("ResolutionInfo").trim(),
                total = o.optString("Total").trim(),
                status = o.optString("RegulatedStatus").trim(),
                payType = o.optString("PayType").trim(),
                resolutionType = o.optString("ResolutionType").trim(),
                primary = o.optString("RegulatePrimary").trim(),
                additional = o.optString("Additional").trim(),
                interest = o.optString("Interest").trim(),
                instalments = instalments(o.optJSONArray("RegulatedInstallmentData")),
            )
        }
    }

    private fun instalments(arr: JSONArray?): List<Instalment> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Instalment(
                no = o.optString("RowAA").trim(),
                due = o.optString("ExpirationDate").trim(),
                amount = o.optString("Amount").trim(),
                paid = o.optString("Payed").trim(),
                balance = o.optString("Balance").trim(),
                increments = o.optString("Increments").trim(),
            )
        }
    }

    private fun outstanding(arr: JSONArray?): List<Outstanding> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Outstanding(
                issueDate = o.optString("IssueDate").trim(),
                document = o.optString("DocumentInfo").trim(),
                primary = o.optString("Primary").trim(),
                additional = o.optString("Additional").trim(),
                total = o.optString("Total").trim(),
            )
        }
    }

    // -------------------------------------------------------------- έντυπο

    data class Table(
        val headers: List<String>,
        /** Αναλογίες πλάτους στηλών· κανονικοποιούνται κατά τη σχεδίαση. */
        val weights: List<Float>,
        val rows: List<List<String>>,
        /** Γραμμή συνόλων, αν έχει νόημα. Τυπώνεται έντονη και χωριστά. */
        val totals: List<String> = emptyList(),
    )

    /**
     * Ένα κομμάτι του εντύπου: τίτλος, ζεύγη ετικέτα-τιμή, και προαιρετικά ένας
     * πίνακας. Τα τρία μαζί καλύπτουν όσα δείχνει η καρτέλα, χωρίς να χρειάζεται
     * ο σχεδιαστής [KeaoPdf] να ξέρει τι σημαίνει το καθένα.
     */
    data class Section(
        val caption: String,
        val facts: List<Pair<String, String>> = emptyList(),
        val table: Table? = null,
        val note: String = "",
    )

    data class Report(
        val fileName: String,
        val title: String,
        val office: String,
        val identity: List<Pair<String, String>>,
        /** Η ταυτότητα πληρωμής — μπαίνει σε πλαίσιο, μόνη της. */
        val debtorId: String,
        val summary: List<Pair<String, String>>,
        val sections: List<Section>,
        val footer: List<String>,
    )

    /** Τι περιλαμβάνει το έντυπο — η επιλογή του χρήστη στην οθόνη λήψης. */
    const val SCOPE_REGULATED = "regulated"
    const val SCOPE_ALL = "all"

    /**
     * Ένα [Report] ανά φορέα.
     *
     * @param scope [SCOPE_REGULATED] για ρυθμίσεις και συνολική οφειλή,
     *   [SCOPE_ALL] για να μπουν και οι οφειλές εκτός ρύθμισης. Η επιλογή είναι
     *   του χρήστη και αλλάζει ανά περίσταση: άλλο πράγμα στέλνεις σε πελάτη που
     *   ρωτά «πόσο είναι η δόση μου» και άλλο σε πελάτη που τα τακτοποιεί όλα.
     * @param retrievedAt πότε αντλήθηκαν τα στοιχεία, όπως θα το διαβάσει
     *   άνθρωπος. Μπαίνει στο υποσέλιδο επειδή μια καρτέλα οφειλών γερνάει μέσα
     *   σε μέρες, και ο πελάτης πρέπει να ξέρει τι κρατά στα χέρια του.
     */
    fun reports(
        carriers: List<Carrier>,
        clientName: String,
        afm: String,
        office: String,
        retrievedAt: String,
        scope: String = SCOPE_REGULATED,
    ): List<Report> {
        // Τα ονόματα κρατιούνται για να μη συγκρουστούν — βλ. τη συνάρτηση unique.
        val used = HashSet<String>()
        return carriers.mapIndexed { index, c ->
            report(c, index, used, clientName, afm, office, retrievedAt, scope)
        }
    }

    private fun report(
        c: Carrier,
        index: Int,
        used: MutableSet<String>,
        clientName: String,
        afm: String,
        office: String,
        retrievedAt: String,
        scope: String,
    ): Report {
        // **Ο ΑΜΟ πρώτος, όχι ο Αριθμός Μητρώου.**
        //
        // Επαληθεύτηκε σε πραγματικό λογαριασμό με τρεις φορείς: το ΤΕΚΑ και το
        // ΟΠΣ-ΙΚΑ είχαν **τον ίδιο** ΑΜ (9310464020) και διαφορετικό ΑΜΟ. Με τον
        // ΑΜ στο όνομα, το τρίτο έντυπο έγραφε πάνω στο δεύτερο και η καρτέλα
        // ΤΕΚΑ εξαφανιζόταν χωρίς κανένα σφάλμα πουθενά — ο λογιστής θα έβλεπε
        // δύο έντυπα εκεί που έπρεπε να δει τρία.
        val tag = listOf(c.amo, c.carrierAm, (index + 1).toString())
            .firstOrNull { it.isNotBlank() }.orEmpty()

        val sections = ArrayList<Section>()

        if (c.regulated.isNotEmpty()) {
            sections += Section(
                caption = "Ρυθμίσεις",
                table = Table(
                    headers = listOf("Ρύθμιση", "Είδος", "Κατάσταση", "Δόσεις", "Επόμενη", "Σύνολο"),
                    weights = listOf(1.6f, 2.6f, 1.2f, 0.9f, 1.5f, 1.3f),
                    rows = c.regulated.map { r ->
                        listOf(
                            r.info,
                            r.resolutionType.ifBlank { r.payType },
                            r.status,
                            if (r.installments > 0) "${r.pending.size}/${r.installments}" else "—",
                            listOf(r.nextDue, r.nextAmount).filter { it.isNotBlank() }
                                .joinToString(" · ").ifBlank { "—" },
                            r.total,
                        )
                    },
                ),
                note = if (c.regulated.size > 1) {
                    "Η στήλη «Δόσεις» δείχνει πόσες μένουν από πόσες συνολικά."
                } else {
                    ""
                },
            )
        }

        // Αναλυτικά **μόνο οι ενεργές**. Ένας φορέας μπορεί να κουβαλά δεκάδες
        // παλιές ρυθμίσεις που έχουν λήξει ή χαθεί· αν αναλύονταν όλες, το
        // έντυπο θα γινόταν εκατοντάδες γραμμές και η δόση που τρέχει θα
        // κρυβόταν μέσα τους.
        for (r in c.regulated.filter { it.active && it.instalments.isNotEmpty() }) {
            val pending = r.pending
            sections += Section(
                caption = "Ρύθμιση " + r.resolutionType.ifBlank { r.info },
                facts = buildList {
                    if (r.info.isNotBlank()) add("Αρ. / ημ. απόφασης" to r.info)
                    if (r.primary.isNotBlank()) add("Κύρια εισφορά" to r.primary)
                    if (r.additional.isNotBlank()) add("Πρόσθετα τέλη" to r.additional)
                    if (r.interest.isNotBlank()) add("Τόκος" to r.interest)
                    if (r.total.isNotBlank()) add("Σύνολο ρύθμισης" to r.total)
                    if (r.payType.isNotBlank()) add("Τρόπος" to r.payType)
                    if (r.status.isNotBlank()) add("Κατάσταση" to r.status)
                    if (r.lastDue.isNotBlank()) add("Τελευταία δόση" to r.lastDue)
                },
                table = if (pending.isEmpty()) {
                    null
                } else {
                    Table(
                        headers = listOf("Α/Α", "Ημ. λήξης", "Ποσό δόσης", "Προσαύξηση", "Υπόλοιπο"),
                        weights = listOf(0.7f, 1.4f, 1.4f, 1.4f, 1.4f),
                        rows = pending.map { i ->
                            listOf(i.no, i.due, i.amount, i.increments, i.balance)
                        },
                        totals = listOf(
                            "ΣΥΝΟΛΑ",
                            "",
                            money(sum(pending.map { it.amount })),
                            money(sum(pending.map { it.increments })),
                            money(sum(pending.map { it.balance })),
                        ),
                    )
                },
                note = if (pending.isEmpty()) "Δεν υπάρχουν εκκρεμείς δόσεις." else "",
            )
        }

        if (scope == SCOPE_ALL && c.outstanding.isNotEmpty()) {
            sections += Section(
                caption = "Οφειλές εκτός ρύθμισης",
                table = Table(
                    headers = listOf("Ημερομηνία", "Παραστατικό", "Κύρια", "Πρόσθετα", "Σύνολο"),
                    weights = listOf(1.2f, 3.4f, 1.2f, 1.2f, 1.2f),
                    rows = c.outstanding.map { o ->
                        listOf(o.issueDate, o.document, o.primary, o.additional, o.total)
                    },
                    totals = listOf(
                        "ΣΥΝΟΛΑ",
                        "",
                        money(sum(c.outstanding.map { it.primary })),
                        money(sum(c.outstanding.map { it.additional })),
                        money(sum(c.outstanding.map { it.total })),
                    ),
                ),
            )
        }

        return Report(
            fileName = unique(fileName(afm, tag), used),
            title = "Καρτέλα οφειλέτη ΚΕΑΟ",
            office = office,
            identity = buildList {
                add("Υπόχρεος" to listOf(clientName, afm).filter { it.isNotBlank() }.joinToString(" · "))
                val name = carrierName(c.description)
                if (name.title.isNotBlank()) add("Φορέας" to name.title)
                if (name.category.isNotBlank()) add("Κατηγορία" to name.category)
                if (c.amo.isNotBlank()) add("ΑΜΟ" to c.amo)
                if (c.carrierAm.isNotBlank()) add("Αριθμός Μητρώου" to c.carrierAm)
                if (c.companyName.isNotBlank()) add("Επωνυμία" to c.companyName)
                if (c.branch.isNotBlank()) add("Αρμόδιο υποκατάστημα" to c.branch)
            },
            debtorId = c.debtorId,
            summary = buildList {
                if (c.totals.debit.isNotBlank()) add("Χρέωση" to c.totals.debit)
                if (c.totals.credit.isNotBlank()) add("Πίστωση" to c.totals.credit)
                if (c.totals.reduction.isNotBlank()) add("Έκπτωση" to c.totals.reduction)
                if (c.totals.deleted.isNotBlank()) add("Διαγραφή" to c.totals.deleted)
                if (c.totals.balance.isNotBlank()) add("Υπόλοιπο" to c.totals.balance)
            },
            sections = sections,
            footer = buildList {
                add(
                    "Άντληση από την Ηλεκτρονική Πλατφόρμα Οφειλετών ΚΕΑΟ" +
                        if (retrievedAt.isNotBlank()) " στις $retrievedAt." else ".",
                )
                if (scope != SCOPE_ALL && c.outstanding.isNotEmpty()) {
                    add(
                        "Υπάρχουν και ${c.outstanding.size} οφειλές εκτός ρύθμισης, που δεν " +
                            "περιλαμβάνονται σε αυτό το έντυπο.",
                    )
                }
                add(
                    "Τα ποσά αλλάζουν με κάθε καταβολή και με τον χρόνο (προσαυξήσεις). " +
                        "Για πληρωμή χρησιμοποίησε την παραπάνω Ταυτότητα Οφειλέτη, που " +
                        "ισχύει μόνο για αυτόν τον φορέα.",
                )
                add("Ενημερωτικό έντυπο του γραφείου — δεν είναι έγγραφο του ΚΕΑΟ.")
            },
        )
    }

    // ------------------------------------------------------------------ ποσά

    /**
     * «1.234,56» σε αριθμό. Οι πύλες δίνουν ελληνική μορφή· ό,τι δεν διαβάζεται
     * μετρά ως μηδέν, γιατί ένα σύνολο που λείπει είναι λιγότερο επικίνδυνο από
     * ένα σύνολο λάθος κατά έναν παράγοντα χιλίων.
     */
    fun amount(raw: String): Double {
        val clean = raw.replace(".", "").replace("€", "").replace(" ", "").replace(",", ".").trim()
        return clean.toDoubleOrNull() ?: 0.0
    }

    private fun sum(values: List<String>): Double = values.sumOf { amount(it) }

    /**
     * Αριθμός σε «1.234,56».
     *
     * Χτίζεται στο χέρι και όχι με NumberFormat: η μορφή πρέπει να είναι ίδια
     * στη συσκευή και στα τεστ, και το locale της συσκευής δεν είναι δεδομένο.
     */
    fun money(value: Double): String {
        val cents = Math.round(value * 100)
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val whole = (abs / 100).toString()
        val frac = (abs % 100).toString().padStart(2, '0')
        val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
        return "$sign$grouped,$frac"
    }

    /** `KEAO_KARTELA_<ΑΦΜ>_<ΑΜΟ>.pdf` — ό,τι περιμένει το `DocumentNaming`. */
    fun fileName(afm: String, tag: String): String {
        val clean = tag.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
        return "KEAO_KARTELA_${afm}_${clean.ifBlank { "1" }}.pdf"
    }

    /**
     * Εγγυάται ότι δύο φορείς δεν θα γράψουν στο ίδιο αρχείο.
     *
     * Ο ΑΜΟ είναι μοναδικός σε ό,τι έχουμε δει, αλλά αυτό ακριβώς πιστεύαμε και
     * για τον Αριθμό Μητρώου μέχρι που δύο φορείς τον μοιράστηκαν. Το δίχτυ
     * κοστίζει τρεις γραμμές· η απώλεια ενός εντύπου δεν φαίνεται πουθενά.
     */
    private fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val base = name.removeSuffix(".pdf")
        var n = 2
        while (!used.add("${base}_$n.pdf")) n++
        return "${base}_$n.pdf"
    }

    data class CarrierName(val title: String, val category: String)

    /**
     * «Ληξιπρόθεσμο - ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΤΕΚΑ» -> τίτλος «ΜΙΣΘΩΤΟΙ ΤΕΚΑ - ΤΕΚΑ»,
     * κατηγορία «Ληξιπρόθεσμο».
     *
     * Η λίστα του ΚΕΑΟ βάζει μπροστά το είδος της οφειλής και συχνά επαναλαμβάνει
     * το όνομα του φορέα δύο φορές. Κρατάμε **και τα δύο** αλλά χωριστά: η
     * κατηγορία είναι πληροφορία, όχι θόρυβος, και δεν έχει λόγο να γεμίζει τον
     * τίτλο του εντύπου που θα διαβάσει ο πελάτης.
     */
    fun carrierName(raw: String): CarrierName {
        val value = raw.trim()
        val kinds = listOf("Ληξιπρόθεσμο", "Εμπρόθεσμο", "Ληξιπρόθεσμες", "Εμπρόθεσμες")
        val kind = kinds.firstOrNull { value.startsWith("$it - ", ignoreCase = true) }
        var title = if (kind != null) value.removePrefix("$kind - ").trim() else value
        // «Χ - Χ» από τη λίστα: ίδιο όνομα δύο φορές, χωρίς νόημα στο έντυπο.
        val halves = title.split(" - ")
        if (halves.size == 2 && halves[0].trim() == halves[1].trim()) title = halves[0].trim()
        return CarrierName(title, kind.orEmpty())
    }
}
