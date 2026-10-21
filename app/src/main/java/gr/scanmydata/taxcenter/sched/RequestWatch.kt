package gr.scanmydata.taxcenter.sched

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import gr.scanmydata.taxcenter.data.Settings
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Η **καθημερινή παρακολούθηση των αιτημάτων ΑΑΔΕ**: όσες φορές τη μέρα και
 * όποιες ώρες διαλέξει ο χρήστης ([Settings.requestWatchHours]), για όσους
 * πελάτες έχουν διαλεχτεί, και ειδοποίηση όταν απαντηθεί ή αλλάξει κάτι.
 *
 * Κάθε ώρα είναι **δική της** ημερήσια εργασία (`request-watch-09`,
 * `request-watch-14`…). Μία εργασία που ξαναπρογραμματίζει τον εαυτό της θα
 * έχανε όλες τις επόμενες ώρες την πρώτη φορά που το σύστημα δεν θα την
 * ξυπνούσε· έτσι, μια χαμένη ώρα δεν παίρνει μαζί της τις υπόλοιπες.
 *
 * Ίδια μηχανική και ίδιοι περιορισμοί με το [DebtWatch] — η ώρα είναι «περίπου»,
 * κάθε εκτέλεση του συστήματος έχει όριο δέκα λεπτών, και η δουλειά κόβεται σε
 * κομμάτια. Χωριστή εργασία και όχι κομμάτι της ενημέρωσης οφειλών, γιατί
 * είναι άλλη απόφαση: άλλοι πελάτες έχουν αιτήματα σε εκκρεμότητα, άλλοι
 * χρωστούν, και ο διακόπτης της μίας δεν πρέπει να ανοίγει συνδέσεις για την
 * άλλη.
 */
object RequestWatch {

    const val WORK = "request-watch"
    const val WORK_CONTINUE = "request-watch-continue"

    const val KEY_IDS = "ids"
    const val KEY_ATTEMPT = "attempt"
    const val KEY_TALLY = "tally"

    /** Η εργασία ζητήθηκε ρητά (τώρα, ή ως συνέχεια) — τρέχει και με κλειστό πρόγραμμα. */
    const val KEY_MANUAL = "manual"

    /**
     * Ο έλεγχος αποθηκεύει **ολόκληρα** όσα αιτήματα λείπουν από τη συσκευή,
     * όχι μόνο όσα άλλαξαν. Το ζητά ο χρήστης από την οθόνη «Αιτήματα»· ο
     * προγραμματισμένος έλεγχος δεν το κάνει, γιατί δεν έχει τον χρόνο.
     */
    const val KEY_FULL = "full"

    /** Το όνομα της εργασίας μιας ώρας. */
    fun slot(hour: Int): String = WORK + "-" + hour.toString().padStart(2, '0')

    /** «09:00, 14:00». */
    fun hoursLabel(hours: List<Int>): String = hours.joinToString(", ") { it.toString().padStart(2, '0') + ":00" }

    /** «1 φορά», «3 φορές». */
    fun timesLabel(count: Int): String = if (count == 1) "1 φορά" else "$count φορές"

    /** Πόσες ώρες το πολύ: κάθε έλεγχος είναι μία σύνδεση στο TAXISnet ανά πελάτη. */
    const val MAX_TIMES = 6

    /** Μπαίνει στο πρόγραμμα, ή βγαίνει. Καλείται σε κάθε αλλαγή ρύθμισης. */
    fun apply(context: Context, settings: Settings) {
        val manager = WorkManager.getInstance(context.applicationContext)
        // Η παλιά, μοναδική εργασία — από εκδόσεις με μία ώρα.
        manager.cancelUniqueWork(WORK)
        val on = settings.requestWatchDaily && settings.requestWatchClients.isNotEmpty()
        val hours = if (on) settings.requestWatchHours.take(MAX_TIMES) else emptyList()
        for (hour in 0..23) if (hour !in hours) manager.cancelUniqueWork(slot(hour))
        if (!on) {
            manager.cancelUniqueWork(WORK_CONTINUE)
            return
        }
        for (hour in hours) {
            val delay = DebtWatch.initialDelay(ZonedDateTime.now(ZONE), hour)
            val request = PeriodicWorkRequestBuilder<RequestWatchWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(online())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(slot(hour), ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }
    }

    /** «Έλεγχος τώρα»: ο ίδιος κώδικας με τον προγραμματισμένο, χωρίς αναμονή. */
    fun runNow(context: Context, full: Boolean = false) {
        enqueueNext(context, ids = null, attempt = 0, tally = Tally(), delayMinutes = 0, full = full)
    }

    /** Το επόμενο κομμάτι. `ids == null` σημαίνει «όλη η λίστα παρακολούθησης». */
    fun enqueueNext(
        context: Context,
        ids: List<Long>?,
        attempt: Int,
        tally: Tally,
        delayMinutes: Long,
        full: Boolean = false,
    ) {
        val data = Data.Builder()
            .putInt(KEY_ATTEMPT, attempt)
            .putIntArray(KEY_TALLY, tally.toArray())
            .putBoolean(KEY_MANUAL, true)
            .putBoolean(KEY_FULL, full)
        if (ids != null) data.putLongArray(KEY_IDS, ids.toLongArray())
        val request = OneTimeWorkRequestBuilder<RequestWatchWorker>()
            .setInputData(data.build())
            .setConstraints(online())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_CONTINUE, ExistingWorkPolicy.REPLACE, request)
    }

