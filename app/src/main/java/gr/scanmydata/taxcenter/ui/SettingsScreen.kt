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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.BuildConfig
import gr.scanmydata.taxcenter.gdpr.Exports
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import gr.scanmydata.taxcenter.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    var googleStatus by remember { mutableStateOf(
        if (settings.googleConnected) "Συνδεδεμένο: ${settings.senderEmail}" else "Δεν έχει συνδεθεί",
    ) }
    val context = LocalContext.current
    var diagnostics by remember { mutableStateOf(settings.diagnostics) }
    var lockEnabled by remember { mutableStateOf(settings.lockEnabled) }
    var blockScreenshots by remember { mutableStateOf(settings.blockScreenshots) }
    var retention by remember { mutableStateOf(settings.retentionMonths.toString()) }
    var exportStatus by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf("") }
    var updateBusy by remember { mutableStateOf(false) }
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
            title = "Κλείδωμα εφαρμογής",
            description = "Ζητά βιομετρικά ή τον κωδικό της συσκευής στο άνοιγμα και " +
                "μετά από ${settings.lockGraceSeconds} δευτερόλεπτα στο παρασκήνιο. " +
                "Η βάση είναι ήδη κρυπτογραφημένη· αυτό προστατεύει από ξεκλείδωτη " +
                "συσκευή πάνω στο γραφείο.",
            checked = lockEnabled,
            onChange = { lockEnabled = it; settings.lockEnabled = it },
        )

        Spacer(Modifier.height(12.dp))
        SettingSwitch(
            title = "Αποκλεισμός στιγμιότυπων οθόνης",
            description = "Εμποδίζει screenshot και καταγραφή οθόνης, και κρύβει το " +
                "περιεχόμενο από τα «πρόσφατα». Ισχύει μετά την επόμενη επιστροφή " +
                "στην εφαρμογή.",
            checked = blockScreenshots,
            onChange = { blockScreenshots = it; settings.blockScreenshots = it },
        )

        Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Προστασία δεδομένων", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = retention,
            onValueChange = {
                retention = it.filter(Char::isDigit).take(3)
                settings.retentionMonths = retention.toIntOrNull() ?: 0
            },
            label = { Text("Διατήρηση ληφθέντων εντύπων (μήνες, 0 = χωρίς όριο)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Τα παλαιότερα PDF διαγράφονται αυτόματα στο άνοιγμα της εφαρμογής. Η " +
                "καρτέλα και τα διαπιστευτήρια του πελάτη δεν θίγονται — μόνο τα αρχεία.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            scope.launch {
                exportStatus = "Εξαγωγή…"
                exportStatus = try {
                    val file = withContext(Dispatchers.IO) { Exports.auditCsv(context, container.db) }
                    Exports.share(context, file, "text/csv", "Αρχείο δραστηριοτήτων")
                    "Εξήχθησαν ${file.length() / 1024} KB."
                } catch (e: Exception) {
                    "Απέτυχε: ${e.message}"
                }
            }
        }) { Text("Εξαγωγή αρχείου δραστηριοτήτων (CSV)") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Το αρχείο του άρθρου 30: ποιος, πότε, ποιου πελάτη δεδομένα, ποια " +
                "ενέργεια. Ποτέ τιμές — καμία στήλη δεν περιέχει κωδικό.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        if (exportStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(exportStatus, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Η εξαγωγή δεδομένων ενός πελάτη (φορητότητα, άρθρο 20) και η οριστική " +
                "διαγραφή του (άρθρο 17) γίνονται από την καρτέλα του.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Ενημέρωση", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Τρέχουσα έκδοση ${BuildConfig.VERSION_NAME}. Ο έλεγχος είναι " +
                "χειροκίνητος και ανώνυμος: ένα GET στο δημόσιο API του GitHub, " +
                "χωρίς κανένα στοιχείο της συσκευής ή των πελατών.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !updateBusy,
            onClick = {
                scope.launch {
                    updateBusy = true
                    updateStatus = "Έλεγχος…"
                    updateStatus = try {
                        val release = withContext(Dispatchers.IO) { UpdateChecker.latest() }
                        when {
                            release == null -> "Δεν βρέθηκε έκδοση με APK."
                            !UpdateChecker.isNewer(release.version) ->
                                "Έχεις την τελευταία έκδοση (${release.tag})."
                            else -> {
                                val apk = withContext(Dispatchers.IO) {
                                    UpdateChecker.download(context, release)
                                }
                                UpdateChecker.install(context, apk)
                                "Λήφθηκε η ${release.tag} — ολοκλήρωσε την εγκατάσταση."
                            }
                        }
                    } catch (e: Exception) {
                        "Απέτυχε: ${e.message}"
                    }
                    updateBusy = false
                }
            },
        ) { Text("Έλεγχος για ενημέρωση") }

        if (updateStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(updateStatus, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(32.dp))
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
