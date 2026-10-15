package gr.scanmydata.taxcenter.sched

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import gr.scanmydata.taxcenter.TaxCenterApp
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.google.DriveRun
import gr.scanmydata.taxcenter.google.DriveSync
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Το **αυτόματο** αντίγραφο στο Drive: μία φορά την ημέρα, γύρω στις 02:00
 * ώρα Αθηνών, όταν υπάρχει δίκτυο και η μπαταρία δεν είναι χαμηλή.
 *
 * Ήταν κουμπί. Ένα αντίγραφο που θέλει άνθρωπο να το θυμηθεί δεν γίνεται
 * ποτέ την ημέρα που χρειάζεται.
 *
 * Τρέχει μόνο όταν το Drive έχει **στηθεί** ([Settings.driveReady]): δόθηκε η
 * άδεια της Google με τον χρήστη μπροστά, υπάρχει φάκελος και passphrase. Το
 * παρασκήνιο δεν μπορεί να ζητήσει άδεια — αν η Google την ανακαλέσει, η
 * εκτέλεση γράφει «χρειάζεται ξανά σύνδεση» και η οθόνη το δείχνει κόκκινο.
 */
object DriveWork {

    const val WORK = "drive-daily"
    private const val HOUR = 2

    /** Μπαίνει στο πρόγραμμα ή βγαίνει, ανάλογα με τη ρύθμιση. */
    fun apply(context: Context, settings: Settings) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (settings.driveMode == DriveSync.Mode.OFF || !settings.driveReady) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val delay = DebtWatch.initialDelay(ZonedDateTime.now(ZONE), HOUR)
        val request = PeriodicWorkRequestBuilder<DriveWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        // KEEP: μια αλλαγή φακέλου ή λειτουργίας δεν μετακινεί την ώρα· η
        // εργασία διαβάζει τις ρυθμίσεις τη στιγμή που τρέχει.
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private val ZONE = java.time.ZoneId.of("Europe/Athens")
}

class DriveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TaxCenterApp).container
        val settings = container.settings
        val mode = settings.driveMode
        if (mode == DriveSync.Mode.OFF || !settings.driveReady) return Result.success()

        val run = DriveRun(applicationContext, container.db, settings, container.driveBackup, container.driveSync)
        val token = try {
            // Χωρίς launcher: αν χρειαστεί οθόνη συγκατάθεσης, πετάει
            // ConsentRequired αντί να ανοίξει κάτι στο παρασκήνιο.
            GoogleAuthorizer(applicationContext, null, arrayOfNulls(1)).accessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleAuthorizer.ConsentRequired) {
            run.recordFailure("Η Google ζητά ξανά άδεια — άνοιξε Ρυθμίσεις → Google Drive.")
            return Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) return Result.retry()
            run.recordFailure("Δεν έγινε σύνδεση με Google: ${e.message ?: e.javaClass.simpleName}")
            return Result.success()
        }

        val stages = buildSet {
            add(DriveRun.Stage.FOLDER)
            add(DriveRun.Stage.BACKUP)
            if (mode == DriveSync.Mode.SYNC) add(DriveRun.Stage.FILES)
        }
        run.run(token, stages)
        return Result.success()
    }
}
