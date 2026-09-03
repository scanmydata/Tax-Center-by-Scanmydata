package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.R
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Λίστα πελατών: αναζήτηση, ενέργειες ανά πελάτη, μαζική διαγραφή.
 *
 * Το πάτημα σε γραμμή δεν ανοίγει κατευθείαν την καρτέλα. Ανοίγει **επιλογές**:
 * τις περισσότερες φορές ο λογιστής θέλει να στείλει κάτι, όχι να διορθώσει
 * στοιχεία, και η διαδρομή «καρτέλα → πίσω → οθόνη εγγράφων → βρες τον ίδιο
 * πελάτη» ήταν τρία βήματα για τη συχνότερη ενέργεια.
 */
@Composable
fun ClientsScreen(
    container: AppContainer,
    onOpenClient: (Long) -> Unit = {},
    onOpenDocuments: (Long) -> Unit = {},
    /** Πού πάει η οθόνη όταν ξεκινήσει μαζική ενημέρωση. */
    onOpenFetch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmRefresh by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }

    var actionsFor by remember { mutableStateOf<ClientEntity?>(null) }
    var sendDetailsFor by remember { mutableStateOf<ClientEntity?>(null) }
    var confirmBulk by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val filtered = remember(clients, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) clients
        else clients.filter { it.afm.contains(q) || it.displayName.lowercase().contains(q) }
    }
    val selected = remember(clients, picked.toList()) {
        val byId = clients.associateBy { it.id }
        picked.mapNotNull { byId[it] }
    }

    Column(modifier.padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Αναζήτηση με ΑΦΜ ή επωνυμία") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // Καμία λειτουργία «Επιλογή». Η επιλογή ξεκινά και τελειώνει με
        // παρατεταμένο πάτημα — η κίνηση που ξέρουν όλοι από τα αρχεία και τις
        // φωτογραφίες. Ένα κουμπί που πρέπει να πατηθεί πρώτα είναι ένα βήμα
        // παραπάνω για κάτι που κανείς δεν ψάχνει.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (picked.isEmpty()) "${filtered.size} πελάτες" else "${picked.size} επιλεγμένοι",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (picked.isNotEmpty()) {
                TextButton(onClick = {
                    if (picked.size == filtered.size) picked.clear()
                    else {
                        picked.clear()
                        picked.addAll(filtered.map { it.id })
                    }
                }) {
                    Text(if (picked.size == filtered.size) "Κανένας" else "Όλοι")
                }
                IconButton(onClick = { confirmBulk = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Διαγραφή επιλεγμένων",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // Η μαζική ενημέρωση δουλεύει και χωρίς επιλογή — τότε αφορά όσους
            // δείχνει το φίλτρο. Η οθόνη επιβεβαίωσης λέει ρητά πόσοι είναι,
            // ώστε ένα κενό πεδίο αναζήτησης να μη σημαίνει κατά λάθος 400
            // συνδέσεις στο GSIS.
            IconButton(onClick = { confirmRefresh = true }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Ενημέρωση στοιχείων από το Μητρώο",
                )
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { client ->
                ClientRow(
                    client = client,
                    selecting = picked.isNotEmpty(),
                    checked = client.id in picked,
                    onClick = {
                        // Όσο υπάρχει επιλογή, το απλό πάτημα προσθέτει και
                        // αφαιρεί· χωρίς επιλογή ανοίγει τις ενέργειες.
                        if (picked.isNotEmpty()) {
                            if (client.id in picked) picked.remove(client.id) else picked.add(client.id)
                        } else {
                            actionsFor = client
                        }
                    },
                    onLongClick = {
                        if (client.id in picked) picked.remove(client.id) else picked.add(client.id)
                    },
                )
            }
        }

        if (picked.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Παρατεταμένο πάτημα προσθέτει και αφαιρεί. Το κόκκινο εικονίδιο " +
                    "πάνω δεξιά διαγράφει τους επιλεγμένους.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    // ------------------------------------------------------------ ενέργειες

    actionsFor?.let { client ->
        ClientActionsDialog(
            client = client,
            onDismiss = { actionsFor = null },
            onOpenCard = {
                actionsFor = null
                onOpenClient(client.id)
            },
            onSendDetails = {
                actionsFor = null
                sendDetailsFor = client
            },
            onDocuments = {
                actionsFor = null
                onOpenDocuments(client.id)
            },
        )
    }

    sendDetailsFor?.let { client ->
        SendOwnDetailsDialog(
            client = client,
            defaultIncludeSecrets = container.settings.includePasswordsInClientEmail,
            onDismiss = { sendDetailsFor = null },
            onConfirm = { includeSecrets ->
                sendDetailsFor = null
                scope.launch {
                    status = "Αποστολή σε ${client.effectiveEmail}…"
                    status = try {
                        val token = authorizer.accessToken()
                        val send = withContext(Dispatchers.IO) {
                            container.mail.sendOwnDetails(token, client, includeSecrets)
                        }
                        if (send.failed) "Απέτυχε: ${send.error}" else "Στάλθηκε στον ${client.displayName}."
                    } catch (e: GoogleAuthorizer.ConsentRequired) {
                        "Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις."
                    } catch (e: Exception) {
                        "Απέτυχε: ${e.message}"
                    }
                }
            },
        )
    }

    if (confirmRefresh) {
        val targets = if (picked.isEmpty()) filtered else filtered.filter { it.id in picked }
        AlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text("Ενημέρωση ${targets.size} καρτελών") },
            text = {
                Column {
                    Text(
                        "Θα γίνει σύνδεση στο TAXISnet **μία φορά για κάθε πελάτη** και " +
                            "θα διαβαστούν ονοματεπώνυμο, ΔΟΥ, είδος υπόχρεου, " +
                            "οικογενειακή κατάσταση, email και — όπου έχει νόημα — ΑΜΚΑ.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Τίποτα δεν γράφεται αυτόματα: στο τέλος βλέπεις «πριν → μετά» " +
                            "ανά πεδίο και διαλέγεις. Όσοι δεν έχουν κωδικούς TAXISnet " +
                            "παραλείπονται.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                    if (targets.size > 20) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Είναι πολλοί. Οι συνδέσεις γίνονται αυστηρά μία-μία, οπότε " +
                                "θα πάρει ώρα — μπορείς να φύγεις από την οθόνη.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = targets.isNotEmpty(),
                    onClick = {
                        confirmRefresh = false
                        scope.launch {
                            val plans = withContext(Dispatchers.IO) {
                                container.fetch.refreshPlans(targets)
                            }
                            if (plans.isEmpty()) {
                                status = "Κανένας από τους επιλεγμένους δεν έχει κωδικούς TAXISnet."
                            } else {
                                picked.clear()
                                container.fetch.start(plans)
                                onOpenFetch()
                            }
                        }
                    },
                ) { Text("Ενημέρωση") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRefresh = false }) { Text("Άκυρο") }
            },
        )
    }

    if (confirmBulk) {
        BulkDeleteDialog(
            clients = selected,
            onDismiss = { confirmBulk = false },
            onDeleteAll = {
                confirmBulk = false
                scope.launch {
                    status = "Διαγραφή…"
                    val count = withContext(Dispatchers.IO) {
                        container.repository.deleteClients(selected)
                    }
                    picked.clear()
                    status = "Διαγράφηκαν $count πελάτες."
                }
            },
            onDeleteDocuments = {
                confirmBulk = false
                scope.launch {
                    status = "Διαγραφή εγγράφων…"
                    val count = withContext(Dispatchers.IO) {
                        container.repository.deleteDocumentsOf(selected)
                    }
                    picked.clear()
                    status = "Διαγράφηκαν $count έγγραφα. Οι πελάτες παρέμειναν."
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClientRow(
    client: ClientEntity,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (checked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
                Spacer(Modifier.height(0.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(client.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(client.afm)
                        if (client.kind.isNotBlank()) append("  ·  ").append(client.kind)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    client.effectiveEmail.ifBlank { "— χωρίς email —" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (client.effectiveEmail.isBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Τι μπορεί να γίνει με έναν πελάτη.
 *
 * Δύο ενέργειες αντί για τέσσερις. Η «λήψη εντύπων» και η «αποστολή εντύπων»
 * ήταν χωριστές γραμμές που κατέληγαν στην ίδια δουλειά και στα ίδια αρχεία —
 * τώρα είναι μία, και οδηγεί στην καρτέλα «Έγγραφα», όπου υπάρχουν και τα δύο
 * μαζί με ό,τι έχει ήδη κατέβει.
 */
@Composable
private fun ClientActionsDialog(
    client: ClientEntity,
    onDismiss: () -> Unit,
    onOpenCard: () -> Unit,
    onSendDetails: () -> Unit,
    onDocuments: () -> Unit,
) {
    val hasEmail = client.effectiveEmail.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Καρτέλα", style = MaterialTheme.typography.labelMedium)
                Text(client.displayName, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column {
                Text(
                    buildString {
                        append("ΑΦΜ ").append(client.afm)
                        if (client.kind.isNotBlank()) append("  ·  ").append(client.kind)
                        if (client.doy.isNotBlank()) append("  ·  ").append(client.doy)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    client.effectiveEmail.ifBlank { "— χωρίς διεύθυνση email —" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasEmail) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.error,
                )

                Spacer(Modifier.height(16.dp))
                ActionRow(
                    icon = R.drawable.ic_menu_clients,
                    title = "Άνοιγμα καρτέλας",
                    subtitle = "Στοιχεία, διαπιστευτήρια, εντολή",
                    onClick = onOpenCard,
                )
                ActionRow(
                    icon = R.drawable.ic_menu_documents,
                    title = "Έντυπα — λήψη και αποστολή",
                    subtitle = "Ό,τι έχει κατέβει, και λήψη νέων",
                    onClick = onDocuments,
                )
                ActionRow(
                    icon = R.drawable.ic_menu_send,
                    title = "Αποστολή στοιχείων & κωδικών",
                    subtitle = if (hasEmail) "ΑΦΜ, ΑΜΚΑ, χρήστης TAXISnet" else "χρειάζεται email",
                    enabled = hasEmail,
                    onClick = onSendDetails,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Κλείσιμο") } },
    )
}

@Composable
private fun ActionRow(
    @androidx.annotation.DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * alpha),
            )
        }
    }
}

/**
 * Μαζική διαγραφή, με **δύο ξεχωριστά κουμπιά** αντί για διακόπτη.
 *
 * «Σβήσε τα έγγραφα» και «σβήσε τους πελάτες» δεν είναι παραλλαγές της ίδιας
 * ενέργειας: το πρώτο ελευθερώνει χώρο, το δεύτερο τερματίζει σχέση και είναι
 * μη αναστρέψιμο. Ένα checkbox μέσα σε ένα κουμπί «Διαγραφή» θα έκανε εύκολο
 * να γίνει το δεύτερο ενώ εννοούσες το πρώτο.
 */
@Composable
private fun BulkDeleteDialog(
    clients: List<ClientEntity>,
    onDismiss: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteDocuments: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Διαγραφή — ${clients.size} πελάτες") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                clients.forEach { client ->
                    Text(
                        "• ${client.displayName} (${client.afm})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("Μόνο τα έγγραφα", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Σβήνονται τα ληφθέντα PDF και οι εγγραφές τους. Οι πελάτες, οι " +
                        "κωδικοί και το ιστορικό αποστολών μένουν.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Οριστική διαγραφή πελατών",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Σβήνονται καρτέλες, διαπιστευτήρια, έγγραφα και αρχεία. Το αρχείο " +
                        "ενεργειών μένει — είναι αυτό που αποδεικνύει ότι η διαγραφή έγινε. " +
                        "Η ενέργεια δεν αναιρείται· κρατιέται αντίγραφο της βάσης πριν.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDeleteAll) {
                Text("Οριστική διαγραφή", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Άκυρο") }
                TextButton(onClick = onDeleteDocuments) { Text("Μόνο έγγραφα") }
            }
        },
    )
}

/**
 * Επιβεβαίωση πριν σταλούν στον πελάτη τα στοιχεία του.
 *
 * Ο διακόπτης για τους κωδικούς είναι **κλειστός εξ ορισμού** και η
 * προειδοποίηση δεν κρύβεται πίσω από «προχωρημένες ρυθμίσεις»: το email δεν
 * είναι ασφαλές κανάλι, και ο κωδικός TAXISnet δίνει πλήρη πρόσβαση στη
 * φορολογική εικόνα του ανθρώπου.
 */
@Composable
private fun SendOwnDetailsDialog(
    client: ClientEntity,
    defaultIncludeSecrets: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var includeSecrets by remember { mutableStateOf(defaultIncludeSecrets) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Αποστολή στοιχείων") },
        text = {
            Column {
                Text("Προς: ${client.effectiveEmail}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Θα σταλούν τα πεδία που έχεις ορίσει στο πρότυπο — από προεπιλογή " +
                        "ΑΦΜ, ΑΜΚΑ και όνομα χρήστη TAXISnet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                    Text(
                        "Να συμπεριληφθούν συνθηματικό και κλειδάριθμος",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (includeSecrets) {
                    Text(
                        "Το email δεν είναι ασφαλές κανάλι: περνά από servers τρίτων και " +
                            "μένει στο γραμματοκιβώτιο του πελάτη. Το μήνυμα θα τον " +
                            "προτρέπει να το διαγράψει.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(includeSecrets) }) { Text("Αποστολή") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}
