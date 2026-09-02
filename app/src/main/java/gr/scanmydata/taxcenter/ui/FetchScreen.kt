package gr.scanmydata.taxcenter.ui

import android.Manifest
import android.os.Build
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import gr.scanmydata.taxcenter.engine.DocumentCatalog
import gr.scanmydata.taxcenter.engine.FetchController
import gr.scanmydata.taxcenter.engine.ProcessRunner
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Επιλογή εντύπων × πελατών, και μετά η πρόοδος της παρτίδας.
 *
 * Η οθόνη **δεν κρατά** την εκτέλεση: τη δίνει στο [FetchController], που ζει
 * όσο η εφαρμογή. Ο λογιστής μπορεί να φύγει, να δει έναν πελάτη και να
 * επιστρέψει — η παρτίδα συνεχίζει.
 *
 * Το ορατό WebView εμφανίζεται μόνο όσο τρέχει διαδικασία που το χρειάζεται
 * (σήμερα ΕΝΦΙΑ/Ε9). Είναι εκεί ακριβώς για να μπορεί ο χρήστης να λύσει OTP ή
 * CAPTCHA με το χέρι — **δεν παρακάμπτονται**.
 */
@Composable
fun FetchScreen(
    container: AppContainer,
    preselectedClient: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val controller = container.fetch
    val state by controller.state.collectAsState()

    if (state.idle) {
        FetchSelection(container, preselectedClient, modifier)
    } else {
        FetchProgress(container, modifier)
    }
}

/** Τι κάνει η παρτίδα. Δύο ενέργειες, μία λίστα πελατών. */
private enum class Action(val label: String) {
    FETCH("Λήψη εντύπων"),
    CREDENTIALS("Αποστολή κωδικών στους πελάτες"),
}

/**
 * Μία επιλογή εντύπου, **με δικές της παραμέτρους**.
 *
 * Το έτος ανήκει εδώ και όχι στην παρτίδα. «Ε1 του 2025 και Ε9 του 2027 μαζί»
 * είναι καθημερινό αίτημα — με ένα κοινό πεδίο έτους χρειαζόταν δύο χωριστές
 * εκτελέσεις, δηλαδή δύο συνδέσεις στο GSIS ανά πελάτη.
 *
 * Το [uid] υπάρχει για να μπορεί το ίδιο έντυπο να μπει δύο φορές με άλλο έτος.
 */
private data class Pick(
    val uid: Long,
    val itemId: String,
    /** Πολλαπλά έτη: «Ε1 για 2023, 2024 και 2025» είναι μία επιλογή, τρεις λήψεις. */
    val years: List<String> = emptyList(),
    val months: List<String> = emptyList(),
)

// --------------------------------------------------------------- επιλογή

