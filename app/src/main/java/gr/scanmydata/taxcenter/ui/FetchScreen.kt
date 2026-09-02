package gr.scanmydata.taxcenter.ui

import android.Manifest
import android.os.Build
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.engine.ConfigInfo
import gr.scanmydata.taxcenter.engine.FetchController
import gr.scanmydata.taxcenter.engine.ProcessRunner
import java.util.Calendar

/**
 * Επιλογή πελατών × εντύπων × έτους, και μετά η πρόοδος της παρτίδας.
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

// --------------------------------------------------------------- επιλογή

@Composable
private fun FetchSelection(container: AppContainer, modifier: Modifier) {
    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())
    val configs: List<ConfigInfo> = remember { container.assets.catalog() }

    var query by remember { mutableStateOf("") }
    val pickedClients = remember { mutableStateListOf<Long>() }
    val pickedConfigs = remember { mutableStateListOf<String>() }
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
    val needsYear = configs.any { it.id in pickedConfigs && FetchController.acceptsYear(it) }
    val jobCount = pickedClients.size * pickedConfigs.size

    Column(modifier.padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f)) {

            item {
                Spacer(Modifier.height(12.dp))
                Text("Έντυπα", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Οι διαδικασίες τρέχουν αυστηρά μία-μία: το GSIS κλείνει τον " +
                        "λογαριασμό όταν ανοίξουν πολλές συνεδρίες ταυτόχρονα.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(6.dp))
            }

            items(configs, key = { it.id }) { cfg ->
                SelectableRow(
                    checked = cfg.id in pickedConfigs,
                    title = cfg.title.ifBlank { cfg.id },
                    subtitle = buildString {
                        append(cfg.portal)
                        if (cfg.needsBrowser) append("  ·  χρειάζεται ορατό browser")
                    },
                    onToggle = {
                        if (cfg.id in pickedConfigs) pickedConfigs.remove(cfg.id)
                        else pickedConfigs.add(cfg.id)
                    },
                )
            }

            item {
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

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (jobCount == 0) "Διάλεξε έντυπα και πελάτες"
                else "$jobCount εκτελέσεις",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = jobCount > 0,
                onClick = {
                    val byId = clients.associateBy { it.id }
                    val extras = if (needsYear && year.isNotBlank()) mapOf("year" to year) else emptyMap()
                    val jobs = pickedClients.mapNotNull { byId[it] }.flatMap { client ->
                        pickedConfigs.map { configId ->
                            ProcessRunner.Job(client = client, configId = configId, extraInputs = extras)
                        }
                    }
                    container.fetch.start(jobs)
                },
            ) { Text("Έναρξη") }
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
                Button(onClick = { controller.clear() }) { Text("Νέα λήψη") }
                if (state.failed > 0) {
                    OutlinedButton(onClick = { controller.retryFailed() }) {
                        Text("Επανάληψη ${state.failed} αποτυχιών")
                    }
                }
            }
        }
    }
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
