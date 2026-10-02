package gr.scanmydata.taxcenter.debts

import gr.scanmydata.taxcenter.aade.DebtSchedule
import gr.scanmydata.taxcenter.doc.Money
import gr.scanmydata.taxcenter.keao.KeaoCard
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Οι οφειλές ενός πελάτη **όπως τις βλέπει ο λογιστής στην καρτέλα** — από τα
 * JSON που γράφουν ήδη τα `aade-debts` και `keao-debts`.
 *
 * ## Γιατί χωριστό μοντέλο από τα έντυπα
 *
 * Τα PDF απαντούν στο «στείλε μου κάτι να πληρώσω». Η καρτέλα απαντά σε άλλη
 * ερώτηση, που γίνεται με τον πελάτη στο τηλέφωνο: **τι χρωστάει τώρα, πότε
 * λήγει η επόμενη δόση, με ποιον κωδικό πληρώνεται**. Γι' αυτό κάθε γραμμή
 * εδώ είναι μία πληρωτέα υποχρέωση με την ταυτότητά της, και όχι ένας πίνακας
 * της πύλης.
 *
 * ## Καμία σελίδα της πύλης δεν είναι γραμμένη εδώ
 *
 * Οι στήλες των δόσεων αναγνωρίζονται από το **περιεχόμενο** (ημερομηνία,
 * ποσό) και όχι από το όνομα: η σελίδα των οφειλών και η σελίδα των ρυθμίσεων
 * ονομάζουν αλλιώς την ίδια στήλη, και ένας πίνακας ονομάτων θα έδειχνε λάθος
 * ποσό την πρώτη φορά που η ΑΑΔΕ θα άλλαζε μια λέξη.
 *
 * Όλο το αρχείο είναι καθαρή Kotlin, ώστε να ελέγχεται με τεστ χωρίς συσκευή.
 */
object Debts {

    /**
     * Η πύλη από την οποία έρχεται μια οφειλή.
     *
     * Δεν είναι ετικέτα εμφάνισης: είναι **άλλος πιστωτής**. Οι οφειλές στην
     * ΑΑΔΕ και στο ΚΕΑΟ έχουν άλλους κωδικούς πληρωμής, άλλες ρυθμίσεις και
     * άλλη ώρα ενημέρωσης, και ο πελάτης ρωτά πάντα για το ένα ή για το άλλο.
     * Γι' αυτό η καρτέλα, το email και η ενημέρωση χωρίζονται πρώτα εδώ.
     */
    enum class Source(val label: String) {
        AADE("ΑΑΔΕ"),
        KEAO("ΚΕΑΟ"),
    }

    /**
     * @param label η ομάδα με την πύλη της, για όπου φαίνονται όλες μαζί
     * @param short η ομάδα μόνη της, για όπου η πύλη είναι ήδη γνωστή
     */
    enum class Group(val label: String, val source: Source, val short: String) {
        AADE_OPEN("ΑΑΔΕ — εκτός ρύθμισης", Source.AADE, "Εκτός ρύθμισης"),
        AADE_ARRANGED("ΑΑΔΕ — σε ρύθμιση", Source.AADE, "Σε ρύθμιση"),
        KEAO_CARRIER("ΚΕΑΟ — υπόλοιπο ανά φορέα", Source.KEAO, "Υπόλοιπο ανά φορέα"),
        KEAO_ARRANGED("ΚΕΑΟ — ενεργές ρυθμίσεις", Source.KEAO, "Ενεργές ρυθμίσεις"),
    }

    /** Μία **απλήρωτη** δόση. Το [amount] είναι ό,τι μένει να πληρωθεί γι' αυτήν. */
    data class Instalment(val no: String, val due: String, val amount: String) {
        /** `null` όταν η ημερομηνία δεν διαβάζεται — τότε δεν κρίνεται ως ληξιπρόθεσμη. */
        val dueDate: LocalDate? get() = Debts.date(due)
    }