    private fun online(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private val ZONE = ZoneId.of("Europe/Athens")

    /**
     * Ο απολογισμός μιας εκτέλεσης, που ταξιδεύει από κομμάτι σε κομμάτι.
     *
     * **Μόνο πλήθη.** Καταλήγει στην ειδοποίηση του Android: το ποιος πελάτης
     * πήρε απάντηση φαίνεται μέσα στην εφαρμογή, πίσω από το κλείδωμά της.
     */
    data class Tally(
        val checked: Int = 0,
        /** Αιτήματα με νέα απάντηση. */
        val answered: Int = 0,
        /** Αιτήματα που άλλαξαν κατάσταση χωρίς νέα απάντηση. */
        val moved: Int = 0,
        /** Πελάτες με τουλάχιστον μία αλλαγή. */
        val clients: Int = 0,
        val failed: Int = 0,
        /** Βγήκαν από την παρακολούθηση επειδή οι κωδικοί τους απορρίφθηκαν. */
        val dropped: Int = 0,
        val skipped: Int = 0,
        /** Μπήκαν «σε εκκρεμότητα από τον συναλλασσόμενο» — θέλουν τον πελάτη. */
        val waiting: Int = 0,
        /** Μπήκαν «σε επεξεργασία». */
        val processing: Int = 0,
    ) {
        fun toArray(): IntArray =
            intArrayOf(checked, answered, moved, clients, failed, dropped, skipped, waiting, processing)

        /** Υπάρχει κάτι που αξίζει ειδοποίηση; */
        val news: Boolean get() = answered > 0 || moved > 0 || waiting > 0 || processing > 0

        /** Ο τίτλος της ειδοποίησης: το πιο επείγον πρώτο. */
        fun headline(): String = when {
            answered == 1 -> "Απαντήθηκε 1 αίτημα ΑΑΔΕ"
            answered > 1 -> "Απαντήθηκαν $answered αιτήματα ΑΑΔΕ"
            waiting == 1 -> "Αίτημα ΑΑΔΕ σε εκκρεμότητα από τον πελάτη"
            waiting > 1 -> "$waiting αιτήματα ΑΑΔΕ σε εκκρεμότητα από τον πελάτη"
            processing == 1 -> "Αίτημα ΑΑΔΕ μπήκε σε επεξεργασία"
            processing > 1 -> "$processing αιτήματα ΑΑΔΕ μπήκαν σε επεξεργασία"
            moved > 0 -> "Αλλαγή σε αιτήματα ΑΑΔΕ"
            else -> "Αιτήματα ΑΑΔΕ"
        }

        fun summary(): String = buildString {
            append(
                when {
                    answered == 1 -> "1 νέα απάντηση"
                    answered > 1 -> "$answered νέες απαντήσεις"
                    else -> "Καμία νέα απάντηση"
                },
            )
            if (news) append(if (clients == 1) " σε 1 πελάτη" else " σε $clients πελάτες")
            if (waiting > 0) {
                append(if (waiting == 1) " · 1 σε εκκρεμότητα από τον πελάτη" else " · $waiting σε εκκρεμότητα από τον πελάτη")
            }
            if (processing > 0) {
                append(if (processing == 1) " · 1 μπήκε σε επεξεργασία" else " · $processing μπήκαν σε επεξεργασία")
            }
            if (moved > 0) append(if (moved == 1) " · 1 αλλαγή κατάστασης" else " · $moved αλλαγές κατάστασης")
            append(if (checked == 1) " · ελέγχθηκε 1 πελάτης" else " · ελέγχθηκαν $checked πελάτες")
            if (failed > 0) append(" · $failed απέτυχαν")
            if (dropped > 0) append(" · $dropped βγήκαν από το πρόγραμμα (λάθος κωδικοί)")
            if (skipped > 0) append(" · $skipped χωρίς κωδικούς")
        }

        companion object {
            /** Δέχεται και τον παλιό πίνακα των 7, από εργασία που μπήκε στην ουρά πριν την ενημέρωση. */
            fun of(raw: IntArray?): Tally =
                if (raw == null || raw.size < 7) Tally()
                else Tally(
                    raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6],
                    raw.getOrElse(7) { 0 }, raw.getOrElse(8) { 0 },
                )
        }
    }
}
