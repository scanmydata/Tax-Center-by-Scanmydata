package gr.scanmydata.taxcenter.ui

import android.Manifest
import android.os.Build
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.engine.ConfigInfo
import gr.scanmydata.taxcenter.engine.FetchController
import gr.scanmydata.taxcenter.engine.ProcessRunner
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Επιλογή ενέργειας × πελατών, και μετά η πρόοδος της παρτίδας.
 *
 * Η οθόνη **δεν κρατά** την εκτέλεση: τη δίνει στο [FetchController], που ζει
 * όσο η εφαρμογή. Ο λογιστής μπορεί να φύγει, να δει έναν πελάτη και να
 * επιστρέψει — η παρτίδα συνεχίζει.
 *
 * Το ορατό WebView εμφανίζεται μόνο όσο τρέχει διαδικασία που το χρειάζεται
 * (σήμερα `aade-enfia`). Είναι εκεί ακριβώς για να μπορεί ο χρήστης να λύσει
 * OTP ή CAPTCHA με το χέρι — **δεν παρακάμπτονται**.
 */
@Composable
fun FetchScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val controller = container.fetch
    val state by controller.state.collectAsState()

    if (state.idle) {
        FetchSelection(container, modifier)
    } else {
        FetchProgress(container, modifier)
    }
}

/** Τι κάνει η παρτίδα. Δύο ενέργειες, μία λίστα πελατών. */
private enum class Action(val label: String) {
    FETCH("Λήψη εντύπων"),
    CREDENTIALS("Αποστολή κωδικών στους πελάτες"),
}