    data class Line(
        /** Σταθερό κλειδί μέσα στο στιγμιότυπο, για την επιλογή στην οθόνη. */
        val id: String,
        val group: Group,
        /** Η ονομασία της οφειλής, όπως τη γράφει η πύλη. */
        val title: String,
        /** ΔΟΥ, έτος, φορέας — ό,τι βοηθά να ξεχωρίσει από μια όμοιά της. */
        val detail: String = "",
        /** Συνολικό υπόλοιπο, χωρίς το «€». */
        val total: String = "",
        /** Το ληξιπρόθεσμο μέρος, όπου το δίνει η πύλη. */
        val overdue: String = "",
        val codeLabel: String = "Ταυτότητα Οφειλής",
        /** Ο κωδικός πληρωμής. Κενός όταν η πύλη δεν τον έδωσε. */
        val code: String = "",
        /** Οι απλήρωτες δόσεις, με τη σειρά λήξης. */
        val unpaid: List<Instalment> = emptyList(),
        /** Πόσες δόσεις έχει συνολικά το δοσολόγιο (πληρωμένες και μη). */
        val instalments: Int = 0,
        /**
         * Τα ονόματα με τα οποία μπορεί να έχει γραφτεί το **έντυπο** αυτής της
         * οφειλής. Με αυτά το email βρίσκει ποια οφειλή είναι κάθε συνημμένο.
         *
         * Πολλά και όχι ένα: το όνομα που έγραψε το config μένει στο JSON μόνο
         * όταν κατέβηκαν PDF, ενώ μια «ενημέρωση καρτέλας» ξαναγράφει το JSON
         * χωρίς αυτό. Γι' αυτό μπαίνει και το όνομα όπως **θα** το έχτιζε το
         * config από την ίδια γραμμή.
         */
        val files: List<String> = emptyList(),
    ) {
        val source: Source get() = group.source

        /**
         * Η δόση που πρέπει να πληρωθεί **τώρα**: η παλαιότερη απλήρωτη.
         *
         * Όχι «η επόμενη χρονικά»: αν η δόση του Ιουλίου έμεινε απλήρωτη, αυτή
         * χρωστιέται πρώτη, και ένα μήνυμα που θα έλεγε «επόμενη δόση 30
         * Σεπτεμβρίου» θα άφηνε τον πελάτη να πιστεύει ότι είναι εντάξει.
         */
        val next: Instalment? get() = unpaid.firstOrNull()

        /** Οι απλήρωτες δόσεις που έχουν ήδη λήξει. */
        fun late(today: LocalDate): List<Instalment> =
            unpaid.filter { it.dueDate?.isBefore(today) == true }

        fun lateTotal(today: LocalDate): String = Money.money(Money.sum(late(today).map { it.amount }))

        /** Το ποσό που μπαίνει στο μήνυμα: της δόσης, ή όλο το υπόλοιπο αν δεν έχει δόσεις. */
        val payable: String get() = next?.amount ?: total
    }

