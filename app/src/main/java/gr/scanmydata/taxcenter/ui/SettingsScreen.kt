package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.BuildConfig
import gr.scanmydata.taxcenter.gdpr.Exports
import gr.scanmydata.taxcenter.google.DriveBackup
import gr.scanmydata.taxcenter.google.DriveSync
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import gr.scanmydata.taxcenter.mail.MailTemplateStore
import gr.scanmydata.taxcenter.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    var googleStatus by remember { mutableStateOf("") }
    // Ξεχωριστή κατάσταση από τις ρυθμίσεις, ώστε η κάρτα να ανανεώνεται μόλις
    // γυρίσει η σύνδεση — το `Settings` είναι SharedPreferences και δεν
    // ειδοποιεί το Compose από μόνο του.
    var connected by remember { mutableStateOf(settings.googleConnected) }
    var account by remember { mutableStateOf(settings.senderEmail) }
    val context = LocalContext.current
    var diagnostics by remember { mutableStateOf(settings.diagnostics) }
    var lockEnabled by remember { mutableStateOf(settings.lockEnabled) }
    var blockScreenshots by remember { mutableStateOf(settings.blockScreenshots) }
    var retention by remember { mutableStateOf(settings.retentionMonths.toString()) }
    var updateStatus by remember { mutableStateOf("") }
    var updateBusy by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(emptyList<DriveBackup.Entry>()) }
    var restoreTarget by remember { mutableStateOf<DriveBackup.Entry?>(null) }
    var includeSecrets by remember { mutableStateOf(settings.includePasswordsInClientEmail) }
    var officeName by remember { mutableStateOf(settings.officeName) }
    var signature by remember { mutableStateOf(settings.signature) }
    var signatureDocuments by remember { mutableStateOf(settings.signatureDocuments) }
    var signatureCredentials by remember { mutableStateOf(settings.signatureCredentials) }
    val templateStore = remember { MailTemplateStore(context) }
    var driveMode by remember { mutableStateOf(settings.driveMode) }
    var syncStatus by remember { mutableStateOf("") }
    var syncBusy by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<TemplateKind?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {

        Text("Google", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // Ποιος λογαριασμός στέλνει, ρητά και μπροστά. Σε γραφείο με
        // περισσότερους από έναν λογαριασμούς Google, το «είμαι συνδεδεμένος»
        // δεν λέει τίποτα — το «από ποιον φεύγουν τα email» λέει.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (connected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (connected) "Ενεργό προφίλ αποστολής" else "Καμία σύνδεση",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        !connected -> "Χωρίς σύνδεση δεν φεύγει κανένα email."
                        account.isNotBlank() -> account
                        // Συνδεδεμένος αλλά χωρίς όνομα λογαριασμού: το email
                        // έρχεται από χωριστή κλήση στο userinfo και μπορεί να
                        // μην έχει γίνει ακόμη. Η αποστολή δουλεύει ούτως ή άλλως.
                        else -> "Συνδεδεμένο (το όνομα του λογαριασμού θα φανεί μετά την πρώτη αποστολή)"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (googleStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        googleStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (googleStatus.startsWith("Απέτυχε")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                scope.launch {
                    googleStatus = "Σύνδεση…"
                    googleStatus = try {
                        authorizer.accessToken()
                        connected = settings.googleConnected
                        account = settings.senderEmail
                        ""
                    } catch (e: Exception) {
                        "Απέτυχε: ${e.message}"
                    }
                }
            }) { Text(if (connected) "Ανανέωση" else "Σύνδεση με Google") }

            if (connected) {
                OutlinedButton(onClick = {
                    scope.launch {
                        authorizer.forget()
                        connected = false
                        account = ""
                        googleStatus = "Αποσυνδέθηκε. Πάτα «Σύνδεση» για να διαλέξεις λογαριασμό."
                    }
                }) { Text("Αλλαγή λογαριασμού") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Ζητούνται μόνο δικαιώματα αποστολής email και πρόσβασης στα αρχεία που " +
                "δημιουργεί η ίδια η εφαρμογή. Δεν διαβάζεται το γραμματοκιβώτιό σας.\n\n" +
                "Η «αλλαγή λογαριασμού» ξεχνά την επιλογή σε αυτή τη συσκευή. Η " +
                "εξουσιοδότηση που έχει ήδη δοθεί στην εφαρμογή ανακαλείται μόνο από " +
                "τις ρυθμίσεις του λογαριασμού Google.",
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
            label = { Text("Υπογραφή (κοινή)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ξεχωριστή υπογραφή ανά είδος μηνύματος",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Κενό πεδίο σημαίνει «χρησιμοποίησε την κοινή». Τα δύο μηνύματα δεν " +
                "κλείνουν το ίδιο: αυτό με τους κωδικούς χρειάζεται σύσταση να μην " +
                "προωθηθεί, αυτό με τα έντυπα όχι.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = signatureDocuments,
            onValueChange = { signatureDocuments = it; settings.signatureDocuments = it },
            label = { Text("Υπογραφή — αποστολή εντύπων") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = signatureCredentials,
            onValueChange = { signatureCredentials = it; settings.signatureCredentials = it },
            label = { Text("Υπογραφή — αποστολή στοιχείων/κωδικών") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Text("Πρότυπα μηνυμάτων", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            "Θέμα, κείμενα, και ποια πεδία μπαίνουν σε κάθε μήνυμα.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { editingTemplate = TemplateKind.DOCUMENTS }) {
                Text("Έντυπα")
            }
            OutlinedButton(onClick = { editingTemplate = TemplateKind.CREDENTIALS }) {
                Text("Στοιχεία & κωδικοί")
            }
        }

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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
        OutlinedButton(onClick = onOpenLogs) { Text("Αρχείο ενεργειών (άρθρο 30)") }
        Spacer(Modifier.height(4.dp))
        Text(
            "Ποιος, πότε, ποιου πελάτη δεδομένα, ποια ενέργεια — ποτέ τιμές. Από εκεί " +
                "γίνεται και η εξαγωγή σε CSV και η εκκαθάριση.\n\n" +
                "Η εξαγωγή δεδομένων ενός πελάτη (φορητότητα, άρθρο 20) και η οριστική " +
                "διαγραφή του (άρθρο 17) γίνονται από την καρτέλα του· η μαζική " +
                "διαγραφή από τη λίστα πελατών.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("Google Drive", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        DriveSync.Mode.entries.forEach { mode ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (mode == driveMode) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == driveMode,
                        onClick = { driveMode = mode; settings.driveMode = mode },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }

        if (driveMode == DriveSync.Mode.SYNC) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Δομή φακέλων: ScanMyData Tax Center → Πελάτες → «ΑΦΜ — Επωνυμία» → έτος. " +
                    "Τα έντυπα ανεβαίνουν αυτόματα στο τέλος κάθε παρτίδας λήψης, " +
                    "εφόσον υπάρχει δίκτυο.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Το τοπικό αντίγραφο μένει: χωρίς αυτό η εφαρμογή δεν θα δούλευε χωρίς " +
                    "δίκτυο, και τα PDF στο Drive είναι ακρυπτογράφητα — δεν πρέπει να " +
                    "γίνουν το μοναδικό αντίγραφο φορολογικών εντύπων τρίτων.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    enabled = !syncBusy,
                    onClick = {
                        scope.launch {
                            syncBusy = true
                            syncStatus = "Συγχρονισμός…"
                            syncStatus = try {
                                if (!container.driveSync.online()) {
                                    "Δεν υπάρχει σύνδεση στο διαδίκτυο."
                                } else {
                                    val token = authorizer.accessToken()
                                    val result = container.driveSync.syncAll(token) { p ->
                                        syncStatus = "Ανέβηκαν ${p.uploaded}…"
                                    }
                                    "Ανέβηκαν ${result.uploaded}, ήδη συγχρονισμένα " +
                                        "${result.skipped}" +
                                        if (result.failed > 0) ", απέτυχαν ${result.failed}" else "."
                                }
                            } catch (e: Exception) {
                                "Απέτυχε: ${e.message}"
                            }
                            syncBusy = false
                        }
                    },
                ) { Text("Συγχρονισμός τώρα") }

                OutlinedButton(
                    enabled = !syncBusy,
                    onClick = {
                        scope.launch {
                            syncBusy = true
                            syncStatus = "Ανάκτηση αρχείων…"
                            syncStatus = try {
                                val token = authorizer.accessToken()
                                val count = container.driveSync.pullMissing(token)
                                "Κατέβηκαν $count αρχεία που έλειπαν."
                            } catch (e: Exception) {
                                "Απέτυχε: ${e.message}"
                            }
                            syncBusy = false
                        }
                    },
                ) { Text("Ανάκτηση σε νέα συσκευή") }
            }
            if (syncStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(syncStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Αντίγραφο ασφαλείας της βάσης", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Προαιρετικό. Η βάση κρυπτογραφείται **πριν** ανέβει, με κλειδί που " +
                "παράγεται από τη δική σου passphrase (PBKDF2-HMAC-SHA256, 210.000 " +
                "επαναλήψεις, AES-256-GCM). Η Google βλέπει μόνο κρυπτογράφημα.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Χαμένη passphrase = χαμένο αντίγραφο. Δεν υπάρχει ανάκτηση — αυτό " +
                "είναι το νόημα. Κράτησέ τη στον διαχειριστή κωδικών σου.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase αντιγράφου (τουλάχιστον 12 χαρακτήρες)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !backupBusy && passphrase.length >= 12,
                onClick = {
                    scope.launch {
                        backupBusy = true
                        backupStatus = "Δημιουργία και ανέβασμα…"
                        backupStatus = try {
                            val token = authorizer.accessToken()
                            val entry = withContext(Dispatchers.IO) {
                                container.driveBackup.upload(token, passphrase)
                            }
                            "Ανέβηκε: ${entry.name} (${entry.size / 1024} KB)."
                        } catch (e: Exception) {
                            "Απέτυχε: ${e.message}"
                        }
                        backupBusy = false
                    }
                },
            ) { Text("Δημιουργία αντιγράφου") }

            OutlinedButton(
                enabled = !backupBusy && passphrase.length >= 12,
                onClick = {
                    scope.launch {
                        backupBusy = true
                        backupStatus = "Αναζήτηση αντιγράφων…"
                        try {
                            val token = authorizer.accessToken()
                            backups = withContext(Dispatchers.IO) { container.driveBackup.list(token) }
                            backupStatus = if (backups.isEmpty()) {
                                "Δεν βρέθηκαν αντίγραφα."
                            } else {
                                "Βρέθηκαν ${backups.size} αντίγραφα."
                            }
                        } catch (e: Exception) {
                            backupStatus = "Απέτυχε: ${e.message}"
                        }
                        backupBusy = false
                    }
                },
            ) { Text("Επαναφορά…") }
        }

        backups.take(5).forEach { entry ->
            TextButton(onClick = { restoreTarget = entry }) {
                Text("${entry.name.removePrefix("taxcenter-backup-")} · ${entry.size / 1024} KB")
            }
        }

        if (backupStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(backupStatus, style = MaterialTheme.typography.bodyMedium)
        }

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

    restoreTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Επαναφορά αντιγράφου") },
            text = {
                Text(
                    "Θα αντικατασταθεί ΟΛΗ η τρέχουσα βάση με το αντίγραφο " +
                        "${entry.name}.\n\nΌ,τι έχει καταχωρηθεί μετά τη δημιουργία " +
                        "του χάνεται. Η εφαρμογή θα κλείσει αμέσως μετά — άνοιξέ την " +
                        "ξανά για να δεις τα δεδομένα.\n\nΗ ενέργεια δεν αναιρείται.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    restoreTarget = null
                    scope.launch {
                        backupStatus = "Επαναφορά…"
                        try {
                            val token = authorizer.accessToken()
                            withContext(Dispatchers.IO) {
                                container.driveBackup.restore(token, target, passphrase)
                            }
                            // Η Room κρατά ανοιχτό handle στο παλιό αρχείο· κάθε
                            // εγγραφή μετά την αντικατάσταση θα το κατέστρεφε.
                            exitProcess(0)
                        } catch (e: Exception) {
                            backupStatus = "Απέτυχε: ${e.message}"
                        }
                    }
                }) { Text("Επαναφορά") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Άκυρο") } },
        )
    }

    editingTemplate?.let { kind ->
        TemplateEditorDialog(
            kind = kind,
            store = templateStore,
            onDismiss = { editingTemplate = null },
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
