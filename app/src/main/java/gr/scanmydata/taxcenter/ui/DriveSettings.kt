package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.KeyStoreKeys
import gr.scanmydata.taxcenter.google.DriveBackup
import gr.scanmydata.taxcenter.google.DriveRun
import gr.scanmydata.taxcenter.google.DriveSync
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.sched.DriveWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * Η ομάδα «Google Drive» των Ρυθμίσεων.
 *
 * ## Γιατί ξαναγράφτηκε
 *
 * Η επιλογή «Μόνο αντίγραφο ασφαλείας» **δεν έκανε τίποτα μόνη της**: ήταν η
 * προεπιλογή, δεν ζητούσε άδεια από την Google, και το αντίγραφο γινόταν μόνο
 * αν ο χρήστης έγραφε passphrase και πατούσε κουμπί — όπου ανέβαινε στη
 * **ρίζα** του Drive και όχι στον φάκελο. Ο χρήστης έβλεπε «ενεργό» και στο
 * Drive δεν υπήρχε τίποτα.
 *
 * Τώρα η επιλογή είναι **πράξη**: ζητά passphrase (μία φορά), ανοίγει την
 * άδεια της Google, φτιάχνει τον φάκελο, ανεβάζει το πρώτο αντίγραφο και
 * βάζει το καθημερινό στο πρόγραμμα. Αν κάτι από αυτά δεν γίνει, η λειτουργία
 * **δεν** αλλάζει — η οθόνη δεν λέει ποτέ «ενεργό» για κάτι που δεν έγινε.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DriveSettings(container: AppContainer, authorizer: GoogleAuthorizer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settings
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    var mode by remember { mutableStateOf(settings.driveMode) }
    var ready by remember { mutableStateOf(settings.driveReady) }
    var folder by remember { mutableStateOf(settings.driveFolderPath) }
    var hasPassphrase by remember { mutableStateOf(KeyStoreKeys.backupPassphrase(context) != null) }
    // Αλλάζει όταν τελειώνει μια εκτέλεση, για να ξαναδιαβαστεί η κάρτα κατάστασης.
    var tick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    // Το παράθυρο αναμονής: τίτλος, βήματα, και ποιο τρέχει.
    var workTitle by remember { mutableStateOf("") }
    var workSteps by remember { mutableStateOf(emptyList<BusyStep>()) }
    var workText by remember { mutableStateOf("") }

    var askPassphrase by remember { mutableStateOf<DriveSync.Mode?>(null) }
    var changePassphrase by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<DriveBackup.Entry>?>(null) }
    var restoreTarget by remember { mutableStateOf<DriveBackup.Entry?>(null) }

    fun steps(labels: List<String>, current: Int, failedAt: Int = -1) = labels.mapIndexed { i, label ->
        BusyStep(
            label,
            when {
                i == failedAt -> BusyStep.State.FAILED
                i < current -> BusyStep.State.DONE
                i == current -> BusyStep.State.RUNNING
                else -> BusyStep.State.WAITING
            },
        )
    }

    /**
     * Άδεια Google → φάκελος → αντίγραφο → (αρχεία). Η λειτουργία γράφεται
     * **μόνο** αν πέτυχαν άδεια και φάκελος.
     */
    fun runDrive(target: DriveSync.Mode, title: String, stages: Set<DriveRun.Stage>, activate: Boolean) {
        scope.launch {
            val labels = listOf("Άδεια από την Google") + stages.sortedBy { it.ordinal }.map { it.label }
            workTitle = title
            workSteps = steps(labels, 0)
            workText = "Αν ανοίξει παράθυρο της Google, διάλεξε τον λογαριασμό και πάτα «Συνέχεια»."
            message = ""
            try {
                val token = try {
                    authorizer.accessToken()
                } catch (e: GoogleAuthorizer.ConsentDeclined) {
                    throw IllegalStateException("δεν δόθηκε άδεια από την Google — τίποτα δεν άλλαξε")
                }
                val ordered = stages.sortedBy { it.ordinal }
                val report = DriveRun(context, container.db, settings, container.driveBackup, container.driveSync)
                    .run(token, stages) { stage, note ->
                        workSteps = steps(labels, 1 + ordered.indexOf(stage))
                        workText = note
                    }
                if (DriveRun.Stage.FOLDER in report.errors) {
                    throw IllegalStateException(report.errors.getValue(DriveRun.Stage.FOLDER))
                }
                if (activate) {
                    settings.driveMode = target
                    settings.driveReady = true
                    mode = target
                    ready = true
                }
                DriveWork.apply(context, settings)
                failed = !report.ok
                message = if (report.ok) {
                    "Έτοιμο: " + report.summary() + ". Φάκελος «${container.driveSync.folderPath}» στον Drive σου."
                } else {
                    "Έγινε εν μέρει: " + report.summary() + "."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                message = "Απέτυχε: ${e.message}"
            } finally {
                workTitle = ""
                tick++
            }
        }
    }

    fun choose(target: DriveSync.Mode) {
        if (target == mode && (target == DriveSync.Mode.OFF || ready)) return
        if (target == DriveSync.Mode.OFF) {
            settings.driveMode = DriveSync.Mode.OFF
            mode = DriveSync.Mode.OFF
            DriveWork.apply(context, settings)
            failed = false
            message = "Ανενεργό: τίποτα δεν ανεβαίνει πια. Ό,τι έχει ήδη ανέβει μένει στο Drive σου."
            return
        }
        if (!hasPassphrase) {
            askPassphrase = target
            return
        }
        val stages = buildSet {
            add(DriveRun.Stage.FOLDER)
            add(DriveRun.Stage.BACKUP)
            if (target == DriveSync.Mode.SYNC) add(DriveRun.Stage.FILES)
        }
        runDrive(target, "Ενεργοποίηση: " + target.label, stages, activate = true)
    }

    // ------------------------------------------------------------ κατάσταση
    val active = mode != DriveSync.Mode.OFF
    StatusCard(
        mode = mode,
        ready = ready,
        folder = container.driveSync.folderPath,
        tick = tick,
        settings = settings,
    )

    Spacer(Modifier.height(12.dp))
    Text("Τι ανεβαίνει", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    DriveSync.Mode.entries.forEach { option ->
        val selected = option == mode
        Card(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { choose(option) },
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = { choose(option) })
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                    Text(option.description, style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
        }
    }

    if (message.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }

    if (active) {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Φάκελος στο Drive", style = MaterialTheme.typography.titleSmall)
        Text(
            "Η διαδρομή μέσα στον Drive σου, με «/» για υποφακέλους — π.χ. «Γραφείο/TaxCenter». " +
                "Ό,τι λείπει δημιουργείται. Από κάτω μπαίνουν οι φάκελοι «Αντίγραφα» και «Πελάτες».",
            style = MaterialTheme.typography.bodySmall,
            color = dim,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            label = { Text("Φάκελος") },
            singleLine = true,
            supportingText = { Text("Θα γίνει: " + DriveSync.normalisePath(folder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (DriveSync.normalisePath(folder) != container.driveSync.folderPath) {
            Button(onClick = {
                settings.driveFolderPath = folder
                folder = settings.driveFolderPath
                runDrive(mode, "Αλλαγή φακέλου", setOf(DriveRun.Stage.FOLDER), activate = false)
            }) { Text("Αποθήκευση και δημιουργία") }
            Text(
                "Ό,τι έχει ήδη ανέβει μένει στον παλιό φάκελο — η εφαρμογή δεν μετακινεί αρχεία.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                if (!hasPassphrase) {
                    askPassphrase = mode
                } else {
                    runDrive(
                        mode,
                        if (ready) "Αντίγραφο τώρα" else "Στήσιμο του Drive",
                        buildSet {
                            add(DriveRun.Stage.FOLDER)
                            add(DriveRun.Stage.BACKUP)
                            if (mode == DriveSync.Mode.SYNC) add(DriveRun.Stage.FILES)
                        },
                        activate = !ready,
                    )
                }
            }) { Text(if (ready) "Αντίγραφο τώρα" else "Στήσιμο τώρα") }
            if (mode == DriveSync.Mode.SYNC && ready) {
                OutlinedButton(onClick = {
                    runDrive(mode, "Ανέβασμα αρχείων πελατών", setOf(DriveRun.Stage.FILES), activate = false)
                }) { Text("Ανέβασμα αρχείων τώρα") }
            }
            OutlinedButton(onClick = { changePassphrase = true }) {
                Text(if (hasPassphrase) "Αλλαγή passphrase" else "Ορισμός passphrase")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Η passphrase κρυπτογραφεί το αντίγραφο πριν φύγει από τη συσκευή (AES-256-GCM, " +
                "κλειδί από PBKDF2 με 210.000 επαναλήψεις). Φυλάγεται κρυπτογραφημένη στη " +
                "συσκευή για να γίνεται το αντίγραφο μόνο του, και δεν ανεβαίνει ποτέ. " +
                "Κράτησέ τη και στον διαχειριστή κωδικών σου: σε νέα συσκευή θα τη χρειαστείς, " +
                "και χωρίς αυτήν το αντίγραφο δεν ανοίγει — ούτε από εμάς ούτε από την Google.",
            style = MaterialTheme.typography.bodySmall,
            color = dim,
        )
        if (mode == DriveSync.Mode.SYNC) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Τα έντυπα, οι οφειλές (Οφειλές.txt) και τα αιτήματα με τα συνημμένα τους είναι " +
                    "αναγνώσιμα στο Drive, όπως και ό,τι στέλνεις με email. Το τοπικό αντίγραφο " +
                    "μένει: η εφαρμογή δουλεύει και χωρίς δίκτυο.",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Νέα συσκευή", style = MaterialTheme.typography.titleSmall)
    Text(
        "Η επαναφορά αντικαθιστά τη βάση με ένα αντίγραφο από το Drive. Μετά, με τη λειτουργία " +
            "«αρχεία πελατών», η «Ανάκτηση αρχείων» κατεβάζει τα έντυπα και τα συνημμένα που λείπουν.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                workTitle = "Αναζήτηση αντιγράφων"
                workSteps = emptyList()
                workText = "Σύνδεση με Google και ανάγνωση των αντιγράφων της εφαρμογής…"
                try {
                    val token = authorizer.accessToken()
                    backups = withContext(Dispatchers.IO) { container.driveBackup.list(token) }
                    if (backups.isNullOrEmpty()) {
                        failed = true
                        message = "Δεν βρέθηκαν αντίγραφα στο Drive αυτού του λογαριασμού."
                        backups = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    message = "Απέτυχε: ${e.message}"
                } finally {
                    workTitle = ""
                }
            }
        }) { Text("Επαναφορά από αντίγραφο…") }
        if (mode == DriveSync.Mode.SYNC) {
            OutlinedButton(onClick = {
                scope.launch {
                    workTitle = "Ανάκτηση αρχείων"
                    workSteps = emptyList()
                    workText = "Λήψη από το Drive όσων αρχείων λείπουν από τη συσκευή…"
                    try {
                        val token = authorizer.accessToken()
                        val count = container.driveSync.pullMissing(token)
                        failed = false
                        message = "Κατέβηκαν $count αρχεία που έλειπαν."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        message = "Απέτυχε: ${e.message}"
                    } finally {
                        workTitle = ""
                    }
                }
            }) { Text("Ανάκτηση αρχείων") }
        }
    }

    // ------------------------------------------------------------ διάλογοι
    BusyOverlay(
        visible = workTitle.isNotBlank(),
        title = workTitle,
        text = workText,
        steps = workSteps,
        detail = "Μην κλείσεις την εφαρμογή μέχρι να τελειώσει.",
    )

    askPassphrase?.let { target ->
        PassphraseDialog(
            title = "Passphrase αντιγράφου",
            intro = "Πριν από το πρώτο αντίγραφο: διάλεξε passphrase τουλάχιστον 12 χαρακτήρων. " +
                "Με αυτήν κρυπτογραφείται το αντίγραφο πριν ανέβει.",
            onDismiss = { askPassphrase = null },
            onSave = { value ->
                KeyStoreKeys.setBackupPassphrase(context, value)
                hasPassphrase = true
                askPassphrase = null
                val stages = buildSet {
                    add(DriveRun.Stage.FOLDER)
                    add(DriveRun.Stage.BACKUP)
                    if (target == DriveSync.Mode.SYNC) add(DriveRun.Stage.FILES)
                }
                runDrive(target, "Ενεργοποίηση: " + target.label, stages, activate = true)
            },
        )
    }

    if (changePassphrase) {
        PassphraseDialog(
            title = if (hasPassphrase) "Νέα passphrase" else "Passphrase αντιγράφου",
            intro = "Τα επόμενα αντίγραφα κρυπτογραφούνται με τη νέα. Τα παλιά στο Drive ανοίγουν " +
                "μόνο με εκείνη που είχε τότε — κράτησε και τις δύο.",
            onDismiss = { changePassphrase = false },
            onSave = { value ->
                KeyStoreKeys.setBackupPassphrase(context, value)
                hasPassphrase = true
                changePassphrase = false
                failed = false
                message = "Η passphrase άλλαξε. Το επόμενο αντίγραφο θα τη χρησιμοποιήσει."
            },
        )
    }

    backups?.let { list ->
        AlertDialog(
            onDismissRequest = { backups = null },
            title = { Text("Διάλεξε αντίγραφο") },
            text = {
                Column {
                    list.take(14).forEach { entry ->
                        TextButton(onClick = { restoreTarget = entry; backups = null }) {
                            Text(backupLabel(entry))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { backups = null }) { Text("Άκυρο") } },
        )
    }

    restoreTarget?.let { entry ->
        var typed by remember(entry.id) { mutableStateOf(KeyStoreKeys.backupPassphrase(context).orEmpty()) }
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Επαναφορά αντιγράφου") },
            text = {
                Column {
                    Text(
                        "Θα αντικατασταθεί ΟΛΗ η τρέχουσα βάση με το αντίγραφο της " +
                            backupLabel(entry) + ".\n\nΌ,τι καταχωρήθηκε μετά χάνεται. Η εφαρμογή " +
                            "κλείνει αμέσως μετά — άνοιξέ την ξανά.\n\nΗ ενέργεια δεν αναιρείται.",
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Passphrase του αντιγράφου") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = typed.length >= 12, tone = Tone.DELETE, onClick = {
                    val target = entry
                    val passphrase = typed
                    restoreTarget = null
                    scope.launch {
                        workTitle = "Επαναφορά αντιγράφου"
                        workSteps = emptyList()
                        workText = "Λήψη, αποκρυπτογράφηση και αντικατάσταση της βάσης…"
                        try {
                            val token = authorizer.accessToken()
                            withContext(Dispatchers.IO) {
                                container.driveBackup.restore(token, target, passphrase)
                            }
                            // Σε νέα συσκευή, η passphrase που δούλεψε μένει για τα
                            // επόμενα αυτόματα αντίγραφα.
                            if (KeyStoreKeys.backupPassphrase(context) == null) {
                                KeyStoreKeys.setBackupPassphrase(context, passphrase)
                            }
                            // Η Room κρατά ανοιχτό handle στο παλιό αρχείο· κάθε
                            // εγγραφή μετά την αντικατάσταση θα το κατέστρεφε.
                            exitProcess(0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = true
                            message = "Απέτυχε: ${e.message}"
                            workTitle = ""
                        }
                    }
                }) { Text("Επαναφορά") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Άκυρο") } },
        )
    }
}

/** Τι ισχύει τώρα: λειτουργία, φάκελος, τελευταίο αντίγραφο, τελευταία εκτέλεση. */
@Composable
private fun StatusCard(
    mode: DriveSync.Mode,
    ready: Boolean,
    folder: String,
    tick: Int,
    settings: gr.scanmydata.taxcenter.data.Settings,
) {
    val lastBackup = remember(tick) { settings.driveLastBackup }
    val lastRun = remember(tick) { settings.driveLastRun }
    val summary = remember(tick) { settings.driveLastSummary }
    val lastFailed = remember(tick) { settings.driveLastFailed }
    val warn = mode != DriveSync.Mode.OFF && (!ready || lastFailed)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                mode == DriveSync.Mode.OFF -> MaterialTheme.colorScheme.surfaceVariant
                warn -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    mode == DriveSync.Mode.OFF -> "Τίποτα δεν ανεβαίνει στο Drive"
                    !ready -> "Δεν έχει στηθεί ακόμη — δεν έχει ανέβει τίποτα"
                    else -> "Ενεργό: " + mode.label.lowercase()
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (mode != DriveSync.Mode.OFF) {
                Spacer(Modifier.height(4.dp))
                Text("Φάκελος: $folder/${DriveSync.BACKUPS}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Τελευταίο αντίγραφο: " + if (lastBackup == 0L) "κανένα" else AthensDates.stamp(lastBackup),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (lastRun != 0L) {
                    Text(
                        "Τελευταία εκτέλεση " + AthensDates.stamp(lastRun) + ": " + summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lastFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    if (ready) {
                        "Αυτόματα μία φορά την ημέρα, περίπου στις 02:00, όταν υπάρχει δίκτυο."
                    } else {
                        "Πάτα «Στήσιμο τώρα»: θα ζητηθεί άδεια από την Google και θα ανέβει το πρώτο αντίγραφο."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    intro: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val long = first.length >= 12
    val same = first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("Passphrase (12+ χαρακτήρες)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text("Ξανά, για επιβεβαίωση") },
                    singleLine = true,
                    isError = second.isNotEmpty() && !same,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Χαμένη passphrase = χαμένο αντίγραφο. Κράτησέ τη στον διαχειριστή κωδικών σου.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = long && same, onClick = { onSave(first) }) { Text("Αποθήκευση") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}

/** «15/10/2026 02:04 · 1.240 KB» — από το όνομα, που έχει τη χρονοσφραγίδα. */
private fun backupLabel(entry: DriveBackup.Entry): String {
    val millis = entry.name.removePrefix("taxcenter-backup-").removeSuffix(".smdbk").toLongOrNull()
    val date = if (millis != null) AthensDates.stamp(millis) else entry.createdTime
    return "$date · ${entry.size / 1024} KB"
}