    data class Snapshot(
        val lines: List<Line> = emptyList(),
        /** Πότε γράφτηκε το JSON της κάθε πύλης. `0` = δεν έχει ληφθεί ποτέ. */
        val aadeAt: Long = 0L,
        val keaoAt: Long = 0L,
        /**
         * Τα μητρώα ΚΕΑΟ στα οποία περιορίστηκε η τελευταία λήψη. Μη κενό
         * σημαίνει ότι η εικόνα του ΚΕΑΟ **δεν είναι πλήρης**, και η καρτέλα
         * το λέει αντί να αφήσει να εννοηθεί ότι οι άλλοι φορείς εξοφλήθηκαν.
         */
        val keaoPartial: List<String> = emptyList(),
    ) {
        val empty: Boolean get() = lines.isEmpty()

        fun inGroup(group: Group): List<Line> = lines.filter { it.group == group }

        fun inSource(source: Source): List<Line> = lines.filter { it.source == source }

        /** Πότε διαβάστηκε η πύλη. `0` = ποτέ. */
        fun at(source: Source): Long = if (source == Source.AADE) aadeAt else keaoAt

        /**
         * Τι χρωστά συνολικά σε μια πύλη.
         *
         * Στο ΚΕΑΟ μετρούν **μόνο** τα υπόλοιπα των φορέων: οι ρυθμίσεις είναι
         * μέρος τους, και το άθροισμα και των δύο θα έδειχνε τη ρυθμισμένη
         * οφειλή δύο φορές.
         */
        fun total(source: Source): String = when (source) {
            Source.AADE -> Money.money(Money.sum(inSource(Source.AADE).map { it.total }))
            Source.KEAO -> total(Group.KEAO_CARRIER)
        }

        /** Άθροισμα υπολοίπων μιας ομάδας, σε ελληνική μορφή. */
        fun total(group: Group): String = Money.money(Money.sum(inGroup(group).map { it.total }))

        /** Το ληξιπρόθεσμο της ΑΑΔΕ, όπως το δίνει η πύλη ανά οφειλή. */
        val aadeOverdue: String
            get() = Money.money(Money.sum(inGroup(Group.AADE_OPEN).map { it.overdue }))

        /**
         * Αποτύπωμα της εικόνας, για το «άλλαξε κάτι από την προηγούμενη φορά;».
         *
         * Μπαίνουν μόνο όσα αφορούν τον πελάτη — ποσά, κωδικοί, δόσεις. Η ώρα
         * λήψης μένει έξω: αλλιώς κάθε ενημέρωση θα μετρούσε ως αλλαγή.
         */
        val fingerprint: String
            get() = lines.joinToString("|") { line ->
                listOf(line.group.name, line.title, line.total, line.code, line.unpaid.size.toString(), line.next?.due.orEmpty())
                    .joinToString("~")
            }
    }

    // ------------------------------------------------------------------ ΑΑΔΕ

    /**
     * Οι οφειλές από το `AADE_debts_….json`: εκτός ρύθμισης και σε ρύθμιση.
     *
     * Η «συνυπευθυνότητα» μένει έξω: η σελίδα της δεν έχει ταυτότητες
     * πληρωμής ανά γραμμή, και μια οφειλή χωρίς τρόπο πληρωμής δεν είναι κάτι
     * που στέλνεται σε πελάτη.
     */
    fun aade(json: String): List<Line> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val sections = root.optJSONObject("sections") ?: return emptyList()
        val afm = root.optString("afm").trim()
        val out = ArrayList<Line>()

        val open = sections.optJSONObject("debts_unregulated")?.optJSONArray("debts")
        for (i in 0 until (open?.length() ?: 0)) {
            val debt = open?.optJSONObject(i) ?: continue
            val fields = fields(debt.optJSONObject("fields"))
            val schedule = schedule(debt.optJSONObject("installments"))
            val kind = pick(fields, "Είδος φόρου", "Είδος")
            // Το ποσό όπως το βάζει το config στο όνομα: σύνολο, αλλιώς ό,τι υπάρχει.
            val named = listOf("total", "overdueBalance", "nonOverdue")
                .map { text(debt, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            out += Line(
                id = "aade-open-$i",
                group = Group.AADE_OPEN,
                title = pick(fields, "Είδος φόρου", "Είδος").ifBlank { "Οφειλή" },
                detail = listOf(
                    fields["ΔΟΥ"].orEmpty(),
                    fields["Οικ. έτος"].orEmpty().let { if (it.isBlank()) "" else "έτος $it" },
                ).filter { it.isNotBlank() }.joinToString(" · "),
                total = euro(debt.optString("total")),
                overdue = euro(debt.optString("overdueBalance")),
                codeLabel = "Ταυτότητα Οφειλής",
                code = code(debt),
                unpaid = schedule.first,
                instalments = schedule.second,
                files = listOf(
                    text(debt, "toPdf"),
                    "OFEILI_" + afm + "_" + san(kind.ifBlank { "debts_unregulated" }) + "_" + san(named) + ".pdf",
                    DebtSchedule.fallbackName(afm, kind, named),
                ).filter { it.isNotBlank() }.distinct(),
            )
        }

        val arranged = sections.optJSONObject("debts_arrangement")?.optJSONArray("debts")
        for (i in 0 until (arranged?.length() ?: 0)) {
            val debt = arranged?.optJSONObject(i) ?: continue
            val fields = fields(debt.optJSONObject("fields"))
            val schedule = schedule(debt.optJSONObject("installments"))
            val to = debt.optJSONObject("to")
            val number = to?.optString("arrAA").orEmpty().trim()
            out += Line(
                id = "aade-arranged-$i",
                group = Group.AADE_ARRANGED,
                title = pick(fields, "Τύπος Ρύθμισης", "Ρύθμιση").ifBlank { "Ρύθμιση" },
                detail = listOf(
                    fields["ΔΟΥ"].orEmpty(),
                    fields["Ημ/νία Ρύθμισης"].orEmpty().let { if (it.isBlank()) "" else "από $it" },
                ).filter { it.isNotBlank() }.joinToString(" · "),
                // Το **υπόλοιπο** της ρύθμισης, όχι το αρχικά ρυθμισμένο ποσό.
                total = euro(pick(fields, "Συνολικό Υπόλοιπο", "Συνολικό Ρυθμιζόμενο Ποσό")),
                codeLabel = "Ταυτότητα Ρυθμισμένης Οφειλής",
                code = code(debt),
                unpaid = schedule.first,
                instalments = schedule.second,
                files = listOf(
                    text(debt, "toPdf"),
                    if (number.isBlank()) "" else
                        "RYTHMISI_" + afm + "_" + san(to?.optString("arnYear").orEmpty()) + "_" + san(number) + ".pdf",
                ).filter { it.isNotBlank() }.distinct(),
            )
        }
        return out
    }

    private fun code(debt: JSONObject): String = text(debt, "toCode")

    /** Το `optString` γυρίζει τη λέξη «null» για JSON null· εδώ θέλουμε κενό. */
    private fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key).trim()

