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

        /**
         * Οι **τρέχουσες** εισφορές του μη μισθωτού στον e-ΕΦΚΑ — η μηνιαία
         * εισφορά που δεν έχει ακόμη βεβαιωθεί στο ΚΕΑΟ. Άλλος κωδικός πληρωμής
         * (RF του μήνα), άλλη πύλη, άλλη λήξη: γι' αυτό τρίτη πύλη και όχι ΚΕΑΟ.
         */
        EFKA("ΕΦΚΑ"),
    }

    /**
     * @param label η ομάδα με την πύλη της, για όπου φαίνονται όλες μαζί
     * @param short η ομάδα μόνη της, για όπου η πύλη είναι ήδη γνωστή
     */
    enum class Group(val label: String, val source: Source, val short: String) {
        AADE_OPEN("ΑΑΔΕ — εκτός ρύθμισης", Source.AADE, "Εκτός ρύθμισης"),
        AADE_ARRANGED("ΑΑΔΕ — σε ρύθμιση", Source.AADE, "Σε ρύθμιση"),
        KEAO_CARRIER("ΚΕΑΟ — οφειλή ανά φορέα", Source.KEAO, "Οφειλή ανά φορέα"),
        KEAO_ARRANGED("ΚΕΑΟ — ενεργές ρυθμίσεις", Source.KEAO, "Ενεργές ρυθμίσεις"),

        /**
         * Ανενεργές, απολεσθείσες, εξοφλημένες. Φαίνονται μόνο όταν τις ζητήσει
         * ο λογιστής, και **δεν μετρούν** πουθενά — βλ. [Line.active].
         */
        KEAO_INACTIVE("ΚΕΑΟ — μη ενεργές ρυθμίσεις", Source.KEAO, "Ανενεργές / απολεσθείσες ρυθμίσεις"),
        EFKA_CURRENT("ΕΦΚΑ — τρέχουσα εισφορά", Source.EFKA, "Τρέχουσα εισφορά"),

        /**
         * Απλήρωτοι **προηγούμενοι** μήνες που δεν έχουν βεβαιωθεί ακόμη στο ΚΕΑΟ —
         * συνήθως οι δύο τελευταίοι. Η πύλη δεν τους δείχνει στις «Τρέχουσες
         * Οφειλές»· χωρίς αυτή την ομάδα ο πελάτης φαινόταν να χρωστά μόνο τον
         * τρέχοντα μήνα.
         */
        EFKA_OVERDUE("ΕΦΚΑ — ληξιπρόθεσμες εισφορές (πριν από το ΚΕΑΟ)", Source.EFKA, "Ληξιπρόθεσμες εισφορές — δεν πέρασαν ακόμη στο ΚΕΑΟ"),
    }

    /**
     * Ένα κομμάτι του συνόλου μιας γραμμής: «πρόσθετα τέλη 40,00».
     *
     * @param included `true` όταν το ποσό είναι **ήδη μέσα** σε άλλο κομμάτι
     *   ή στο σύνολο και γράφεται μόνο για να φαίνεται — π.χ. οι τόκοι μιας
     *   ρύθμισης, που είναι μέσα στις δόσεις. Δεν αθροίζεται ξανά.
     */
    data class Part(val label: String, val amount: String, val included: Boolean = false)

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
        /**
         * Ο φορέας του ΚΕΑΟ στον οποίο ανήκει η γραμμή — το υπόλοιπό του, ή
         * μια ρύθμισή του. Κενό στην ΑΑΔΕ. Βλ. [Carrier].
         */
        val carrier: String = "",
        /** Από τι αποτελείται το [total] — πρόσθετα τέλη, προσαυξήσεις, τόκοι. */
        val parts: List<Part> = emptyList(),
        /** Η κατάσταση όπως τη γράφει η πύλη, για ρυθμίσεις που δεν είναι ενεργές. */
        val status: String = "",
    ) {
        val source: Source get() = group.source

        /**
         * Μετρά στα σύνολα, στα μηνύματα και στο «άλλαξε κάτι;». Μόνο οι μη
         * ενεργές ρυθμίσεις του ΚΕΑΟ είναι `false`: είναι ιστορικό, και η οφειλή
         * τους έχει ήδη γυρίσει στις εκτός ρύθμισης του φορέα.
         */
        val active: Boolean get() = group != Group.KEAO_INACTIVE

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

    /**
     * Ένας φορέας του ΚΕΑΟ, όπως τον διαλέγει ο λογιστής στην καρτέλα.
     *
     * Το ΚΕΑΟ δεν είναι ένας πιστωτής αλλά πολλοί κάτω από μία πύλη — ΕΦΚΑ,
     * πρώην ΙΚΑ, ΤΕΚΑ — ο καθένας με δική του Ταυτότητα Οφειλέτη και δικές του
     * ρυθμίσεις. Ο πελάτης που ρωτά «τι χρωστάω στο ΙΚΑ» δεν θέλει να ακούσει
     * και τα υπόλοιπα.
     *
     * @param key σταθερό μέσα στο στιγμιότυπο· **όχι** ο ΑΜΟ, που δεν είναι
     *   κάτι που χρειάζεται να κυκλοφορεί στην οθόνη ως αναγνωριστικό
     * @param label το όνομα του φορέα· όταν δύο έχουν το ίδιο, μαζί με ό,τι τους ξεχωρίζει
     * @param total το υπόλοιπο του φορέα, χωρίς το «€»
     */
    data class Carrier(val key: String, val label: String, val total: String)

    data class Snapshot(
        val lines: List<Line> = emptyList(),
        /** Πότε γράφτηκε το JSON της κάθε πύλης. `0` = δεν έχει ληφθεί ποτέ. */
        val aadeAt: Long = 0L,
        val keaoAt: Long = 0L,
        val efkaAt: Long = 0L,
        /**
         * Τα μητρώα ΚΕΑΟ στα οποία περιορίστηκε η τελευταία λήψη. Μη κενό
         * σημαίνει ότι η εικόνα του ΚΕΑΟ **δεν είναι πλήρης**, και η καρτέλα
         * το λέει αντί να αφήσει να εννοηθεί ότι οι άλλοι φορείς εξοφλήθηκαν.
         */
        val keaoPartial: List<String> = emptyList(),
    ) {
        val empty: Boolean get() = lines.isEmpty()

        fun inGroup(group: Group): List<Line> = lines.filter { it.group == group }

        /** Οι **ενεργές** γραμμές μιας πύλης — αυτές που μετρούν. */
        fun inSource(source: Source): List<Line> = lines.filter { it.source == source && it.active }

        /** Πότε διαβάστηκε η πύλη. `0` = ποτέ. */
        fun at(source: Source): Long = when (source) {
            Source.AADE -> aadeAt
            Source.KEAO -> keaoAt
            Source.EFKA -> efkaAt
        }

        /**
         * Τι χρωστά συνολικά σε μια πύλη.
         *
         * Στο ΚΕΑΟ μετρούν **μόνο** οι γραμμές των φορέων: οι ρυθμίσεις είναι
         * μέρος τους, και το άθροισμα και των δύο θα έδειχνε τη ρυθμισμένη
         * οφειλή δύο φορές. Η γραμμή του φορέα έχει ήδη πρόσθετα τέλη, τόκους
         * και προσαυξήσεις — βλ. [KeaoCard.Owed].
         */
        fun total(source: Source): String = when (source) {
            Source.AADE -> Money.money(Money.sum(inSource(Source.AADE).map { it.total }))
            Source.KEAO -> total(Group.KEAO_CARRIER)
            Source.EFKA -> Money.money(Money.sum(inSource(Source.EFKA).map { it.total }))
        }

        /** Άθροισμα υπολοίπων μιας ομάδας, σε ελληνική μορφή. */
        fun total(group: Group): String = Money.money(Money.sum(inGroup(group).map { it.total }))

        /** Οι φορείς του ΚΕΑΟ, με τη σειρά της πύλης. */
        val carriers: List<Carrier>
            get() {
                val own = inGroup(Group.KEAO_CARRIER).filter { it.carrier.isNotBlank() }
                val repeated = own.groupingBy { it.title }.eachCount().filterValues { it > 1 }.keys
                return own.map { line ->
                    Carrier(
                        key = line.carrier,
                        // Ο ίδιος φορέας εμφανίζεται δύο φορές όταν έχει δύο
                        // μητρώα ή δύο κατηγορίες· τότε το όνομα μόνο του δεν αρκεί.
                        label = if (line.title in repeated && line.detail.isNotBlank()) {
                            line.title + " · " + line.detail
                        } else {
                            line.title
                        },
                        total = line.total,
                    )
                }
            }

        /**
         * Το υπόλοιπο του ΚΕΑΟ **μόνο** για τους φορείς [only] — ίδιος κανόνας
         * με το [total]: μετρούν τα υπόλοιπα, όχι οι ρυθμίσεις.
         */
        fun keaoTotal(only: Set<String>): String =
            Money.money(Money.sum(inGroup(Group.KEAO_CARRIER).filter { it.carrier in only }.map { it.total }))

        /**
         * Το άθροισμα ενός κομματιού ([Part.label]) στους φορείς [only] του ΚΕΑΟ
         * — π.χ. πόσα από το σύνολο είναι πρόσθετα τέλη.
         */
        fun keaoPart(label: String, only: Set<String>): Double = inGroup(Group.KEAO_CARRIER)
            .filter { it.carrier in only }
            .sumOf { line -> line.parts.filter { it.label == label }.sumOf { Money.amount(it.amount) } }

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
            get() = lines.filter { it.active }.joinToString("|") { line ->
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
     * Τρία είδη γραμμών ανά φορέα: η **οφειλή** του φορέα (με πρόσθετα τέλη,
     * τόκους και προσαυξήσεις — [KeaoCard.Owed]), μία γραμμή για κάθε **ενεργή
     * ρύθμισή** του με τις δόσεις της, και οι **μη ενεργές** ρυθμίσεις ως
     * ιστορικό. Όσες πληρώνονται, πληρώνονται με την ίδια Ταυτότητα Οφειλέτη —
     * του φορέα.
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
            val owed = c.owed
            out += Line(
                id = "keao-$index",
                group = Group.KEAO_CARRIER,
                title = name.title.ifBlank { "Φορέας ΚΕΑΟ" },
                detail = listOf(
                    name.category,
                    if (c.amo.isBlank()) "" else "ΑΜΟ ${c.amo}",
                ).filter { it.isNotBlank() }.joinToString(" · "),
                total = Money.money(owed.total),
                codeLabel = "Ταυτότητα Οφειλέτη",
                code = c.debtorId,
                files = if (afm.isBlank()) emptyList() else listOf(KeaoCard.fileName(afm, tag)),
                carrier = "keao-$index",
                parts = carrierParts(owed),
            )
            c.regulated.filter { it.active }.forEachIndexed { n, r ->
                val pending = r.pending.map { i ->
                    // Ό,τι **μένει** από τη δόση, μαζί με τις προσαυξήσεις της:
                    // αυτό πληρώνει ο πελάτης. Το ονομαστικό ποσό μόνο αν η πύλη
                    // δεν έδωσε υπόλοιπο.
                    val left = Money.amount(i.balance.ifBlank { i.amount }) + Money.amount(i.increments)
                    Instalment(i.no, i.due, Money.money(left))
                }
                out += Line(
                    id = "keao-$index-r$n",
                    group = Group.KEAO_ARRANGED,
                    title = r.resolutionType.ifBlank { "Ρύθμιση ΚΕΑΟ" },
                    detail = who,
                    total = Money.money(r.owed),
                    codeLabel = "Ταυτότητα Οφειλέτη",
                    code = c.debtorId,
                    unpaid = pending,
                    instalments = r.installments,
                    parts = regulationParts(r),
                    files = if (afm.isBlank()) emptyList() else listOf(
                        KeaoCard.regulationFileName(
                            afm,
                            tag,
                            r.info.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { (n + 1).toString() },
                        ),
                    ),
                    carrier = "keao-$index",
                )
            }
            // Οι υπόλοιπες, ως ιστορικό: χωρίς δόσεις, χωρίς κωδικό, χωρίς έντυπο.
            c.regulated.filter { !it.active }.forEachIndexed { n, r ->
                out += Line(
                    id = "keao-$index-x$n",
                    group = Group.KEAO_INACTIVE,
                    title = r.resolutionType.ifBlank { "Ρύθμιση ΚΕΑΟ" },
                    detail = listOf(
                        if (r.info.isBlank()) "" else "απόφαση ${r.info}",
                        if (r.installments > 0) "${r.installments} δόσεις" else "",
                        who,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    total = euro(r.total),
                    codeLabel = "Ταυτότητα Οφειλέτη",
                    carrier = "keao-$index",
                    parts = regulationTerms(r),
                    status = r.status.ifBlank { "χωρίς κατάσταση" },
                )
            }
        }
        return out
    }

    /** Οι ετικέτες των κομματιών — κοινές, ώστε η σύνοψη να τα αθροίζει. */
    const val PART_OUTSIDE = "Εκτός ρύθμισης"
    const val PART_ADDITIONAL = "πρόσθετα τέλη"
    const val PART_REGULATED = "Σε ρύθμιση, με τους τόκους"
    const val PART_INCREMENTS = "προσαυξήσεις"
    const val PART_INTEREST = "τόκοι ρύθμισης"

    /**
     * Φορέας: εκτός ρύθμισης και σε ρύθμιση, που αθροίζονται στο σύνολο, και
     * κάτω από το καθένα τα πρόσθετα τέλη και οι προσαυξήσεις του.
     *
     * Οι τόκοι **δεν** γράφονται εδώ ως ποσό: η πύλη δίνει τους τόκους όλης της
     * ρύθμισης, και όχι πόσοι μένουν στις απλήρωτες δόσεις. Μετρούν πάντως —
     * είναι μέσα στις δόσεις — και το λέει η ετικέτα [PART_REGULATED].
     */
    private fun carrierParts(owed: KeaoCard.Owed): List<Part> = buildList {
        if (owed.fallback) return@buildList
        if (owed.outside > CENT) add(Part(PART_OUTSIDE, Money.money(owed.outside)))
        if (owed.outsideAdditional > CENT) add(Part(PART_ADDITIONAL, Money.money(owed.outsideAdditional), included = true))
        if (owed.regulated > CENT) add(Part(PART_REGULATED, Money.money(owed.regulated)))
        if (owed.increments > CENT) add(Part(PART_INCREMENTS, Money.money(owed.increments), included = true))
    }

    /** Ενεργή ρύθμιση: τι μένει από τις δόσεις, οι προσαυξήσεις, και οι τόκοι της. */
    private fun regulationParts(r: KeaoCard.Regulation): List<Part> = buildList {
        if (r.instalments.isNotEmpty()) add(Part("υπόλοιπο δόσεων", Money.money(r.remaining)))
        // Κάτω από τις δόσεις, που τους περιέχουν — όλης της ρύθμισης, όχι όσοι μένουν.
        if (Money.amount(r.interest) > CENT) {
            add(Part("$PART_INTEREST (σε όλες τις δόσεις)", euro(r.interest), included = true))
        }
        if (r.increments > CENT) add(Part(PART_INCREMENTS, Money.money(r.increments)))
    }

    /** Από τι αποτελείται η ρύθμιση, όπως τη γράφει η πύλη — μέσα στο σύνολό της. */
    private fun regulationTerms(r: KeaoCard.Regulation): List<Part> = buildList {
        if (Money.amount(r.primary) > CENT) add(Part("κύρια", euro(r.primary), included = true))
        if (Money.amount(r.additional) > CENT) add(Part(PART_ADDITIONAL, euro(r.additional), included = true))
        if (Money.amount(r.interest) > CENT) add(Part(PART_INTEREST, euro(r.interest), included = true))
    }

    private const val CENT = 0.004

    // ------------------------------------------------------------------ ΕΦΚΑ

    /**
     * Οι τρέχουσες εισφορές από το `EFKA_notices_….json` (`efka-notices`,
     * ενότητα `CURRENT` = το tab «Τρέχουσες Οφειλές» της πύλης).
     *
     * Μία γραμμή ανά οφειλή: είδος, μήνας αναφοράς, πληρωτέο ποσό, εμπρόθεσμη
     * πληρωμή και **κωδικός πληρωμής (RF)**. Οι στήλες βρίσκονται από την
     * επικεφαλίδα, με τη σειρά της πύλης ως εφεδρεία (ζωντανά, Οκτ. 2026:
     * «Είδος Οφειλής, Ημ. Αναφοράς, Πληρωτέο Ποσό, Εμπρόθεσμη Πληρωμή Μέχρι,
     * Κωδικός Πληρωμής»).
     *
     * Η εμπρόθεσμη πληρωμή μπαίνει ως μοναδική «απλήρωτη δόση», ώστε το μήνυμα
     * να γράφει λήξη — και «έληξε» όταν πέρασε. Δεν είναι δοσολόγιο: το
     * [Line.instalments] μένει 0.
     */
    fun efka(json: String): List<Line> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val afm = root.optString("afm").trim()
        val sections = root.optJSONObject("sections") ?: return emptyList()
        val out = ArrayList<Line>()

        // ---- ο τρέχων μήνας, με τον κωδικό πληρωμής του
        val current = sections.optJSONObject("CURRENT")
        if (current != null) {
            val t = Table(current)
            val kindCol = t.col("Είδος", 0)
            val monthCol = t.col("Αναφοράς", 1)
            val amountCol = t.col("Πληρωτέο", 2)
            val dueCol = t.col("Εμπρόθεσμη", 3)
            val codeCol = t.col("Κωδικός", 4)
            t.rows.forEachIndexed { i, row ->
                val amount = euro(row.getOrNull(amountCol).orEmpty())
                val code = row.getOrNull(codeCol).orEmpty().replace(" ", "")
                    .takeIf { it.startsWith("RF", ignoreCase = true) }.orEmpty()
                if (amount.isBlank() && code.isBlank()) return@forEachIndexed
                val month = row.getOrNull(monthCol).orEmpty()
                val due = DATE.find(row.getOrNull(dueCol).orEmpty())?.value.orEmpty()
                val key = monthKey(month)
                val kind = row.getOrNull(kindCol).orEmpty().ifBlank { "Εισφορά e-ΕΦΚΑ" }
                out += Line(
                    id = "efka-$i",
                    group = Group.EFKA_CURRENT,
                    // Ο μήνας στον τίτλο: το μήνυμα SMS/Viber γράφει μόνο τον τίτλο.
                    title = if (month.isBlank()) kind else "$kind $month",
                    detail = if (month.isBlank()) "" else "μήνας $month",
                    total = amount,
                    codeLabel = "Κωδικός πληρωμής",
                    code = code,
                    unpaid = if (due.isBlank()) emptyList() else listOf(Instalment("", due, amount)),
                    files = if (afm.isBlank() || key.isBlank()) emptyList() else listOf("EFKA_EFKA_${afm}_$key.pdf"),
                )
            }
        }

        // ---- οι απλήρωτοι προηγούμενοι μήνες, όσο είναι ακόμη στον ΕΦΚΑ
        val listed = out.mapNotNull { line -> monthKey(line.detail).takeIf { it.isNotBlank() } }.toSet()
        val monthly = sections.optJSONObject("MONTHLY")?.let(::Table)
        val notices = HashMap<String, Pair<String, String>>()
        if (monthly != null) {
            val mCol = monthly.col("Μήνας", 0)
            val aCol = monthly.col("Ποσό", 1)
            val dCol = monthly.col("Εμπρόθεσμης", 2)
            for (row in monthly.rows) {
                val key = monthKey(row.getOrNull(mCol).orEmpty())
                if (key.isBlank() || key in notices) continue
                notices[key] = euro(row.getOrNull(aCol).orEmpty()) to DATE.find(row.getOrNull(dCol).orEmpty())?.value.orEmpty()
            }
        }
        val contrib = sections.optJSONObject("CONTRIB")?.let(::Table)
        for ((key, amount) in owedMonths(sections)) {
            if (key in listed) continue
            val notice = notices[key]
            val total = notice?.first.orEmpty().ifBlank { amount }
            val due = notice?.second.orEmpty()
            val label = key.substring(5).trimStart('0') + "/" + key.substring(0, 4)
            out += Line(
                id = "efka-owed-$key",
                group = Group.EFKA_OVERDUE,
                title = "Μηνιαία Εισφορά e-ΕΦΚΑ $label",
                detail = "μήνας $label · ο κωδικός πληρωμής είναι στο ειδοποιητήριο του μήνα",
                total = total,
                codeLabel = "Κωδικός πληρωμής",
                unpaid = if (due.isBlank()) emptyList() else listOf(Instalment("", due, total)),
                files = if (afm.isBlank()) emptyList() else listOf("EFKA_EFKA_${afm}_$key.pdf"),
            )
        }
        if (contrib == null && out.isEmpty()) return emptyList()
        return out
    }

    /**
     * Οι απλήρωτοι μήνες της ενότητας «Ασφαλιστικές Εισφορές» (`CONTRIB`,
     * «Εξόφληση» = Όχι) που **δεν** έχουν βεβαιωθεί ακόμη στο ΚΕΑΟ
     * (`KEAO_CERTIFIED`): νεότεροι από τον τελευταίο βεβαιωμένο μήνα. Χωρίς
     * πίνακα ΚΕΑΟ, μόνο όσοι είναι μέσα στους τρεις τελευταίους μήνες της λίστας
     * — μετά από δύο περίπου μήνες περνούν στο ΚΕΑΟ, και εκεί τους δείχνει η
     * καρτέλα ΚΕΑΟ.
     *
     * Ίδιος κανόνας με το `owedMonths` του `efka-notices.js`.
     *
     * @return `"yyyy-MM" -> μηνιαία εισφορά`, νεότερος πρώτα
     */
    internal fun owedMonths(sections: JSONObject): List<Pair<String, String>> {
        val contrib = sections.optJSONObject("CONTRIB")?.let(::Table) ?: return emptyList()
        val paidCol = contrib.col("Εξόφληση", -1).takeIf { it >= 0 } ?: return emptyList()
        val amountCol = contrib.col("Εισφορά", 3)
        val keao = sections.optJSONObject("KEAO_CERTIFIED")?.let(::Table)
        val certified = keao?.let { k ->
            val refCol = k.col("Αναφοράς", 2)
            k.rows.map { monthKey(it.getOrNull(refCol).orEmpty()) }.filter { it.isNotBlank() }.toSet()
        }.orEmpty()
        val latest = certified.maxOrNull().orEmpty()
        val unpaid = contrib.rows
            .filter { fold(it.getOrNull(paidCol).orEmpty()).startsWith("οχι") }
            .map { monthKey(it.getOrNull(0).orEmpty()) to euro(it.getOrNull(amountCol).orEmpty()) }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
            .sortedByDescending { it.first }
        return if (keao != null) {
            unpaid.filter { it.first !in certified && (latest.isBlank() || it.first > latest) }
        } else {
            val newest = contrib.rows.map { monthKey(it.getOrNull(0).orEmpty()) }.filter { it.isNotBlank() }.maxOrNull()
                ?: return emptyList()
            val floor = java.time.YearMonth.parse(newest).minusMonths(2).toString()
            unpaid.filter { it.first >= floor }
        }
    }

    /** Ένας πίνακας της πύλης: επικεφαλίδες και γραμμές κειμένου. */
    private class Table(o: JSONObject) {
        val headers: List<String> = o.optJSONArray("headers")?.let(::strings).orEmpty()
        val rows: List<List<String>> = o.optJSONArray("rows")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let(::strings) }
        }.orEmpty()

        /** Η πρώτη στήλη που περιέχει τη λέξη — αλλιώς η θέση της πύλης. */
        fun col(word: String, fallback: Int): Int =
            headers.indexOfFirst { it.contains(word, ignoreCase = true) }.takeIf { it >= 0 } ?: fallback
    }

    /** «01/09/2026», «9/2026» -> «2026-09». */
    internal fun monthKey(raw: String): String {
        Regex("""(\d{2})/(\d{2})/(\d{4})""").find(raw)?.let { m -> return m.groupValues[3] + "-" + m.groupValues[2] }
        Regex("""(\d{1,2})/(\d{4})""").find(raw)?.let { m -> return m.groupValues[2] + "-" + m.groupValues[1].padStart(2, '0') }
        return ""
    }

    private fun fold(raw: String): String = java.text.Normalizer
        .normalize(raw.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

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
