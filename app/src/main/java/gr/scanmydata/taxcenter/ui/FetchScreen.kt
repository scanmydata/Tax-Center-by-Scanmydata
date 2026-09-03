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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
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
    // Ο κατάλογος δείχνει εξ ορισμού μόνο ό,τι αφορά τον πελάτη, όταν ο πελάτης
    // είναι ένας. Ο διακόπτης υπάρχει γιατί το «είδος» της καρτέλας μπορεί να
    // είναι λάθος ή παλιό — και τότε ο χρήστης πρέπει να μπορεί να το αγνοήσει.
    var showAllForms by remember { mutableStateOf(false) }
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
                    // Ένας πελάτης = ξέρουμε τι τον αφορά. Πολλοί = δεν ξέρουμε,
                    // και το να φιλτράρουμε με βάση τον πρώτο θα ήταν αυθαίρετο.
                    val singleKind = if (selected.size == 1) selected.first().kind else ""
                    val filterKind = if (showAllForms) "" else singleKind

                    if (DocumentCatalog.narrows(singleKind)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (showAllForms) {
                                    "Εμφανίζονται όλα τα έντυπα."
                                } else {
                                    "Μόνο τα έντυπα που αφορούν: " + singleKind
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showAllForms = !showAllForms }) {
                                Text(if (showAllForms) "Μόνο τα σχετικά" else "Όλα")
                            }
                        }
                    }
                    DocumentPicker(kind = filterKind) { item ->
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

    // Η άντληση στοιχείων φέρνει και τον ΑΜΚΑ σε ιδιώτη ή ατομική — όπως κάνει
    // και η μεμονωμένη άντληση από την καρτέλα. Οι δύο διαδρομές πρέπει να
    // συμπεριφέρονται ίδια, αλλιώς ο χρήστης μαθαίνει δύο διαφορετικά πράγματα
    // για το ίδιο κουμπί.
    val expanded = buildList {
        addAll(picks)
        if (picks.any { it.itemId == "profile" } && picks.none { it.itemId == "amka" }) {
            add(Pick(uid = -1L, itemId = "amka"))
        }
    }

    for (client in clients) {
        for (pick in expanded) {
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
            // Εξαίρεση: όπου η διαδικασία δέχεται **λίστα** ετών, όλα τα έτη
            // πάνε σε μία εκτέλεση. Στο ETAK αυτό δεν είναι βελτιστοποίηση —
            // είναι ο μόνος τρόπος να μη γίνουν τρεις συνεδρίες GSIS για τρία
            // έτη του ίδιου πελάτη.
            if (item.batchYears && item.needsYear) {
                val list = pick.years.filter { it.isNotBlank() }
                val inputs = HashMap(item.inputs)
                if (list.isNotEmpty()) inputs["years"] = list.joinToString(",")
                plans += FetchController.Plan(
                    job = ProcessRunner.Job(
                        client = client,
                        configId = item.configId,
                        extraInputs = inputs,
                    ),
                    label = item.label + if (list.isNotEmpty()) " " + list.joinToString(", ") else "",
                    producesDocuments = item.producesDocuments,
                )
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

/**
 * Η επιλογή εντύπου — **διάλογος με αναζήτηση**, όχι πτυσσόμενο μενού.
 *
 * Τριάντα έντυπα σε εννιά ομάδες δεν χωρούν σε dropdown τηλεφώνου: το μενού
 * γινόταν μια λίστα που ήθελε κύλιση, χωρίς τρόπο να πεις «Φ2» και να το βρεις.
 * Με τη σημείωση κάθε εντύπου από κάτω, ακόμη λιγότερο.
 *
 * Η αναζήτηση πιάνει ετικέτα, ομάδα **και** σημείωση: ο λογιστής πληκτρολογεί
 * «μισθωτ» ή «ΕΦΚΑ» εξίσου συχνά με το «Ε2».
 *
 * Με [kind] συμπληρωμένο δείχνει μόνο τα έντυπα που αφορούν αυτό το είδος
 * υπόχρεου. Σε ιδιώτη αυτό βγάζει από τη μέση ΦΠΑ, Ε3, ΦΕΝΠ και τους
 * παρακρατούμενους — έντυπα που δεν έχει, και που η πύλη θα γύριζε άδεια.
 */
@Composable
private fun DocumentPicker(kind: String = "", onPick: (DocumentCatalog.Item) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    OutlinedButton(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (open) "Κλείσιμο καταλόγου" else "Πρόσθεσε έντυπο") }

    if (!open) return

    val needle = query.trim().lowercase()
    val groups = remember(needle, kind) {
        DocumentCatalog.GROUPS.map { group ->
            group to DocumentCatalog.inGroup(group, kind).filter { item ->
                needle.isBlank() ||
                    item.label.lowercase().contains(needle) ||
                    item.group.lowercase().contains(needle) ||
                    item.note.lowercase().contains(needle)
            }
        }.filter { it.second.isNotEmpty() }
    }

    Spacer(Modifier.height(8.dp))

    // Δική του επιφάνεια, με περίγραμμα. Ενσωματωμένος **και** αδιάκριτος ήταν
    // χειρότερος από popup: τα έντυπα προς επιλογή ανακατεύονταν οπτικά με τις
    // ήδη επιλεγμένες γραμμές και με τη λίστα πελατών από κάτω, και δεν
    // φαινόταν πού αρχίζει και πού τελειώνει ο κατάλογος.
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Κατάλογος εντύπων",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Αναζήτηση εντύπου") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))

            // `Column` με φραγμένο ύψος και δικό του scroll, **όχι** LazyColumn:
            // αυτό ζει μέσα σε `item {}` ενός LazyColumn, και μια δεύτερη
            // τεμπέλικη λίστα με απεριόριστο ύψος ρίχνει το Compose.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (groups.isEmpty()) {
                    Text(
                        "Κανένα έντυπο δεν ταιριάζει με «" + query.trim() + "».",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                groups.forEachIndexed { index, (group, items) ->
                    if (index > 0) {
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Text(
                        group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    items.forEach { entry ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(entry)
                                    query = ""
                                    open = false
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                            if (entry.note.isNotBlank()) {
                                Text(
                                    entry.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
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
    val context = LocalContext.current
    var reviewing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // Τα έντυπα της γραμμής που ξεδιπλώθηκε — εμφανίζονται **μέσα** στη
    // γραμμή. Ένα popup για δύο ονόματα αρχείων είναι δυσανάλογο, και κρύβει
    // ακριβώς τη λίστα από την οποία ήρθε.
    var expanded by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }
    // Το SharedPreferences δεν ειδοποιεί το Compose· κρατάμε αντίγραφο και
    // γράφουμε πίσω, ώστε ο διακόπτης εδώ και η ρύθμιση να λένε το ίδιο.
    var grouped by remember { mutableStateOf(container.settings.groupFetchByClient) }

    /**
     * Πάτημα σε ολοκληρωμένη γραμμή = άνοιγμα του εντύπου της.
     *
     * Μέχρι τώρα η οθόνη προόδου ήταν αδιέξοδο: έλεγε «✓ 2 έντυπα» και ο μόνος
     * τρόπος να τα δεις ήταν να φύγεις και να τα ξαναβρείς στα Έγγραφα.
     */
    val openRow: (FetchController.Item) -> Unit = { row ->
        scope.launch {
            when {
                row.files.isEmpty() ->
                    message = "Αυτή η γραμμή δεν παρήγαγε έντυπο."
                else -> {
                    val documents = withContext(Dispatchers.IO) {
                        container.db.documents().byClientAndNames(row.clientId, row.files)
                    }
                    when {
                        documents.isEmpty() ->
                            message = "Τα αρχεία δεν βρίσκονται πια στη συσκευή."
                        documents.size == 1 ->
                            message = DocumentActions.open(context, documents.first())
                        else -> {
                            choice = documents
                            expanded = if (expanded == row.key) "" else row.key
                        }
                    }
                }
            }
        }
    }

    Column(modifier.padding(16.dp)) {

        Text(
            if (state.running) {
                "${state.done}/${state.total} ολοκληρώθηκαν"
            } else {
                // Οι κενές δεν μετριούνται ούτε στις επιτυχίες ούτε στις
                // αποτυχίες: ένα «40/40 επιτυχίες» με 12 άδεια είναι ψέμα.
                buildString {
                    append("Τέλος — ")
                    append(state.total - state.failed - state.empty)
                    append("/").append(state.total).append(" με έντυπα")
                    if (state.empty > 0) append("  ·  ${state.empty} χωρίς")
                    if (state.failed > 0) append("  ·  ${state.failed} απέτυχαν")
                }
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )

        // Ο browser τρέχει κρυφός. Εμφανίζεται μόνο όταν η σελίδα ζητά
        // άνθρωπο — ή όταν ο χρήστης θέλει να δει τι γίνεται.
        if (state.browserRunning && !state.browserActive) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Η διαδικασία χρησιμοποιεί browser που τρέχει στο παρασκήνιο. " +
                        "Θα εμφανιστεί μόνος του αν ζητηθεί κωδικός μιας χρήσης ή CAPTCHA.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { controller.showBrowser() }) { Text("Εμφάνιση") }
            }
        }

        if (state.browserActive) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Η σελίδα ζητά κάτι που πρέπει να κάνεις εσύ — κωδικό μιας χρήσης ή " +
                    "CAPTCHA. Συμπλήρωσέ το εδώ· η εφαρμογή δεν τα παρακάμπτει.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(8.dp))
            BrowserPanel(container)
        }

        // Το κενό αποτέλεσμα εξηγείται ρητά. Χωρίς αυτό, ο λογιστής βλέπει μια
        // παρτίδα «χωρίς σφάλματα» και υποθέτει ότι έφυγαν έντυπα που δεν
        // υπήρξαν ποτέ.
        if (!state.running && state.empty > 0) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Δεν βρέθηκε έντυπο σε ${state.empty} από ${state.total} εκτελέσεις",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Η σύνδεση πέτυχε και η πύλη απάντησε — απλώς δεν υπάρχει " +
                            "υποβεβλημένο έντυπο για αυτόν τον συνδυασμό πελάτη και " +
                            "έτους. Συνήθως φταίει το έτος, ή ότι το έντυπο δεν " +
                            "αφορά αυτόν τον υπόχρεο. Δεν στάλθηκε τίποτα γι' αυτές " +
                            "τις γραμμές.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val names = state.emptyClients
                    if (names.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            names.take(6).joinToString(", ") +
                                if (names.size > 6) " και άλλοι ${names.size - 6}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Σύζυγοι που βρήκε το ETAK. Χωριστά από τις «αλλαγές πεδίων»: εδώ
        // δεν αλλάζει τιμή, δημιουργείται **καρτέλα πελάτη**.
        state.spouses.forEach { find ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Βρέθηκε ${find.relation.lowercase()} στο Ε9 του ${find.clientName}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${find.displayName} · ΑΦΜ ${find.spouseAfm}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (find.alreadyClient) {
                            "Υπάρχει ήδη καρτέλα με αυτό το ΑΦΜ — θα συνδεθούν οι δύο " +
                                "καρτέλες μεταξύ τους."
                        } else {
                            "Θα δημιουργηθεί καρτέλα ιδιώτη με ΑΦΜ και ονοματεπώνυμο. " +
                                "**Χωρίς κωδικούς** — δεν τους έχουμε και δεν τους " +
                                "μαντεύουμε· η καρτέλα θα δουλέψει μόλις τους βάλεις."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { scope.launch { controller.applySpouse(find) } }) {
                            Text(if (find.alreadyClient) "Σύνδεση" else "Δημιουργία καρτέλας")
                        }
                        OutlinedButton(onClick = { controller.discardSpouse(find) }) {
                            Text("Όχι")
                        }
                    }
                }
            }
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

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Πάτημα σε ολοκληρωμένη γραμμή ανοίγει το έντυπο.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                grouped = !grouped
                container.settings.groupFetchByClient = grouped
            }) { Text(if (grouped) "Αναλυτικά" else "Ανά πελάτη") }
        }

        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (grouped) {
                // Ομαδοποίηση με `clientId` και όχι με το όνομα: δύο πελάτες
                // μπορεί να λέγονται ίδια, και μια νέα καρτέλα χωρίς όνομα δεν
                // πρέπει να συγχωνεύεται με άλλη.
                val groups = state.items.groupBy { it.clientId to it.clientName }
                groups.forEach { (key, rows) ->
                    item(key = "c-${key.first}-${key.second}") {
                        ClientProgressCard(
                            name = key.second,
                            rows = rows,
                            onOpen = openRow,
                            expandedKey = expanded,
                            choices = choice,
                            onOpenFile = { document ->
                                expanded = ""
                                message = DocumentActions.open(context, document)
                            },
                        )
                    }
                }
            } else {
                items(state.items, key = { it.key }) { row ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { openRow(row) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "${row.clientName} — ${row.configTitle}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            ProgressLine(row)
                            if (expanded == row.key) {
                                FileChoices(choice) { document ->
                                    expanded = ""
                                    message = DocumentActions.open(context, document)
                                }
                            }
                        }
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

/**
 * Μία κάρτα ανά πελάτη αντί για μία ανά έντυπο.
 *
 * Σε παρτίδα «5 έντυπα × 40 πελάτες» η αναλυτική λίστα είναι 200 κάρτες — που
 * σε τηλέφωνο σημαίνει ότι κανείς δεν τη διαβάζει. Εδώ η πρώτη γραμμή απαντά
 * στην ερώτηση που όντως έχει ο λογιστής («ποιοι πελάτες έχουν πρόβλημα;») και
 * οι λεπτομέρειες μένουν από κάτω, μικρότερες.
 */
@Composable
private fun ClientProgressCard(
    name: String,
    rows: List<FetchController.Item>,
    onOpen: (FetchController.Item) -> Unit,
    expandedKey: String,
    choices: List<DocumentEntity>,
    onOpenFile: (DocumentEntity) -> Unit,
) {
    val ok = rows.count { it.status == FetchController.Status.OK }
    val empty = rows.count { it.status == FetchController.Status.EMPTY }
    val failed = rows.count { it.status == FetchController.Status.FAILED }
    val files = rows.sumOf { it.fileCount }

    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(name.ifBlank { "(χωρίς όνομα)" }, style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append(files).append(" έντυπα από ").append(rows.size).append(" εκτελέσεις")
                    if (empty > 0) append("  ·  ").append(empty).append(" χωρίς")
                    if (failed > 0) append("  ·  ").append(failed).append(" απέτυχαν")
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    failed > 0 -> MaterialTheme.colorScheme.error
                    empty > 0 -> MaterialTheme.colorScheme.tertiary
                    ok > 0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
            )
            Spacer(Modifier.height(4.dp))
            rows.forEach { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(row.configTitle, style = MaterialTheme.typography.bodySmall)
                    ProgressLine(row)
                    if (expandedKey == row.key) FileChoices(choices, onOpenFile)
                }
            }
        }
    }
}

/** Η μία γραμμή κατάστασης, κοινή στις δύο όψεις. */
@Composable
private fun ProgressLine(row: FetchController.Item) {
    Text(
        when (row.status) {
            FetchController.Status.PENDING -> "σε αναμονή"
            FetchController.Status.RUNNING -> "εκτελείται…"
            FetchController.Status.OK ->
                "✓ ${row.fileCount} έντυπα" +
                    if (row.detail.isNotBlank()) " · ${row.detail}" else ""
            FetchController.Status.EMPTY -> "— ${row.detail}"
            FetchController.Status.FAILED -> "✗ ${row.detail}"
            FetchController.Status.CANCELLED -> "διακόπηκε"
        },
        style = MaterialTheme.typography.bodySmall,
        color = when {
            row.status == FetchController.Status.FAILED || row.sendFailed ->
                MaterialTheme.colorScheme.error
            row.status == FetchController.Status.EMPTY -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
    )
}

/**
 * Τα αρχεία μιας γραμμής, **μέσα** στη γραμμή.
 *
 * Ήταν διάλογος. Για δύο ονόματα αρχείων ένα popup είναι δυσανάλογο: σκεπάζει
 * τη λίστα από την οποία ήρθε, και θέλει δεύτερο πάτημα για να φύγει.
 */
@Composable
private fun FileChoices(documents: List<DocumentEntity>, onOpen: (DocumentEntity) -> Unit) {
    if (documents.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    documents.forEach { document ->
        Text(
            "· " + document.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(document) }
                .padding(vertical = 6.dp),
        )
    }
}