// --------------------------------------------------------------- επιλογή

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FetchSelection(container: AppContainer, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())
    val configs: List<ConfigInfo> = remember { container.assets.catalog() }

    var action by remember { mutableStateOf(Action.FETCH) }
    var query by remember { mutableStateOf("") }
    val pickedClients = remember { mutableStateListOf<Long>() }
    val pickedConfigs = remember { mutableStateListOf<String>() }
    var includeSecrets by remember { mutableStateOf(container.settings.includePasswordsInClientEmail) }
    var confirmSend by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var year by remember {
        // Προεπιλογή το προηγούμενο έτος: τον περισσότερο χρόνο ο λογιστής
        // κατεβάζει τα έντυπα της χρήσης που δηλώνεται τώρα.
        mutableStateOf((Calendar.getInstance().get(Calendar.YEAR) - 1).toString())
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val shown = remember(clients, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) clients
        else clients.filter { it.afm.contains(q) || it.displayName.lowercase().contains(q) }
    }
    val selected = remember(clients, pickedClients.toList()) {
        val byId = clients.associateBy { it.id }
        pickedClients.mapNotNull { byId[it] }
    }
    val needsYear = configs.any { it.id in pickedConfigs && FetchController.acceptsYear(it) }
    val jobCount = pickedClients.size * pickedConfigs.size
    val recipients = selected.filter { it.effectiveEmail.isNotBlank() }

    val ready = when (action) {
        Action.FETCH -> jobCount > 0
        Action.CREDENTIALS -> recipients.isNotEmpty()
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f)) {

            item {
                Spacer(Modifier.height(12.dp))
                PickerDropdown(
                    label = "Ενέργεια",
                    text = action.label,
                ) { dismiss ->
                    Action.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                action = option
                                dismiss()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (action == Action.FETCH) {
                item {
                    // Dropdown αντί για λίστα με 19 checkbox: ο κατάλογος
                    // μεγαλώνει με κάθε νέα πύλη, και έσπρωχνε τους πελάτες —
                    // το μισό της δουλειάς — κάτω από τη μέση της οθόνης.
                    PickerDropdown(
                        label = "Έντυπα",
                        text = when (pickedConfigs.size) {
                            0 -> "— διάλεξε —"
                            1 -> configs.firstOrNull { it.id == pickedConfigs.first() }
                                ?.title.orEmpty().ifBlank { pickedConfigs.first() }
                            else -> "${pickedConfigs.size} επιλεγμένα"
                        },
                    ) {
                        configs.forEach { cfg ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Checkbox(
                                        checked = cfg.id in pickedConfigs,
                                        onCheckedChange = null,
                                    )
                                },
                                text = {
                                    Column {
                                        Text(cfg.title.ifBlank { cfg.id })
                                        Text(
                                            buildString {
                                                append(cfg.portal)
                                                if (cfg.needsBrowser) {
                                                    append("  ·  χρειάζεται ορατό browser")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                        )
                                    }
                                },
                                // Το μενού μένει ανοιχτό: σχεδόν πάντα
                                // επιλέγονται περισσότερα από ένα έντυπα.
                                onClick = {
                                    if (cfg.id in pickedConfigs) pickedConfigs.remove(cfg.id)
                                    else pickedConfigs.add(cfg.id)
                                },
                            )
                        }
                    }

                    if (pickedConfigs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        // FlowRow και όχι Row: με έξι-εφτά επιλεγμένα έντυπα τα
                        // chips βγαίνουν εκτός οθόνης και δεν φαίνεται τι έχεις
                        // διαλέξει — που είναι όλο το νόημά τους.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pickedConfigs.toList().forEach { id ->
                                AssistChip(
                                    onClick = { pickedConfigs.remove(id) },
                                    label = {
                                        Text(
                                            configs.firstOrNull { it.id == id }?.title.orEmpty()
                                                .ifBlank { id }
                                                .take(28),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    if (needsYear) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = year,
                            onValueChange = { year = it.filter(Char::isDigit).take(4) },
                            label = { Text("Έτος") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Οι διαδικασίες τρέχουν αυστηρά μία-μία: το GSIS κλείνει τον " +
                            "λογαριασμό όταν ανοίξουν πολλές συνεδρίες ταυτόχρονα.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            } else {
                item { CredentialsOptions(includeSecrets) { includeSecrets = it } }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Πελάτες (${pickedClients.size}/${clients.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (pickedClients.size == shown.size) {
                            pickedClients.clear()
                        } else {
                            pickedClients.clear()
                            pickedClients.addAll(shown.map { it.id })
                        }
                    }) {
                        Text(if (pickedClients.size == shown.size && shown.isNotEmpty()) "Κανένας" else "Όλοι")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Αναζήτηση σε ΑΦΜ ή επωνυμία") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }

            items(shown, key = { it.id }) { client ->
                SelectableRow(
                    checked = client.id in pickedClients,
                    title = client.displayName,
                    subtitle = buildString {
                        append(client.afm)
                        if (!client.active) append("  ·  ανενεργός")
                        if (client.effectiveEmail.isBlank()) append("  ·  χωρίς email")
                    },
                    onToggle = {
                        if (client.id in pickedClients) pickedClients.remove(client.id)
                        else pickedClients.add(client.id)
                    },
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    action == Action.CREDENTIALS && recipients.isEmpty() ->
                        "Διάλεξε πελάτες με διεύθυνση email"
                    action == Action.CREDENTIALS ->
                        "${recipients.size} παραλήπτες" +
                            (selected.size - recipients.size).let {
                                if (it > 0) " · $it χωρίς email" else ""
                            }
                    jobCount == 0 -> "Διάλεξε έντυπα και πελάτες"
                    else -> "$jobCount εκτελέσεις"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = ready,
                onClick = {
                    when (action) {
                        Action.CREDENTIALS -> confirmSend = true
                        Action.FETCH -> {
                            val extras = if (needsYear && year.isNotBlank()) {
                                mapOf("year" to year)
                            } else {
                                emptyMap()
                            }
                            val jobs = selected.flatMap { client ->
                                pickedConfigs.map { configId ->
                                    ProcessRunner.Job(
                                        client = client,
                                        configId = configId,
                                        extraInputs = extras,
                                    )
                                }
                            }
                            container.fetch.start(jobs)
                        }
                    }
                },
            ) { Text(if (action == Action.CREDENTIALS) "Αποστολή" else "Έναρξη") }
        }
    }

    if (confirmSend) {
        BulkCredentialsDialog(
            recipients = recipients,
            includeSecrets = includeSecrets,
            onDismiss = { confirmSend = false },
            onConfirm = {
                confirmSend = false
                scope.launch {
                    var sent = 0
                    val failures = ArrayList<String>()
                    status = "Αποστολή…"
                    try {
                        val token = authorizer.accessToken()
                        for (client in recipients) {
                            status = "Αποστολή ${sent + 1}/${recipients.size}: ${client.displayName}…"
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    container.mail.sendOwnDetails(token, client, includeSecrets)
                                }
                            }
                            val entry = result.getOrNull()
                            when {
                                result.isFailure ->
                                    failures += "${client.displayName}: ${result.exceptionOrNull()?.message}"
                                entry != null && entry.failed ->
                                    failures += "${client.displayName}: ${entry.error}"
                                else -> sent++
                            }
                        }
                        status = buildString {
                            append("Στάλθηκαν $sent από ${recipients.size}.")
                            if (failures.isNotEmpty()) {
                                append("\nΑπέτυχαν:\n")
                                append(failures.joinToString("\n"))
                            }
                        }
                    } catch (e: GoogleAuthorizer.ConsentRequired) {
                        status = "Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις."
                    } catch (e: Exception) {
                        status = "Απέτυχε: ${e.message}"
                    }
                }
            },
        )
    }
}

/** Οι επιλογές της αποστολής κωδικών, με την προειδοποίηση μπροστά. */
@Composable
private fun CredentialsOptions(includeSecrets: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Σε κάθε πελάτη στέλνεται **ένα ξεχωριστό** μήνυμα με τα δικά του " +
                    "στοιχεία: ΑΦΜ, ΑΜΚΑ και όνομα χρήστη TAXISnet. Κανείς δεν " +
                    "βλέπει τα στοιχεία κανενός άλλου.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = includeSecrets, onCheckedChange = onChange)
                Text(
                    "  Συνθηματικό και κλειδάριθμος",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (includeSecrets) {
                Text(
                    "Το email δεν είναι ασφαλές κανάλι: περνά από servers τρίτων και " +
                        "μένει στο γραμματοκιβώτιο του πελάτη για χρόνια. Σε μαζική " +
                        "αποστολή ο κίνδυνος πολλαπλασιάζεται. Το μήνυμα προτρέπει " +
                        "τον παραλήπτη να το διαγράψει.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Επιβεβαίωση μαζικής αποστολής, με τους παραλήπτες **ονομαστικά**.
 *
 * Ένα «αποστολή σε 42 πελάτες» δεν επιτρέπει να προσέξεις ότι ο ένας από τους
 * 42 δεν έπρεπε να είναι εκεί. Η λίστα το επιτρέπει.
 */
@Composable
private fun BulkCredentialsDialog(
    recipients: List<ClientEntity>,
    includeSecrets: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Αποστολή σε ${recipients.size} πελάτες") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (includeSecrets) {
                        "Θα σταλούν ΑΦΜ, ΑΜΚΑ, όνομα χρήστη TAXISnet, συνθηματικό " +
                            "και κλειδάριθμος."
                    } else {
                        "Θα σταλούν ΑΦΜ, ΑΜΚΑ και όνομα χρήστη TAXISnet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                recipients.forEach { client ->
                    Text(
                        "• ${client.displayName} — ${client.effectiveEmail}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Αποστολή") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}

/** Πεδίο-μενού: κλειστό κείμενο που ανοίγει λίστα επιλογών. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerDropdown(
    label: String,
    text: String,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = text,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                content { expanded = false }
            }
        }
    }
}

@Composable
private fun SelectableRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

// --------------------------------------------------------------- πρόοδος

@Composable
private fun FetchProgress(container: AppContainer, modifier: Modifier) {
    val controller = container.fetch
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var reviewing by remember { mutableStateOf(false) }

    Column(modifier.padding(16.dp)) {

        Text(
            if (state.running) "${state.done}/${state.total} ολοκληρώθηκαν"
            else "Τέλος — ${state.total - state.failed}/${state.total} επιτυχίες",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.browserActive) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Η διαδικασία χρησιμοποιεί πραγματικό browser. Αν ζητηθεί κωδικός " +
                    "μιας χρήσης ή CAPTCHA, συμπλήρωσέ το εδώ — η εφαρμογή δεν τα παρακάμπτει.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(8.dp))
            BrowserPanel(container)
        }

        if (!state.running && state.pending.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${state.pendingChanges} αλλαγές σε ${state.pending.size} καρτέλες",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Η άντληση βρήκε στοιχεία που διαφέρουν από τα αποθηκευμένα. " +
                            "Τίποτα δεν έχει γραφτεί ακόμη.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { reviewing = true }) { Text("Έλεγχος και αποθήκευση") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.items, key = { it.key }) { item ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${item.clientName} — ${item.configTitle}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when (item.status) {
                                FetchController.Status.PENDING -> "σε αναμονή"
                                FetchController.Status.RUNNING -> "εκτελείται…"
                                FetchController.Status.OK -> "✓ ${item.fileCount} έντυπα"
                                FetchController.Status.FAILED -> "✗ ${item.detail}"
                                FetchController.Status.CANCELLED -> "διακόπηκε"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == FetchController.Status.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.running) {
                OutlinedButton(onClick = { controller.cancel() }) { Text("Διακοπή") }
            } else {
                Button(
                    enabled = state.pending.isEmpty(),
                    onClick = { controller.clear() },
                ) { Text("Νέα λήψη") }
                if (state.failed > 0) {
                    OutlinedButton(onClick = { controller.retryFailed() }) {
                        Text("Επανάληψη ${state.failed} αποτυχιών")
                    }
                }
            }
        }
    }

    if (reviewing) {
        PendingUpdatesDialog(
            pending = state.pending,
            onDismiss = { reviewing = false },
            onDiscard = {
                reviewing = false
                controller.discardPending()
            },
            onApply = { approved ->
                reviewing = false
                scope.launch { controller.applyPending(approved) }
            },
        )
    }
}

/**
 * Η οθόνη έγκρισης: «πριν → μετά» ανά πεδίο, με διακόπτη ανά αλλαγή.
 *
 * Ξεκινά με όλα τσεκαρισμένα — η συνηθισμένη περίπτωση είναι ότι το Μητρώο
 * έχει δίκιο. Αλλά όχι πάντα: ένα ονοματεπώνυμο που ο λογιστής διόρθωσε στο
 * χέρι, ή μια διεύθυνση που ο πελάτης δεν έχει ενημερώσει στην ΑΑΔΕ, πρέπει να
 * μπορούν να μείνουν ως έχουν.
 */
@Composable
private fun PendingUpdatesDialog(
    pending: List<FetchController.PendingUpdate>,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    val approved = remember(pending) {
        mutableStateListOf<String>().apply {
            addAll(pending.flatMap { u -> u.changes.map { "${u.clientId}/${it.field.name}" } })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ενημέρωση καρτελών") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                pending.forEach { update ->
                    Text(
                        "${update.clientName} (${update.afm})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    update.changes.forEach { change ->
                        val key = "${update.clientId}/${change.field.name}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = key in approved,
                                onCheckedChange = {
                                    if (key in approved) approved.remove(key) else approved.add(key)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(change.field.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (change.before.isBlank()) {
                                        "(κενό) → ${change.after}"
                                    } else {
                                        "${change.before} → ${change.after}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = approved.isNotEmpty(),
                onClick = { onApply(approved.toSet()) },
            ) { Text("Αποθήκευση ${approved.size}") }
        },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Απόρριψη όλων") } },
    )
}

/**
 * Το δοχείο του ορατού WebView.
 *
 * Το ίδιο το WebView το φτιάχνει και το κατέχει το `WebViewBrowserPage` — εδώ
 * δίνεται μόνο πού θα μπει. Στο `onDispose` το δοχείο αποσυνδέεται, ώστε ένα
 * WebView που ζει πιο πολύ από την οθόνη να μη μείνει με νεκρό parent.
 */
@Composable
private fun BrowserPanel(container: AppContainer) {
    val controller = container.fetch
    AndroidView(
        factory = { ctx -> FrameLayout(ctx).also { controller.browserContainer = it } },
        modifier = Modifier.fillMaxWidth().height(420.dp),
    )
    DisposableEffect(Unit) {
        onDispose { controller.browserContainer = null }
    }
}
