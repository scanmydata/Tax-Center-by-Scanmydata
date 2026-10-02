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
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestsRefresh
import kotlinx.coroutines.CancellationException

/**
 * Ο καθημερινός έλεγχος των αιτημάτων ΑΑΔΕ.
 *
 * Για κάθε πελάτη της λίστας: σύνδεση, ανάγνωση των «Αιτημάτων μου», σύγκριση
 * με την προηγούμενη φορά. Αν κάτι απαντήθηκε, βγαίνει **μία** ειδοποίηση στο
 * τέλος — όχι μία ανά πελάτη.
 *
 * Ισχύουν οι τρεις κανόνες του [DebtWatchWorker], για τους ίδιους λόγους:
 * λάθος κωδικοί βγάζουν τον πελάτη από το πρόγραμμα (το GSIS κλειδώνει), η
 * ουρά του χρήστη προηγείται, και η δουλειά σταματά μόνη της πριν από το όριο
 * των δέκα λεπτών για να συνεχίσει σε νέα εκτέλεση.
 *
 * ## Τι γράφει η ειδοποίηση
 *
 * **Πλήθη, ποτέ ονόματα.** «Απαντήθηκαν 2 αιτήματα ΑΑΔΕ — σε 2 πελάτες». Μια
 * ειδοποίηση φαίνεται στην οθόνη κλειδώματος, σε ρολόι, σε αυτοκίνητο· το όνομα
 * ενός πελάτη δίπλα στη λέξη «ΑΑΔΕ» δεν έχει θέση εκεί. Ποιοι είναι φαίνεται
 * μόλις ανοίξει η εφαρμογή, στην κορυφή της λίστας πελατών.
 */
class RequestWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TaxCenterApp).container
        val settings = container.settings

        val continuing = inputData.getLongArray(RequestWatch.KEY_IDS)?.toList()
        val manual = inputData.getBoolean(RequestWatch.KEY_MANUAL, false)
        val attempt = inputData.getInt(RequestWatch.KEY_ATTEMPT, 0)
        var tally = RequestWatch.Tally.of(inputData.getIntArray(RequestWatch.KEY_TALLY))

        // Η περιοδική εργασία μπορεί να ξυπνήσει αφού ο χρήστης έκλεισε το
        // πρόγραμμα· η ακύρωση στο σύστημα δεν είναι στιγμιαία.
        if (!manual && !settings.requestWatchDaily) return Result.success()

        val queue = continuing ?: settings.requestWatchClients.sorted()
        if (queue.isEmpty()) return Result.success()

        val left = ArrayList(queue)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            for (id in queue) {
                if (SystemClock.elapsedRealtime() - startedAt > BUDGET_MS) break

                val client = container.db.clients().byId(id)
                if (client == null || client.deleted) {
                    left.remove(id)
                    settings.requestWatchClients = settings.requestWatchClients - id
                    continue
                }

                val result = RequestsRefresh.run(
                    filesDir = applicationContext.filesDir,
                    fetch = container.fetch,
                    repository = container.repository,
                    settings = settings,
                    client = client,
                    ownsBatch = true,
                )
                if (result.outcome == RequestsRefresh.Outcome.BUSY) {
                    // Η ουρά είναι του χρήστη αυτή τη στιγμή. Ξαναδοκιμάζουμε
                    // αργότερα, με όριο.
                    if (attempt >= MAX_BUSY_ATTEMPTS) {
                        finish(tally, suffix = " · σταμάτησε: έτρεχε άλλη λήψη")
                    } else {
                        RequestWatch.enqueueNext(applicationContext, left, attempt + 1, tally, BUSY_DELAY_MINUTES)
                    }
                    return Result.success()
                }
                // Βγαίνει από τη λίστα **αφού** τρέξει — βλ. DebtWatchWorker.
                left.remove(id)

                tally = when (result.outcome) {
                    RequestsRefresh.Outcome.MISSING -> tally.copy(skipped = tally.skipped + 1)
                    RequestsRefresh.Outcome.REJECTED -> {
                        settings.requestWatchClients = settings.requestWatchClients - id
                        container.db.audit().log(
                            AuditEntity(
                                ts = System.currentTimeMillis(),
                                action = "REQUEST_WATCH_DROPPED",
                                afm = client.afm,
                                detail = "αφαιρέθηκε από την παρακολούθηση αιτημάτων ΑΑΔΕ: οι κωδικοί απορρίφθηκαν",
                            ),
                        )
                        tally.copy(dropped = tally.dropped + 1)
                    }
                    RequestsRefresh.Outcome.OK -> {
                        val answered = result.answered
                        val moved = result.changes.count { it.kind == AadeRequests.Kind.STATUS }
                        tally.copy(
                            checked = tally.checked + 1,
                            answered = tally.answered + answered,
                            moved = tally.moved + moved,
                            clients = tally.clients + if (result.changes.isNotEmpty()) 1 else 0,
                        )
                    }
                    else -> tally.copy(failed = tally.failed + 1)
                }
            }
        } catch (e: CancellationException) {
            // Το σύστημα μας σταμάτησε. Ό,τι έμεινε συνεχίζει σε νέα εκτέλεση·
            // ο πελάτης που έτρεχε τώρα μένει έξω ώς τον επόμενο κύκλο.
            left.removeFirstOrNull()
            if (left.isNotEmpty()) {
                RequestWatch.enqueueNext(applicationContext, left, 0, tally, CONTINUE_DELAY_MINUTES)
            }
            throw e
        }

        if (left.isNotEmpty()) {
            RequestWatch.enqueueNext(applicationContext, left, 0, tally, CONTINUE_DELAY_MINUTES)
        } else {
            finish(tally)
        }
        return Result.success()
    }

    /** Τέλος εκτέλεσης: γράφεται ο απολογισμός και, αν αξίζει, βγαίνει ειδοποίηση. */
    private fun finish(tally: RequestWatch.Tally, suffix: String = "") {
        val settings = (applicationContext as TaxCenterApp).container.settings
        val summary = tally.summary() + suffix
        settings.requestWatchLastRun = System.currentTimeMillis()
        settings.requestWatchLastSummary = summary
        // Ειδοποίηση μόνο όταν υπάρχει κάτι να δει άνθρωπος. Ένα «καμία νέα
        // απάντηση» κάθε πρωί μαθαίνει τον χρήστη να τις αγνοεί — και τότε θα
        // αγνοήσει και αυτήν που λέει ότι απάντησε η ΑΑΔΕ.
        if (tally.news || tally.failed > 0 || tally.dropped > 0 || suffix.isNotBlank()) {
            notify(tally.headline(), summary)
        }
    }

    private fun notify(title: String, text: String) {
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
                        "Αιτήματα ΑΑΔΕ",
                        // Υψηλότερη από των οφειλών: μια απάντηση της ΑΑΔΕ έχει
                        // συχνά προθεσμία, και ο λόγος που υπάρχει η
                        // παρακολούθηση είναι να μη χαθεί.
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Απαντήσεις και αλλαγές στα αιτήματα των πελατών προς την ΑΑΔΕ"
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_fetch)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    // Φαίνεται και στην οθόνη κλειδώματος: το κείμενο έχει μόνο
                    // πλήθη, και μια ειδοποίηση που κρύβεται ως το ξεκλείδωμα
                    // δεν ειδοποιεί κανέναν.
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }

    private companion object {
        /** Το σύστημα δίνει δέκα λεπτά· κρατάμε τέσσερα για τον πελάτη που ήδη τρέχει. */
        const val BUDGET_MS = 6 * 60 * 1000L

        const val CONTINUE_DELAY_MINUTES = 1L
        const val BUSY_DELAY_MINUTES = 20L
        const val MAX_BUSY_ATTEMPTS = 9

        const val CHANNEL_ID = "aade-requests"
        const val NOTIFICATION_ID = 1003
    }
}
