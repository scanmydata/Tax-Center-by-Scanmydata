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

    data class Regulation(
        val info: String = "",
        val total: String = "",
        val status: String = "",
        val payType: String = "",
        val installments: Int = 0,
        /** Η πρώτη απλήρωτη δόση — αυτή που τρέχει. Κενό όταν δεν υπάρχει. */
        val nextDue: String = "",
        val nextAmount: String = "",
    )

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
            val instalments = o.optJSONArray("RegulatedInstallmentData")
            val next = nextInstalment(instalments)
            Regulation(
                info = o.optString("ResolutionInfo").trim(),
                total = o.optString("Total").trim(),
                status = o.optString("RegulatedStatus").trim(),
                payType = o.optString("PayType").trim(),
                installments = instalments?.length() ?: 0,
                nextDue = next?.optString("ExpirationDate").orEmpty().trim(),
                nextAmount = next?.optString("Amount").orEmpty().trim(),
            )
        }
    }

    /**
     * Η πρώτη δόση με **υπόλοιπο**, δηλαδή η επόμενη που πρέπει να πληρωθεί.
     *
     * Τα ποσά έρχονται ως ελληνικά διαμορφωμένες συμβολοσειρές («1.234,56»),
     * οπότε ο έλεγχος δεν είναι αριθμητικός: υπόλοιπο «0,00» σημαίνει
     * πληρωμένη. Ό,τι δεν μοιάζει με μηδενικό θεωρείται ανοιχτό — μια δόση που
     * παραλείπεται κατά λάθος είναι χειρότερη από μία που δείχνεται περιττά.
     */
    private fun nextInstalment(arr: JSONArray?): JSONObject? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val balance = row.optString("Balance").trim()
            if (balance.isBlank()) continue
            if (balance.replace("0", "").replace(",", "").replace(".", "").replace("€", "").isBlank()) continue
            return row
        }
        return null
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
        val caption: String,
        val headers: List<String>,
        /** Αναλογίες πλάτους στηλών· αθροίζουν σε ό,τι να 'ναι, κανονικοποιούνται. */
        val weights: List<Float>,
        val rows: List<List<String>>,
    )

    data class Report(
        val fileName: String,
        val title: String,
        val office: String,
        val identity: List<Pair<String, String>>,
        /** Η ταυτότητα πληρωμής — μπαίνει σε πλαίσιο, μόνη της. */
        val debtorId: String,
        val summary: List<Pair<String, String>>,
        val tables: List<Table>,
        val footer: List<String>,
    )

    /**
     * Ένα [Report] ανά φορέα.
     *
     * @param retrievedAt πότε αντλήθηκαν τα στοιχεία, όπως θα το διαβάσει
     *   άνθρωπος. Μπαίνει στο υποσέλιδο επειδή μια καρτέλα οφειλών γερνάει
     *   μέσα σε μέρες, και ο πελάτης πρέπει να ξέρει τι κρατά στα χέρια του.
     */
    fun reports(
        carriers: List<Carrier>,
        clientName: String,
        afm: String,
        office: String,
        retrievedAt: String,
    ): List<Report> = carriers.mapIndexed { index, c ->
        val tag = listOf(c.carrierAm, c.amo, (index + 1).toString())
            .firstOrNull { it.isNotBlank() }.orEmpty()

        val tables = ArrayList<Table>()
        if (c.regulated.isNotEmpty()) {
            tables += Table(
                caption = "Ρυθμίσεις",
                headers = listOf("Ρύθμιση", "Κατάσταση", "Τρόπος", "Δόσεις", "Επόμενη", "Σύνολο"),
                weights = listOf(3f, 1.6f, 1.4f, 0.8f, 1.4f, 1.4f),
                rows = c.regulated.map { r ->
                    listOf(
                        r.info,
                        r.status,
                        r.payType,
                        if (r.installments > 0) r.installments.toString() else "—",
                        listOf(r.nextDue, r.nextAmount).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { "—" },
                        r.total,
                    )
                },
            )
        }
        if (c.outstanding.isNotEmpty()) {
            tables += Table(
                caption = "Οφειλές εκτός ρύθμισης",
                headers = listOf("Ημερομηνία", "Παραστατικό", "Κύρια", "Πρόσθετα", "Σύνολο"),
                weights = listOf(1.2f, 3.4f, 1.2f, 1.2f, 1.2f),
                rows = c.outstanding.map { o ->
                    listOf(o.issueDate, o.document, o.primary, o.additional, o.total)
                },
            )
        }

        Report(
            fileName = fileName(afm, tag),
            title = "Καρτέλα οφειλέτη ΚΕΑΟ",
            office = office,
            identity = buildList {
                add("Υπόχρεος" to listOf(clientName, afm).filter { it.isNotBlank() }.joinToString(" · "))
                if (c.description.isNotBlank()) add("Φορέας" to c.description)
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
            tables = tables,
            footer = buildList {
                add("Άντληση από την Ηλεκτρονική Πλατφόρμα Οφειλετών ΚΕΑΟ" +
                    if (retrievedAt.isNotBlank()) " στις $retrievedAt." else ".")
                add(
                    "Τα ποσά αλλάζουν με κάθε καταβολή και με τον χρόνο (προσαυξήσεις). " +
                        "Για πληρωμή χρησιμοποίησε την παραπάνω Ταυτότητα Οφειλέτη, που " +
                        "ισχύει μόνο για αυτόν τον φορέα.",
                )
            },
        )
    }

    /** `KEAO_KARTELA_<ΑΦΜ>_<ΑΜ φορέα>.pdf` — ό,τι περιμένει το [DocumentNaming]. */
    fun fileName(afm: String, tag: String): String {
        val clean = tag.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
        return "KEAO_KARTELA_${afm}_${clean.ifBlank { "1" }}.pdf"
    }
}
