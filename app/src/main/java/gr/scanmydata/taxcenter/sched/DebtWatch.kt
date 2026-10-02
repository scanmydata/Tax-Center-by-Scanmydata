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
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Ο **χρονοπρογραμματισμός** της ενημέρωσης οφειλών: κάθε πότε, για ποιους, και
 * πώς μπαίνει στο πρόγραμμα του συστήματος.
 *
 * ## Τι υπόσχεται και τι όχι
 *
 * Το Android δεν δίνει σε καμία εφαρμογή ακριβή ώρα για δουλειά παρασκηνίου:
 * την εκτελεί **γύρω** από την ώρα που ζητήθηκε, όταν υπάρχει δίκτυο και η
 * συσκευή δεν κοιμάται βαθιά. Η οθόνη το λέει — «περίπου στις 08:00» — αντί να
 * υποσχεθεί κάτι που θα διαψευστεί το πρώτο πρωί.
 *
 * Κάθε εκτέλεση του συστήματος έχει όριο δέκα λεπτών. Γι' αυτό η δουλειά
 * κόβεται σε κομμάτια: ο [DebtWatchWorker] ενημερώνει όσους πελάτες προλάβει
 * και ξαναμπαίνει στην ουρά για τους υπόλοιπους.
 *
 * ## Γιατί η ώρα είναι σε ώρα Αθηνών
 *
 * Όπως και το ημερολόγιο αποστολών: το πρόγραμμα είναι του γραφείου, όχι του
 * κινητού. Ένα τηλέφωνο που ταξιδεύει δεν πρέπει να μετακινεί τη λήψη.
 */
object DebtWatch {

    enum class Frequency(val label: String, val days: Long) {
        OFF("Ανενεργή", 0),
        DAILY("Κάθε μέρα", 1),
        WEEKLY("Κάθε εβδομάδα", 7),
        MONTHLY("Κάθε μήνα", 30);

        companion object {
            /** Άγνωστη τιμή = κλειστό. Μια χαλασμένη ρύθμιση δεν ανοίγει συνδέσεις. */
            fun of(raw: String): Frequency = entries.firstOrNull { it.name == raw } ?: OFF
        }
    }

    /** Η περιοδική εργασία του προγράμματος. */
    const val WORK = "debt-watch"

    /** Το επόμενο κομμάτι μιας εκτέλεσης που δεν χώρεσε στο όριο του συστήματος. */
    const val WORK_CONTINUE = "debt-watch-continue"

    const val KEY_IDS = "ids"
    const val KEY_ATTEMPT = "attempt"
    const val KEY_TALLY = "tally"

    /**
     * Εφαρμόζει τις ρυθμίσεις: μπαίνει στο πρόγραμμα, ή βγαίνει.
     *
     * Καλείται κάθε φορά που αλλάζει συχνότητα, ώρα ή λίστα πελατών. Το
     * πρόγραμμα **ξαναστήνεται** από την αρχή — επόμενη εκτέλεση στην επόμενη
     * εμφάνιση της ώρας — ώστε η αλλαγή να ισχύει αμέσως και προβλέψιμα.
     */
    fun apply(context: Context, settings: Settings) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val frequency = Frequency.of(settings.debtWatchFrequency)
        if (frequency == Frequency.OFF || settings.debtWatchClients.isEmpty()) {
            manager.cancelUniqueWork(WORK)
            manager.cancelUniqueWork(WORK_CONTINUE)
            return
        }
        val delay = initialDelay(ZonedDateTime.now(ZONE), settings.debtWatchHour)
        val request = PeriodicWorkRequestBuilder<DebtWatchWorker>(frequency.days, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    /**
     * «Εκτέλεση τώρα»: ο ίδιος κώδικας με την προγραμματισμένη, χωρίς αναμονή.
     *
     * Υπάρχει για να φαίνεται ότι το πρόγραμμα δουλεύει πριν το εμπιστευτεί
     * κανείς μια ολόκληρη νύχτα.
     */
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
        val request = OneTimeWorkRequestBuilder<DebtWatchWorker>()
            .setInputData(data.build())
            .setConstraints(online())
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_CONTINUE, ExistingWorkPolicy.REPLACE, request)
    }

    /** Η εργασία ζητήθηκε ρητά (τώρα, ή ως συνέχεια) — τρέχει και με κλειστό πρόγραμμα. */
    const val KEY_MANUAL = "manual"

    private fun online(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private val ZONE = java.time.ZoneId.of("Europe/Athens")

    /**
     * Πόσο μένει ως την επόμενη εμφάνιση της [hour]:00.
     *
     * Αν η ώρα έχει ήδη περάσει σήμερα — ή είναι ακριβώς τώρα — πάει αύριο: μια
     * αλλαγή ρύθμισης στις 08:00:30 δεν πρέπει να ξεκινά λήψη «μόλις τώρα».
     */
    fun initialDelay(now: ZonedDateTime, hour: Int): Duration {
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** Πότε θα τρέξει ξανά, για την οθόνη. */
    fun nextRun(now: ZonedDateTime, hour: Int): ZonedDateTime = now.plus(initialDelay(now, hour))

    /**
     * Ο απολογισμός μιας εκτέλεσης, που ταξιδεύει από κομμάτι σε κομμάτι.
     *
     * **Μόνο πλήθη.** Καταλήγει σε ειδοποίηση που φαίνεται και στην οθόνη
     * κλειδώματος: ονόματα πελατών δίπλα στη λέξη «οφειλές» δεν έχουν θέση εκεί.
     */
    data class Tally(
        val updated: Int = 0,
        val changed: Int = 0,
        val failed: Int = 0,
        /** Βγήκαν από την παρακολούθηση επειδή οι κωδικοί τους απορρίφθηκαν. */
        val dropped: Int = 0,
        val skipped: Int = 0,
    ) {
        fun toArray(): IntArray = intArrayOf(updated, changed, failed, dropped, skipped)

        val total: Int get() = updated + failed + dropped + skipped

        fun summary(): String = buildString {
            append(
                when (updated) {
                    0 -> "Καμία καρτέλα δεν ενημερώθηκε"
                    1 -> "Ενημερώθηκε 1 καρτέλα"
                    else -> "Ενημερώθηκαν $updated καρτέλες"
                },
            )
            if (changed > 0) append(" · αλλαγές σε $changed")
            if (failed > 0) append(" · $failed απέτυχαν")
            if (dropped > 0) append(" · $dropped βγήκαν από το πρόγραμμα (λάθος κωδικοί)")
            if (skipped > 0) append(" · $skipped χωρίς κωδικούς")
        }

        companion object {
            fun of(raw: IntArray?): Tally =
                if (raw == null || raw.size < 5) Tally() else Tally(raw[0], raw[1], raw[2], raw[3], raw[4])
        }
    }
}
