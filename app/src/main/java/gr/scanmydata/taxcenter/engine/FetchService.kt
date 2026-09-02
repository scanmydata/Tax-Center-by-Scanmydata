package gr.scanmydata.taxcenter.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import gr.scanmydata.taxcenter.MainActivity
import gr.scanmydata.taxcenter.R

/**
 * Κρατά τη διεργασία ζωντανή όσο τρέχει μια παρτίδα λήψης.
 *
 * **Δεν κάνει τη δουλειά η ίδια** — αυτή τρέχει στο [FetchController], που ζει
 * όσο η εφαρμογή. Ο ρόλος της υπηρεσίας είναι δύο πράγματα που δεν γίνονται
 * αλλιώς:
 *
 *  1. Μια παρτίδα 40 πελατών × 5 έντυπα διαρκεί δεκάδες λεπτά. Χωρίς foreground
 *     service το Android σκοτώνει τη διεργασία μόλις ο χρήστης αλλάξει
 *     εφαρμογή, και η λήψη χάνεται στη μέση — με τις συνεδρίες GSIS ανοιχτές.
 *  2. Ο χρήστης βλέπει την πρόοδο χωρίς να επιστρέψει στην εφαρμογή.
 *
 * Γιατί όχι `WorkManager`: το `aade-enfia` χρειάζεται **ορατό** WebView για
 * OTP/CAPTCHA, που δεν παρακάμπτονται. Ένα Worker δεν μπορεί να δείξει σελίδα
 * στον χρήστη· ο controller μπορεί, γιατί η οθόνη λήψης του δίνει container.
 */
class FetchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Προετοιμασία…" }
        val done = intent?.getIntExtra(EXTRA_DONE, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0

        val notification = build(this, text, done, total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "fetch"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        /** Ξεκινά ή ενημερώνει την ειδοποίηση προόδου. */
        fun update(context: Context, text: String, done: Int, total: Int) {
            ensureChannel(context)
            val intent = Intent(context, FetchService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Σε παλιότερες εκδόσεις μπορεί να μας εμποδίσει ο περιορισμός
                // εκκίνησης από background. Η λήψη συνεχίζει· χάνεται μόνο η
                // ειδοποίηση, οπότε δεν έχει νόημα να σκάσει η παρτίδα γι' αυτό.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FetchService::class.java))
            } catch (e: Exception) {
                // ό,τι και παραπάνω
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Λήψη εντύπων",
                    // LOW: η πρόοδος δεν πρέπει να κάνει ήχο σε κάθε πελάτη.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Πρόοδος της λήψης εντύπων από ΑΑΔΕ και ΕΦΚΑ"
                    setShowBadge(false)
                },
            )
        }

        private fun build(context: Context, text: String, done: Int, total: Int): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_fetch)
                .setContentTitle("Λήψη εντύπων")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
                .apply { if (total > 0) setProgress(total, done, false) }
                .build()
        }
    }
}