    /**
     * Το `san` του `aade-debts.js`, γραμμή προς γραμμή: έτσι χτίζει το config
     * το όνομα του PDF από την κατηγορία και το ποσό της οφειλής.
     *
     * Αντίγραφο και όχι «κάτι παρόμοιο»: το αποτέλεσμα συγκρίνεται με όνομα
     * αρχείου, και μια διαφορά σε έναν χαρακτήρα σημαίνει ότι το email δεν θα
     * αναγνωρίσει το συνημμένο.
     */
    internal fun san(raw: String): String = raw.trim()
        .replace(Regex("[€\\s]+"), "_")
        .replace(Regex("[^0-9A-Za-zΑ-Ωα-ωάέήίόύώϊϋΐΰ.,_\\-]"), "")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(70)

    /**
     * Οι απλήρωτες δόσεις ενός πίνακα της πύλης, και το πλήθος όλων.
     *
     * Ημερομηνία είναι η πρώτη στήλη που μοιάζει με ημερομηνία· ποσό είναι η
     * **τελευταία** στήλη με ποσά — και στις δύο σελίδες αυτή είναι το συνολικό
     * υπόλοιπο της δόσης, μαζί με τις προσαυξήσεις της. Απλήρωτη είναι όποια
     * έχει εκεί κάτι πάνω από το μηδέν.
     */
    private fun schedule(table: JSONObject?): Pair<List<Instalment>, Int> {
        val arr = table?.optJSONArray("rows") ?: return emptyList<Instalment>() to 0
        val rows = (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let(::strings) }
        if (rows.isEmpty()) return emptyList<Instalment>() to 0
        val width = rows.maxOf { it.size }
        val columns = 0 until width
        val dateCol = columns.firstOrNull { c -> rows.any { DATE.matches(it.getOrNull(c).orEmpty()) } }
        val amountCol = columns.lastOrNull { c -> rows.any { MONEY.containsMatchIn(it.getOrNull(c).orEmpty()) } }
        if (dateCol == null || amountCol == null) return emptyList<Instalment>() to rows.size
        val unpaid = rows
            .filter { Money.amount(it.getOrNull(amountCol).orEmpty()) > 0.004 }
            .map { row ->
                Instalment(
                    no = row.getOrNull(0).orEmpty(),
                    due = row.getOrNull(dateCol).orEmpty(),
                    amount = euro(row.getOrNull(amountCol).orEmpty()),
                )
            }
        return unpaid to rows.size
    }

    // ------------------------------------------------------------------ ΚΕΑΟ

