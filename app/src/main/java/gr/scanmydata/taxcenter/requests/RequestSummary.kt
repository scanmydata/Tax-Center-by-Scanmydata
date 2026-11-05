package gr.scanmydata.taxcenter.requests

import java.text.Normalizer

/**
 * Τι λέει **με μια ματιά** η οθόνη ενός αιτήματος.
 *
 * Η πύλη δίνει όλα τα στοιχεία σωστά, αλλά ισότιμα: «Απαντημένο» σε ένα μικρό
 * πλαίσιο, η ανάθεση σε τρία πεδία με τη διεύθυνση της υπηρεσίας μέσα στο
 * όνομά της, και τα συνημμένα ανάμεσα στα κείμενα. Ο λογιστής ανοίγει το αίτημα
 * για να μάθει δύο πράγματα — **σε τι κατάσταση είναι** και **ποιος το έχει** —
 * και αυτά πρέπει να διαβάζονται πρώτα.
 *
 * Καθαρή Kotlin, χωρίς Android: ελέγχεται με τεστ.
 */
object RequestSummary {

    /** Το χρώμα της κατάστασης — το αποφασίζει εδώ, το εφαρμόζει η οθόνη. */
    enum class Tone {
        /** Τελείωσε θετικά: απάντησε η ΑΑΔΕ. */
        DONE,

        /** Περιμένει **τον πελάτη**: χωρίς ενέργεια δεν προχωρά. */
        ACTION,

        /** Το έχει η ΑΑΔΕ και δουλεύεται. */
        WORKING,

        /** Απορρίφθηκε. */
        BAD,

        /** Έκλεισε χωρίς αποτέλεσμα (ανάκληση, αρχειοθέτηση). */
        CLOSED,
    }

    data class Headline(val tone: Tone, val title: String, val subtitle: String = "")

    /**
     * Η κεφαλίδα της κατάστασης: μία πρόταση που λέει τι συμβαίνει, και μία
     * δεύτερη με το πότε ή το από ποιον.
     *
     * @param replies πόσα έγγραφα συνόδευσαν την απάντηση
     */
    fun headline(request: AadeRequests.Request, detail: RequestDetail.Detail?, replies: Int = 0): Headline {
        val code = request.statusCode
        val updated = AadeRequests.date(request.updated)
        val handler = service(request.handlingService).name
        val ask = detail?.pendingAsk
        return when {
            request.rejected || code == AadeRequests.STATUS_REJECTED ->
                Headline(Tone.BAD, "Απορρίφθηκε", on(updated) + if (detail?.rejectReason.isNullOrBlank()) "" else " · η αιτιολογία παρακάτω")
            request.waitingClient || (ask != null && AadeRequests.open(request)) -> {
                val askDate = ask?.date?.let(RequestDetail::stamp).orEmpty().ifBlank { request.askDate }
                Headline(
                    Tone.ACTION,
                    "Η ΑΑΔΕ περιμένει τον πελάτη",
                    if (askDate.isBlank()) "Ζητήθηκαν διευκρινίσεις ή έγγραφα"
                    else "Ζήτησε διευκρινίσεις στις " + AadeRequests.date(askDate),
                )
            }
            request.answered -> Headline(
                Tone.DONE,
                "Ολοκληρώθηκε — απάντησε η ΑΑΔΕ",
                on(updated) + when (replies) {
                    0 -> ""
                    1 -> " · με 1 έγγραφο"
                    else -> " · με $replies έγγραφα"
                },
            )
            code == AadeRequests.STATUS_ARCHIVED -> Headline(Tone.CLOSED, "Αρχειοθετήθηκε χωρίς ενέργεια", on(updated))
            code == AadeRequests.STATUS_REVOKED -> Headline(Tone.CLOSED, "Ανακλήθηκε", on(updated))
            code == AadeRequests.STATUS_SENT_FOR_REVOCATION -> Headline(Tone.WORKING, "Ζητήθηκε ανάκληση", on(updated))
            code == AadeRequests.STATUS_REVIEW -> Headline(Tone.WORKING, "Σε επανεξέταση", by(handler))
            code == AadeRequests.STATUS_PROCESSING -> Headline(Tone.WORKING, "Σε επεξεργασία", by(handler))
            code == AadeRequests.STATUS_SENT -> Headline(Tone.WORKING, "Υποβλήθηκε — περιμένει ανάθεση", "")
            else -> Headline(
                Tone.WORKING,
                request.status.ifBlank { AadeRequests.statusLabel(code) }.ifBlank { "Σε αναμονή" },
                by(handler),
            )
        }
    }

