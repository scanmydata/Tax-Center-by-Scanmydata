package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    var googleStatus by remember { mutableStateOf(
        if (settings.googleConnected) "Συνδεδεμένο: ${settings.senderEmail}" else "Δεν έχει συνδεθεί",
    ) }
    var diagnostics by remember { mutableStateOf(settings.diagnostics) }
    var includeSecrets by remember { mutableStateOf(settings.includePasswordsInClientEmail) }
    var officeName by remember { mutableStateOf(settings.officeName) }
    var signature by remember { mutableStateOf(settings.signature) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {

        Text("Google", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(googleStatus, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                googleStatus = try {
                    authorizer.accessToken()
                    "Συνδεδεμένο: ${settings.senderEmail}"
                } catch (e: Exception) {
                    "Απέτυχε: ${e.message}"
                }
            }
        }) { Text("Σύνδεση με Google") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Ζητούνται μόνο δικαιώματα αποστολής email και πρόσβασης στα αρχεία που " +
                "δημιουργεί η ίδια η εφαρμογή. Δεν διαβάζεται το γραμματοκιβώτιό σας.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Email", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = officeName,
            onValueChange = { officeName = it; settings.officeName = it },
            label = { Text("Όνομα γραφείου") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = signature,
            onValueChange = { signature = it; settings.signature = it },
            label = { Text("Υπογραφή") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Ασφάλεια", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        SettingSwitch(
            title = "Αποστολή κωδικών στους πελάτες",
            description = "Επιτρέπει να συμπεριλαμβάνονται συνθηματικό TAXISnet και " +
                "κλειδάριθμος στο email με τα στοιχεία του πελάτη. Το email δεν είναι " +
                "ασφαλές κανάλι — κρατήστε το κλειστό αν δεν το χρειάζεστε.",
            checked = includeSecrets,
            onChange = { includeSecrets = it; settings.includePasswordsInClientEmail = it },
            warn = true,
        )

        Spacer(Modifier.height(12.dp))
        SettingSwitch(
            title = "Διαγνωστικά αρχεία",
            description = "Κρατά στη συσκευή τα ενδιάμεσα αρχεία των διαδικασιών " +
                "(σελίδες ΑΑΔΕ σε καθαρό κείμενο). Χρήσιμο μόνο για διερεύνηση " +
                "προβλήματος. Το ιστορικό εκτελέσεων καταγράφεται ούτως ή άλλως.",
            checked = diagnostics,
            onChange = { diagnostics = it; settings.diagnostics = it },
            warn = true,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    warn: Boolean = false,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn && checked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
