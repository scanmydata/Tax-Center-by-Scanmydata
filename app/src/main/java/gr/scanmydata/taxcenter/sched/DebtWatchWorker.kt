package gr.scanmydata.taxcenter.sched

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import gr.scanmydata.taxcenter.MainActivity
import gr.scanmydata.taxcenter.R
import gr.scanmydata.taxcenter.TaxCenterApp
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.debts.DebtsRefresh
import gr.scanmydata.taxcenter.debts.DebtsStore
import gr.scanmydata.taxcenter.engine.FetchController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Η προγραμματισμένη ενημέρωση των καρτελών οφειλών.
 *
 * Κάνει ό,τι και το κουμπί «Ενημέρωση τώρα» της καρτέλας, για όσους πελάτες
 * παρακολουθούνται, έναν-έναν. Τρεις κανόνες που δεν είναι προφανείς:
 *
 * ## 1. Λάθος κωδικοί = έξοδος από το πρόγραμμα
 *
 * Το GSIS **κλειδώνει** τον λογαριασμό μετά από αλλεπάλληλες αποτυχημένες
 * συνδέσεις. Ένα πρόγραμμα που ξαναδοκιμάζει κάθε πρωί τους ίδιους λάθος
 * κωδικούς θα κλείδωνε τον πελάτη από το TAXISnet χωρίς να το ξέρει κανείς.
 * Με την πρώτη απόρριψη ο πελάτης βγαίνει από την παρακολούθηση, και μένει
 * έξω ώσπου να τον ξαναβάλει άνθρωπος.
 *
 * ## 2. Η ουρά του χρήστη προηγείται
 *
 * Αν τρέχει λήψη που έβαλε ο λογιστής, η ενημέρωση **περιμένει** και
 * ξαναδοκιμάζει αργότερα. Δύο συνεδρίες GSIS μαζί είναι κλείδωμα (`OAM-6`).
 *
 * ## 3. Δέκα λεπτά, και μετά συνέχεια
 *
 * Το σύστημα σταματά κάθε εργασία παρασκηνίου στα δέκα λεπτά. Ο worker
 * σταματά μόνος του νωρίτερα, ανάμεσα σε δύο πελάτες, και βάζει τους
 * υπόλοιπους σε επόμενη εκτέλεση — ποτέ δεν κόβεται μια σύνδεση στη μέση
 * επειδή τελείωσε ο χρόνος, εκτός αν ένας μόνο πελάτης πάρει πάνω από όσο
 * απομένει.
 */
class DebtWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TaxCenterApp).container
        val settings = container.settings

        val continuing = inputData.getLongArray(DebtWatch.KEY_IDS)?.toList()
        val manual = inputData.getBoolean(DebtWatch.KEY_MANUAL, false)
        val attempt = inputData.getInt(DebtWatch.KEY_ATTEMPT, 0)
        var tally = DebtWatch.Tally.of(inputData.getIntArray(DebtWatch.KEY_TALLY))

        // Η περιοδική εργασία μπορεί να ξυπνήσει αφού ο χρήστης έκλεισε το
        // πρόγραμμα· η ακύρωση στο σύστημα δεν είναι στιγμιαία.
        if (!manual && DebtWatch.Frequency.of(settings.debtWatchFrequency) == DebtWatch.Frequency.OFF) {
            return Result.success()
        }

        val queue = continuing ?: settings.debtWatchClients.sorted()
        if (queue.isEmpty()) return Result.success()

        val left = ArrayList(queue)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            for (id in queue) {
                if (SystemClock.elapsedRealtime() - startedAt > BUDGET_MS) break

                val client = container.db.clients().byId(id)
                if (client == null || client.deleted) {
                    left.remove(id)
                    settings.debtWatchClients = settings.debtWatchClients - id
                    continue
                }
                val plans = DebtsRefresh.plans(container.repository, client)
                if (plans.isEmpty()) {
                    left.remove(id)
                    tally = tally.copy(skipped = tally.skipped + 1)
                    continue
                }

                val before = fingerprint(client.afm)
                val items = DebtsRefresh.run(container.fetch, plans, ownsBatch = true)
                if (items == null) {
                    // Η ουρά είναι του χρήστη αυτή τη στιγμή. Ξαναδοκιμάζουμε
                    // αργότερα, με όριο: ένα πρόγραμμα που περιμένει για πάντα
                    // δεν λέει ποτέ ότι δεν έτρεξε.
                    if (attempt >= MAX_BUSY_ATTEMPTS) {
                        finish(tally, suffix = " · σταμάτησε: έτρεχε άλλη λήψη")
                    } else {
                        DebtWatch.enqueueNext(applicationContext, left, attempt + 1, tally, BUSY_DELAY_MINUTES)
                    }
                    return Result.success()
                }
                // Βγαίνει από τη λίστα **αφού** τρέξει: αν το σύστημα μας
                // σταματήσει στη μέση, ο πελάτης αυτός δεν ξαναδοκιμάζεται
                // αμέσως — δεν θέλουμε βρόχο πάνω σε λογαριασμό που αργεί.
                left.remove(id)

                val rejected = items.any { it.reason == REASON_BAD_CREDENTIALS }
                val failed = items.any { it.status != FetchController.Status.OK }
                val changed = fingerprint(client.afm) != before
                tally = when {
                    rejected -> {
                        settings.debtWatchClients = settings.debtWatchClients - id
                        container.db.audit().log(
                            AuditEntity(
                                ts = System.currentTimeMillis(),
                                action = "DEBT_WATCH_DROPPED",
                                afm = client.afm,
                                detail = "αφαιρέθηκε από την αυτόματη ενημέρωση οφειλών: οι κωδικοί απορρίφθηκαν",
                            ),
                        )
                        tally.copy(dropped = tally.dropped + 1)
                    }
                    failed -> tally.copy(failed = tally.failed + 1)
                    else -> tally.copy(updated = tally.updated + 1)
                }
                if (changed) tally = tally.copy(changed = tally.changed + 1)
            }
        } catch (e: CancellationException) {
            // Το σύστημα μας σταμάτησε. Ό,τι έμεινε συνεχίζει σε νέα εκτέλεση·
            // ο πελάτης που έτρεχε τώρα μένει έξω ώς τον επόμενο κύκλο.
            left.removeFirstOrNull()
            if (left.isNotEmpty()) {
                DebtWatch.enqueueNext(applicationContext, left, 0, tally, CONTINUE_DELAY_MINUTES)
            }
            throw e
        }

        if (left.isNotEmpty()) {
            DebtWatch.enqueueNext(applicationContext, left, 0, tally, CONTINUE_DELAY_MINUTES)
        } else {
            finish(tally)
        }
        return Result.success()
    }

    private suspend fun fingerprint(afm: String): String = withContext(Dispatchers.IO) {
        DebtsStore.load(applicationContext.filesDir, afm).fingerprint
    }

    /** Τέλος εκτέλεσης: γράφεται ο απολογισμός και, αν αξίζει, βγαίνει ειδοποίηση. */
    private fun finish(tally: DebtWatch.Tally, suffix: String = "") {
        val settings = (applicationContext as TaxCenterApp).container.settings
        val summary = tally.summary() + suffix
        settings.debtWatchLastRun = System.currentTimeMillis()
        settings.debtWatchLastSummary = summary
        // Ειδοποίηση μόνο όταν υπάρχει κάτι να δει άνθρωπος. Ένα «όλα ίδια»
        // κάθε πρωί μαθαίνει τον χρήστη να τις αγνοεί — και τότε θα αγνοήσει
        // και αυτήν που λέει ότι άλλαξε κάτι.
        if (tally.changed > 0 || tally.failed > 0 || tally.dropped > 0 || suffix.isNotBlank()) {
            notify(summary)
        }
    }

    private fun notify(text: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                manager.getNotificationChannel(CHANNEL_ID) == null
            ) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Ενημέρωση οφειλών",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "Αποτέλεσμα της αυτόματης ενημέρωσης των καρτελών οφειλών"
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_fetch)
                    .setContentTitle("Οφειλές πελατών")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    // Στην οθόνη κλειδώματος φαίνεται μόνο ότι υπάρχει
                    // ειδοποίηση. Το κείμενο έχει πλήθη και όχι ονόματα, αλλά
                    // «οφειλές» και «πελάτες» μαζί δεν χρειάζεται να τα
                    // διαβάζει όποιος περάσει δίπλα από το τηλέφωνο.
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }

    private companion object {
        /** Ο engine γράφει έτσι την απόρριψη κωδικών από το GSIS. */
        const val REASON_BAD_CREDENTIALS = "InvalidCredentials"

        /**
         * Πότε σταματάμε να ξεκινάμε νέο πελάτη. Το σύστημα δίνει δέκα λεπτά·
         * κρατάμε τέσσερα για τον πελάτη που ήδη τρέχει.
         */
        const val BUDGET_MS = 6 * 60 * 1000L

        const val CONTINUE_DELAY_MINUTES = 1L
        const val BUSY_DELAY_MINUTES = 20L
        const val MAX_BUSY_ATTEMPTS = 9

        const val CHANNEL_ID = "debts"
        const val NOTIFICATION_ID = 1002
    }
}