    private fun on(date: String) = if (date.isBlank()) "" else "Στις $date"
    private fun by(handler: String) = if (handler.isBlank()) "" else "Το χειρίζεται: $handler"

    // -------------------------------------------------------------- βήματα

    enum class StepState { DONE, CURRENT, TODO, FAILED }

    data class Step(val label: String, val state: StepState, val date: String = "")

    /**
     * Υποβολή → επεξεργασία → έκβαση. Τρία βήματα και όχι οι εννέα κωδικοί της
     * πύλης: αρκούν για να φανεί πόσο απέχει το αίτημα από το τέλος.
     */
    fun steps(request: AadeRequests.Request): List<Step> {
        val code = request.statusCode
        val closed = request.answered || request.rejected ||
            code in setOf(AadeRequests.STATUS_REJECTED, AadeRequests.STATUS_ARCHIVED, AadeRequests.STATUS_REVOKED)
        val inProgress = !closed && code != AadeRequests.STATUS_SENT
        val submitted = Step("Υποβλήθηκε", StepState.DONE, AadeRequests.date(request.submitted))
        val middle = Step(
            if (request.waitingClient) "Εκκρεμεί ο πελάτης" else "Σε επεξεργασία",
            when {
                closed -> StepState.DONE
                inProgress -> StepState.CURRENT
                else -> StepState.TODO
            },
        )
        val end = when {
            request.rejected || code == AadeRequests.STATUS_REJECTED ->
                Step("Απορρίφθηκε", StepState.FAILED, AadeRequests.date(request.updated))
            code == AadeRequests.STATUS_ARCHIVED -> Step("Αρχειοθετήθηκε", StepState.DONE, AadeRequests.date(request.updated))
            code == AadeRequests.STATUS_REVOKED -> Step("Ανακλήθηκε", StepState.DONE, AadeRequests.date(request.updated))
            request.answered -> Step("Απαντήθηκε", StepState.DONE, AadeRequests.date(request.updated))
            else -> Step("Απάντηση", StepState.TODO)
        }
        return listOf(submitted, middle, end)
    }

    // ------------------------------------------------------------ ανάθεση

    /**
     * Υπηρεσία της ΑΑΔΕ, χωρισμένη σε **όνομα** και **διεύθυνση**: η πύλη τα
     * γράφει μαζί («15η ΥΠΗΡΕΣΙΑ ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ, ΕΘΝΙΚΗΣ ΑΝΤΙΣΤΑΣΕΩΣ
     * 104, ΕΛΕΥΣΙΝΑ 19200»), και η διεύθυνση δεν είναι ο λόγος που κοιτάς.
     */
    data class Service(val name: String, val address: String = "")

    fun service(raw: String): Service {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isBlank()) return Service("")
        val comma = t.indexOf(',')
        // Διεύθυνση μόνο όταν μετά το κόμμα υπάρχει αριθμός (οδός ή ΤΚ)· αλλιώς
        // το κόμμα είναι μέρος του ονόματος.
        if (comma > 0 && Regex("\\d").containsMatchIn(t.substring(comma))) {
            return Service(t.substring(0, comma).trim(), t.substring(comma + 1).trim())
        }
        return Service(t)
    }

    /**
     * Το τμήμα, μόνο όταν λέει κάτι **παραπάνω** από την υπηρεσία. Το «ΤΜΗΜΑ
     * 15ης ΥΠΗΡΕΣΙΑΣ ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ» κάτω από τη «15η ΥΠΗΡΕΣΙΑ
     * ΦΟΡΟΛΟΓΙΚΗΣ ΕΞΥΠΗΡΕΤΗΣΗΣ» είναι η ίδια πληροφορία δύο φορές.
     */
    fun department(dept: String, handlingService: String): String {
        val d = dept.trim()
        if (d.isBlank()) return ""
        val serviceWords = words(service(handlingService).name)
        val deptWords = words(d).filter { it != "τμημα" }
        if (deptWords.isEmpty()) return ""
        val redundant = deptWords.all { w -> serviceWords.any { s -> s.startsWith(w) || w.startsWith(s) } }
        return if (redundant) "" else d
    }

    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
}
