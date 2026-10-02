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
 * Η **καθημερινή παρακολούθηση των αιτημάτων ΑΑΔΕ**: μία φορά τη μέρα, για
 * όσους πελάτες έχουν διαλεχτεί, και ειδοποίηση όταν απαντηθεί κάτι.
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

    /** Μπαίνει στο πρόγραμμα, ή βγαίνει. Καλείται σε κάθε αλλαγή ρύθμισης. */
    fun apply(context: Context, settings: Settings) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!settings.requestWatchDaily || settings.requestWatchClients.isEmpty()) {
            manager.cancelUniqueWork(WORK)
            manager.cancelUniqueWork(WORK_CONTINUE)
            return
        }
        val delay = DebtWatch.initialDelay(ZonedDateTime.now(ZONE), settings.requestWatchHour)
        val request = PeriodicWorkRequestBuilder<RequestWatchWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    /** «Έλεγχος τώρα»: ο ίδιος κώδικας με τον προγραμματισμένο, χωρίς αναμονή. */
    fun runNow(context: Context) {
        enqueueNext(context, ids = null, attempt = 0, tally = Tally(), delayMinutes = 0)
    }

    /** Το επόμενο κομμάτι. `ids == null` σημαίνει «όλη η λίστα παρακολούθησης». */
    fun enqueueNext(
        context: Context,
        ids: List<Long>?,
        attempt: Int,
        tally: Tally,
        delayMinutes: Long,
    ) {
        val data = Data.Builder()
            .putInt(KEY_ATTEMPT, attempt)
            .putIntArray(KEY_TALLY, tally.toArray())
            .putBoolean(KEY_MANUAL, true)
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
    ) {
        fun toArray(): IntArray = intArrayOf(checked, answered, moved, clients, failed, dropped, skipped)

        /** Υπάρχει κάτι που αξίζει ειδοποίηση; */
        val news: Boolean get() = answered > 0 || moved > 0

        /** Ο τίτλος της ειδοποίησης. */
        fun headline(): String = when {
            answered == 1 -> "Απαντήθηκε 1 αίτημα ΑΑΔΕ"
            answered > 1 -> "Απαντήθηκαν $answered αιτήματα ΑΑΔΕ"
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
            if (moved > 0) append(if (moved == 1) " · 1 αλλαγή κατάστασης" else " · $moved αλλαγές κατάστασης")
            append(if (checked == 1) " · ελέγχθηκε 1 πελάτης" else " · ελέγχθηκαν $checked πελάτες")
            if (failed > 0) append(" · $failed απέτυχαν")
            if (dropped > 0) append(" · $dropped βγήκαν από το πρόγραμμα (λάθος κωδικοί)")
            if (skipped > 0) append(" · $skipped χωρίς κωδικούς")
        }

        companion object {
            fun of(raw: IntArray?): Tally =
                if (raw == null || raw.size < 7) Tally()
                else Tally(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6])
        }
    }
}