@Composable
private fun FetchSelection(container: AppContainer, preselectedClient: Long, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())

    val defaultYear = remember { (Calendar.getInstance().get(Calendar.YEAR) - 1).toString() }

    var action by remember { mutableStateOf(Action.FETCH) }
    var query by remember { mutableStateOf("") }
    val pickedClients = remember { mutableStateListOf<Long>() }
    val picks = remember { mutableStateListOf<Pick>() }
    var nextUid by remember { mutableStateOf(1L) }
    var autoSend by remember { mutableStateOf(false) }
    var includeSecrets by remember { mutableStateOf(container.settings.includePasswordsInClientEmail) }
    var confirmSend by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Όταν ερχόμαστε από την καρτέλα ενός πελάτη, έρχεται ήδη επιλεγμένος: το
    // «λήψη εντύπων για αυτόν» δεν πρέπει να καταλήγει σε λίστα 400 ονομάτων.
    LaunchedEffect(preselectedClient) {
        if (preselectedClient != 0L && preselectedClient !in pickedClients) {
            pickedClients.add(preselectedClient)
        }
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
    val recipients = selected.filter { it.effectiveEmail.isNotBlank() }

    val ready = when (action) {
        Action.FETCH -> picks.isNotEmpty() && selected.isNotEmpty()
        Action.CREDENTIALS -> recipients.isNotEmpty()
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.weight(1f)) {

            item {
                Spacer(Modifier.height(12.dp))
                PickerDropdown(label = "Ενέργεια", text = action.label) { dismiss ->
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
                    DocumentPicker { item ->
                        picks.add(
                            Pick(
                                uid = nextUid++,
                                itemId = item.id,
                                years = if (item.needsYear) listOf(defaultYear) else emptyList(),
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Οι επιλογές είναι λίγες (τρεις-τέσσερις) και ζουν σε απλή
                    // Column αντί για `items` με κλειδιά. Το keyed `items` έριχνε
                    // την εφαρμογή στη δεύτερη αφαίρεση, και ούτε χρειάζεται:
                    // δεν πρόκειται ποτέ για λίστα που θέλει ανακύκλωση.
                    picks.toList().forEach { pick ->
                        PickCard(
                            pick = pick,
                            onChange = { updated ->
                                val index = picks.indexOfFirst { it.uid == updated.uid }
                                if (index >= 0) picks[index] = updated
                            },
                            // `remove(element)` και όχι `removeAll { }`: το δεύτερο
                            // περνά από τον iterator του SnapshotStateList, που δεν
                            // υποστηρίζει αφαίρεση εν κινήσει.
                            onRemove = { picks.remove(pick) },
                        )
                    }
                }

                item {
                    if (picks.isEmpty()) {
                        Text(
                            "Δεν έχεις διαλέξει έντυπα. Κάθε έντυπο κρατά **δικό του** " +
                                "έτος, οπότε μπορείς να ζητήσεις Ε1 του 2025 και Ε9 του " +
                                "2027 στην ίδια εκτέλεση.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = autoSend, onCheckedChange = { autoSend = it })
                        Text(
                            "  Αποστολή με email μόλις κατέβουν",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (autoSend) {
                        Text(
                            "Ένα μήνυμα ανά πελάτη, με τα έντυπα **αυτής** της εκτέλεσης " +
                                "— όχι με ό,τι έχει ήδη λάβει.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
                        if (client.kind.isNotBlank()) append("  ·  ${client.kind}")
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
            Text(status, style = MaterialTheme.typography.bodySmall)
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
                    picks.isEmpty() || selected.isEmpty() -> "Διάλεξε έντυπα και πελάτες"
                    else -> {
                        val perClient = picks.sumOf { pick ->
                            val item = DocumentCatalog.byId(pick.itemId)
                            val years = if (item?.needsYear == true) pick.years.size.coerceAtLeast(1) else 1
                            val months = if (item?.needsMonth == true) pick.months.size.coerceAtLeast(1) else 1
                            years * months
                        }
                        "${perClient * selected.size} εκτελέσεις"
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = ready && !busy,
                onClick = {
                    when (action) {
                        Action.CREDENTIALS -> confirmSend = true
                        Action.FETCH -> scope.launch {
                            busy = true
                            status = "Προετοιμασία…"
                            val built = withContext(Dispatchers.IO) {
                                buildPlans(container, selected, picks.toList())
                            }
                            // Ένα token για δύο δουλειές: την αυτόματη αποστολή
                            // και τον συγχρονισμό στο Drive. Ζητιέται μόνο αν
                            // χρειάζεται κάποια από τις δύο.
                            val wantsDrive = container.driveSync.enabled
                            val token = if (autoSend || wantsDrive) {
                                runCatching { authorizer.accessToken() }.getOrNull()
                            } else {
                                null
                            }
                            busy = false
                            when {
                                built.plans.isEmpty() ->
                                    status = "Καμία εκτέλεση: " + built.describeSkipped()
                                autoSend && token == null ->
                                    status = "Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις " +
                                        "για την αυτόματη αποστολή."
                                else -> {
                                    status = built.describeSkipped()
                                    container.fetch.start(
                                        plans = built.plans,
                                        autoSendToken = if (autoSend) token else null,
                                        syncToken = if (wantsDrive) token else null,
                                    )
                                }
                            }
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

// ------------------------------------------------------- χτίσιμο της ουράς

private class BuiltPlans(
    val plans: List<FetchController.Plan>,
    /** Λόγος → πόσα ζεύγη πελάτη-εντύπου παραλείφθηκαν. */
    val skipped: Map<String, Int>,
) {
    fun describeSkipped(): String =
        if (skipped.isEmpty()) ""
        else skipped.entries.joinToString(" · ") { "${it.value} ${it.key}" }
}

/**
 * Φτιάχνει την ουρά, **παραλείποντας** ό,τι δεν έχει νόημα.
 *
 * Δύο φίλτρα, και τα δύο σιωπηλά μέχρι τώρα:
 *
 *  * **Είδος υπόχρεου.** Το έντυπο Ν είναι μόνο για νομικά πρόσωπα, τα
 *    ειδοποιητήρια ΕΦΚΑ μόνο για όσους έχουν ΑΜΚΑ. Χωρίς φίλτρο, μια παρτίδα
 *    «όλοι οι πελάτες» άνοιγε δεκάδες περιττές συνεδρίες GSIS που θα απέτυχαν.
 *  * **Διαπιστευτήρια.** Η καρτέλα εργοδότη θέλει κωδικούς ΙΚΑ εργοδότη, όχι
 *    TAXISnet — και οι περισσότεροι πελάτες δεν έχουν καθόλου.
 *
 * Οι παραλείψεις **αναφέρονται**: μια σιωπηλή παράλειψη μοιάζει με σφάλμα.
 */
private suspend fun buildPlans(
    container: AppContainer,
    clients: List<ClientEntity>,
    picks: List<Pick>,
): BuiltPlans {
    val plans = ArrayList<FetchController.Plan>()
    val skipped = LinkedHashMap<String, Int>()
    fun skip(reason: String) {
        skipped[reason] = (skipped[reason] ?: 0) + 1
    }

    for (client in clients) {
        for (pick in picks) {
            val item = DocumentCatalog.byId(pick.itemId) ?: continue
            if (!item.matches(client.kind)) {
                skip("δεν ισχύουν για το είδος του πελάτη")
                continue
            }
            val missing = FetchController.missingCredentials(
                repository = container.repository,
                client = client,
                configId = item.configId,
            )
            if (missing.isNotEmpty()) {
                skip("χωρίς τα απαιτούμενα διαπιστευτήρια")
                continue
            }
            // Μία επιλογή με τρία έτη γίνεται τρεις εκτελέσεις. Οι πύλες
            // δέχονται ένα έτος τη φορά — η ομαδοποίηση είναι δική μας ευκολία,
            // όχι κάτι που ξέρει η ΑΑΔΕ.
            val years = if (item.needsYear) pick.years.ifEmpty { listOf("") } else listOf("")
            val months = if (item.needsMonth) pick.months.ifEmpty { listOf("") } else listOf("")
            for (year in years) {
                for (month in months) {
                    val inputs = HashMap(item.inputs)
                    if (year.isNotBlank()) inputs["year"] = year
                    if (month.isNotBlank()) inputs["month"] = month
                    plans += FetchController.Plan(
                        job = ProcessRunner.Job(
                            client = client,
                            configId = item.configId,
                            extraInputs = inputs,
                        ),
                        label = buildString {
                            append(item.label)
                            if (year.isNotBlank()) append(" ").append(year)
                            if (month.isNotBlank()) append("/").append(month)
                        },
                        producesDocuments = item.producesDocuments,
                    )
                }
            }
        }
    }
    return BuiltPlans(plans, skipped)
}

// --------------------------------------------------------- επιλογή εντύπων

/** Το μενού προσθήκης εντύπου, χωρισμένο σε ομάδες. */
@Composable
private fun DocumentPicker(onPick: (DocumentCatalog.Item) -> Unit) {
    PickerDropdown(label = "Πρόσθεσε έντυπο", text = "— διάλεξε —") { dismiss ->
        DocumentCatalog.GROUPS.forEach { group ->
            val items = DocumentCatalog.inGroup(group)
            if (items.isEmpty()) return@forEach
            Text(
                group,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
            )
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label)
                            if (item.note.isNotBlank()) {
                                Text(
                                    item.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    },
                    onClick = {
                        onPick(item)
                        dismiss()
                    },
                )
            }
        }
    }
}

/**
 * Μία επιλεγμένη γραμμή, με τα έτη της.
 *
 * Τα έτη είναι **λίστα** και επιλέγονται από μενού, όχι πληκτρολογούνται. Δύο
 * λόγοι: «Ε1 για 2023, 2024 και 2025» είναι μία σκέψη και δεν πρέπει να γίνεται
 * τρεις γραμμές, και ένα ελεύθερο πεδίο δέχεται «202» ή «20255» — που φτάνουν
 * στην πύλη και γυρίζουν κενό αποτέλεσμα χωρίς εξήγηση.
 */
@Composable
private fun PickCard(pick: Pick, onChange: (Pick) -> Unit, onRemove: () -> Unit) {
    val item = DocumentCatalog.byId(pick.itemId) ?: return
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Αφαίρεση")
                }
            }
            if (item.needsYear) {
                Spacer(Modifier.height(4.dp))
                MultiPicker(
                    label = "Έτη",
                    options = YEARS,
                    selected = pick.years,
                    onToggle = { value ->
                        val updated = if (value in pick.years) pick.years - value else pick.years + value
                        onChange(pick.copy(years = updated.sortedDescending()))
                    },
                )
            }
            if (item.needsMonth) {
                Spacer(Modifier.height(6.dp))
                MultiPicker(
                    label = "Μήνες (κενό = ο πιο πρόσφατος)",
                    options = MONTHS.map { it.first },
                    display = { value -> MONTHS.first { it.first == value }.second },
                    selected = pick.months,
                    onToggle = { value ->
                        val updated = if (value in pick.months) pick.months - value else pick.months + value
                        onChange(pick.copy(months = updated.sortedBy { m -> m.toInt() }))
                    },
                )
            }
        }
    }
}

/** Τα έτη που προσφέρονται: το τρέχον και τα δέκα προηγούμενα. */
private val YEARS: List<String> = Calendar.getInstance().get(Calendar.YEAR).let { now ->
    (now downTo now - 10).map { it.toString() }
}

private val MONTHS: List<Pair<String, String>> = listOf(
    "1" to "Ιανουάριος", "2" to "Φεβρουάριος", "3" to "Μάρτιος", "4" to "Απρίλιος",
    "5" to "Μάιος", "6" to "Ιούνιος", "7" to "Ιούλιος", "8" to "Αύγουστος",
    "9" to "Σεπτέμβριος", "10" to "Οκτώβριος", "11" to "Νοέμβριος", "12" to "Δεκέμβριος",
)

/** Μενού πολλαπλής επιλογής που μένει ανοιχτό όσο διαλέγεις. */
@Composable
private fun MultiPicker(
    label: String,
    options: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    display: (String) -> String = { it },
) {
    PickerDropdown(
        label = label,
        text = when {
            selected.isEmpty() -> "— κανένα —"
            selected.size <= 3 -> selected.joinToString(", ", transform = display)
            else -> "${selected.size} επιλεγμένα"
        },
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                leadingIcon = {
                    Checkbox(checked = option in selected, onCheckedChange = null)
                },
                text = { Text(display(option)) },
                onClick = { onToggle(option) },
            )
        }
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
                "Σε κάθε πελάτη στέλνεται ένα ξεχωριστό μήνυμα με τα δικά του " +
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
                                FetchController.Status.OK ->
                                    "✓ ${item.fileCount} έντυπα" +
                                        if (item.detail.isNotBlank()) " · ${item.detail}" else ""
                                FetchController.Status.FAILED -> "✗ ${item.detail}"
                                FetchController.Status.CANCELLED -> "διακόπηκε"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                item.status == FetchController.Status.FAILED || item.sendFailed ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
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
