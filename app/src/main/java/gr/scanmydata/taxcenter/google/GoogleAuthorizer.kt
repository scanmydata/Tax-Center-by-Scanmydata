package gr.scanmydata.taxcenter.google

import android.content.Context
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import gr.scanmydata.taxcenter.data.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Εξουσιοδότηση Google για αποστολή email.
 *
 * Χρησιμοποιεί το `Identity.getAuthorizationClient` — **όχι** client id/secret
 * μέσα στην εφαρμογή. Σε Android OAuth client η ταυτοποίηση γίνεται από
 * `package name + SHA-1 της υπογραφής`, οπότε δεν υπάρχει τίποτα μυστικό να
 * διαρρεύσει από το APK. Βλ. `docs/google-cloud.md`.
 *
 * Τα scopes είναι σκόπιμα **μόνο sensitive, κανένα restricted**:
 *
 *  * `gmail.send` — μόνο αποστολή· η εφαρμογή δεν μπορεί να διαβάσει
 *    γραμματοκιβώτιο, ούτε καν τη διεύθυνση του αποστολέα (γι' αυτό το μήνυμα
 *    φεύγει χωρίς κεφαλίδα `From:` και τη συμπληρώνει ο Gmail).
 *  * `drive.file` — μόνο τα αρχεία που δημιουργεί η ίδια η εφαρμογή.
 *
 * Το πλήρες `drive` ή το `gmail.readonly` θα ήταν restricted και θα απαιτούσαν
 * επί πληρωμή έλεγχο ασφαλείας από τρίτο φορέα.
 */
class GoogleAuthorizer(
    private val context: Context,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>?,
    private val holder: Array<GoogleAuthorizer?>,
) {

    private var pending: ((Result<String>) -> Unit)? = null

    /**
     * Επιστρέφει access token, ζητώντας συγκατάθεση αν χρειάζεται.
     *
     * Πετάει [ConsentRequired] όταν χρειάζεται UI αλλά δεν υπάρχει launcher —
     * π.χ. όταν καλείται από background εργασία.
     */
    suspend fun accessToken(): String = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map { Scope(it) })
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val intentSender = result.pendingIntent?.intentSender
                    if (launcher == null || intentSender == null) {
                        cont.resumeWithException(ConsentRequired())
                    } else {
                        pending = { outcome ->
                            outcome.fold(cont::resume, cont::resumeWithException)
                        }
                        holder[0] = this
                        launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }
                } else {
                    remember(result)
                    val token = result.accessToken
                    if (token.isNullOrBlank()) cont.resumeWithException(IllegalStateException("κενό token"))
                    else cont.resume(token)
                }
            }
            .addOnFailureListener(cont::resumeWithException)
    }

    /** Καλείται από τον launcher όταν γυρίσει η οθόνη συγκατάθεσης. */
    fun onConsentResult(intentSenderResultOk: Boolean, data: android.content.Intent?) {
        val callback = pending ?: return
        pending = null
        if (!intentSenderResultOk) {
            callback(Result.failure(ConsentDeclined()))
            return
        }
        try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            remember(result)
            val token = result.accessToken
            if (token.isNullOrBlank()) callback(Result.failure(IllegalStateException("κενό token")))
            else callback(Result.success(token))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    private fun remember(result: AuthorizationResult) {
        val settings = Settings(context)
        settings.googleConnected = true
        result.toGoogleSignInAccount()?.email?.let { settings.senderEmail = it }
    }

    class ConsentRequired : Exception("Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις.")
    class ConsentDeclined : Exception("Η σύνδεση με Google ακυρώθηκε.")

    companion object {
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/drive.file",
            Scopes.EMAIL,
        )
    }
}

/**
 * Φτιάχνει έναν [GoogleAuthorizer] δεμένο με launcher συγκατάθεσης.
 *
 * Ο πίνακας ενός στοιχείου είναι το γνωστό κόλπο για να φτάσει το αποτέλεσμα
 * του launcher πίσω στο instance που το ζήτησε — ο launcher δημιουργείται πριν
 * από το αντικείμενο.
 */
@Composable
fun rememberGoogleAuthorizer(): GoogleAuthorizer {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<GoogleAuthorizer>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        holder[0]?.onConsentResult(result.resultCode == android.app.Activity.RESULT_OK, result.data)
    }
    return remember(launcher) { GoogleAuthorizer(context, launcher, holder) }
}