    /**
     * Οι οφειλές από το `KEAO_ofeiles_….json`.
     *
     * Δύο είδη γραμμών ανά φορέα: το **υπόλοιπο** του φορέα, και μία γραμμή για
     * κάθε **ενεργή ρύθμισή** του με τις δόσεις της. Όλες πληρώνονται με την
     * ίδια Ταυτότητα Οφειλέτη — του φορέα.
     */
    fun keao(json: String): List<Line> {
        val carriers = runCatching { KeaoCard.parse(json) }.getOrDefault(emptyList())
        // Το ΑΦΜ μπαίνει στο όνομα των εντύπων· χωρίς αυτό δεν μαντεύουμε όνομα.
        val afm = runCatching { JSONObject(json) }.getOrNull()?.optString("afm").orEmpty().trim()
        val out = ArrayList<Line>()
        carriers.forEachIndexed { index, c ->
            val name = KeaoCard.carrierName(c.description)
            val who = listOf(
                name.title,
                if (c.amo.isBlank()) "" else "ΑΜΟ ${c.amo}",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            // Ίδιος κανόνας με το `KeaoCard.reports`: ο ΑΜΟ πρώτος.
            val tag = listOf(c.amo, c.carrierAm, (index + 1).toString())
                .firstOrNull { it.isNotBlank() }.orEmpty()
            out += Line(
                id = "keao-$index",
                group = Group.KEAO_CARRIER,
                title = name.title.ifBlank { "Φορέας ΚΕΑΟ" },
                detail = listOf(
                    name.category,
                    if (c.amo.isBlank()) "" else "ΑΜΟ ${c.amo}",
                ).filter { it.isNotBlank() }.joinToString(" · "),
                total = euro(c.totals.balance),
                codeLabel = "Ταυτότητα Οφειλέτη",
                code = c.debtorId,
                files = if (afm.isBlank()) emptyList() else listOf(KeaoCard.fileName(afm, tag)),
            )
            c.regulated.filter { it.active }.forEachIndexed { n, r ->
                val pending = r.pending.map { i ->
                    // Ό,τι **μένει** από τη δόση· το ονομαστικό ποσό μόνο αν η
                    // πύλη δεν έδωσε υπόλοιπο.
                    Instalment(i.no, i.due, euro(i.balance.ifBlank { i.amount }))
                }
                out += Line(
                    id = "keao-$index-r$n",
                    group = Group.KEAO_ARRANGED,
                    title = r.resolutionType.ifBlank { "Ρύθμιση ΚΕΑΟ" },
                    detail = who,
                    total = Money.money(Money.sum(pending.map { it.amount })),
                    codeLabel = "Ταυτότητα Οφειλέτη",
                    code = c.debtorId,
                    unpaid = pending,
                    instalments = r.installments,
                    files = if (afm.isBlank()) emptyList() else listOf(
                        KeaoCard.regulationFileName(
                            afm,
                            tag,
                            r.info.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { (n + 1).toString() },
                        ),
                    ),
                )
            }
        }
        return out
    }

    /** Τα μητρώα στα οποία περιορίστηκε η λήψη ΚΕΑΟ — βλ. [Snapshot.keaoPartial]. */
    fun keaoFilter(json: String): List<String> {
        val only = runCatching { JSONObject(json) }.getOrNull()?.optJSONArray("only") ?: return emptyList()
        return strings(only).filter { it.isNotBlank() }
    }

    // -------------------------------------------------------------- βοηθοί

    private val DATE = Regex("""\d{1,2}/\d{1,2}/\d{4}""")
    private val MONEY = Regex("""-?[\d.]+,\d{2}""")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")

    fun date(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.trim(), DAY) }.getOrNull()

    /** «1.973,32 €» -> «1.973,32». Ό,τι δεν είναι ποσό μένει κενό. */
    fun euro(raw: String): String = MONEY.find(raw)?.value.orEmpty()

    private fun strings(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.optString(it).trim() }

    private fun fields(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it).trim() }
    }

    private fun pick(fields: Map<String, String>, vararg labels: String): String =
        labels.firstNotNullOfOrNull { fields[it]?.takeIf { value -> value.isNotBlank() } }.orEmpty()
}
