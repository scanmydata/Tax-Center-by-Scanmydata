package gr.scanmydata.taxcenter.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import gr.scanmydata.taxcenter.R
import gr.scanmydata.taxcenter.security.AppLock

/**
 * Η οθόνη ξεκλειδώματος.
 *
 * Δέχεται **και** βιομετρικά **και** τον κωδικό της συσκευής
 * (`DEVICE_CREDENTIAL`). Ο λόγος είναι πρακτικός: ένα δάχτυλο που δεν διαβάζεται
 * δεν πρέπει να αποκλείει τον λογιστή από τη δουλειά του, και ο κωδικός οθόνης
 * είναι ήδη ό,τι προστατεύει τη συσκευή.
 *
 * Αν η συσκευή δεν έχει καθόλου κλείδωμα οθόνης, η εφαρμογή ξεκλειδώνει και το
 * λέει: το να κλειδωθεί ο χρήστης έξω από τα ίδια του τα δεδομένα θα ήταν
 * χειρότερο από την προειδοποίηση.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var message by remember { mutableStateOf("") }
    var prompting by remember { mutableStateOf(false) }

    fun openWithoutLock(reason: String) {
        message = reason
        prompting = false
        AppLock.unlock()
        onUnlocked()
    }

    fun authenticate() {
        if (activity == null) {
            message = "Δεν είναι διαθέσιμη η ταυτοποίηση σε αυτό το περιβάλλον."
            return
        }
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val status = BiometricManager.from(activity).canAuthenticate(allowed)
        if (status == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
            status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        ) {
            openWithoutLock(
                "Η συσκευή δεν έχει κλείδωμα οθόνης. Ενεργοποίησέ το στις ρυθμίσεις " +
                    "της συσκευής — μέχρι τότε η εφαρμογή δεν κλειδώνει.",
            )
            return
        }

        prompting = true
        try {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        prompting = false
                        AppLock.unlock()
                        onUnlocked()
                    }

                    override fun onAuthenticationError(code: Int, errString: CharSequence) {
                        prompting = false
                        message = errString.toString()
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("ScanMyData Tax Center")
                    .setSubtitle("Ξεκλείδωσε για πρόσβαση στα δεδομένα των πελατών")
                    .setAllowedAuthenticators(allowed)
                    .build(),
            )
        } catch (e: Exception) {
            // Σε ορισμένες εκδόσεις ο συνδυασμός βιομετρικών με κωδικό συσκευής
            // δεν υποστηρίζεται και η βιβλιοθήκη πετάει. Ο χρήστης δεν πρέπει να
            // μείνει κλειδωμένος έξω από τα ίδια του τα δεδομένα γι' αυτό.
            openWithoutLock("Η ταυτοποίηση δεν είναι διαθέσιμη σε αυτή τη συσκευή.")
        }
    }

    // Ένα prompt μόλις εμφανιστεί η οθόνη — χωρίς περιττό πάτημα.
    LaunchedEffect(Unit) { authenticate() }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text("ScanMyData Tax Center", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Η εφαρμογή είναι κλειδωμένη.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(enabled = !prompting, onClick = { authenticate() }) { Text("Ξεκλείδωμα") }
    }
}
