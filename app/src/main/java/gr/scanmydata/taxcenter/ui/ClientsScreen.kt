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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
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
    onFetchFor: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Long>() }

    var actionsFor by remember { mutableStateOf<ClientEntity?>(null) }
    var sendDetailsFor by remember { mutableStateOf<ClientEntity?>(null) }
    var sendDocumentsFor by remember { mutableStateOf<ClientEntity?>(null) }
    var clientDocuments by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selecting) "${picked.size} επιλεγμένοι" else "${filtered.size} πελάτες",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (selecting) {
                TextButton(onClick = {
                    if (picked.size == filtered.size) picked.clear()
                    else {
                        picked.clear()
                        picked.addAll(filtered.map { it.id })
                    }
                }) {
                    Text(if (picked.size == filtered.size && filtered.isNotEmpty()) "Κανένας" else "Όλοι")
                }
            }
            TextButton(onClick = {
                selecting = !selecting
                picked.clear()
            }) { Text(if (selecting) "Άκυρο" else "Επιλογή") }
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
                    selecting = selecting,
                    checked = client.id in picked,
                    onClick = {
                        if (selecting) {
                            if (client.id in picked) picked.remove(client.id) else picked.add(client.id)
                        } else {
                            actionsFor = client
                        }
                    },
                    // Παρατεταμένο πάτημα ξεκινά την επιλογή. Το κουμπί
                    // «Επιλογή» μένει, αλλά κανείς δεν το ψάχνει: η κίνηση που
                    // ξέρουν όλοι από τα αρχεία και τις φωτογραφίες είναι αυτή.
                    onLongClick = {
                        selecting = true
                        if (client.id in picked) picked.remove(client.id) else picked.add(client.id)
                    },
                )
            }
        }

        if (selecting && picked.isNotEmpty()) {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { confirmBulk = true }) {
                    Text("Διαγραφή ${picked.size}")
                }
            }
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
            onSendDocuments = {
                actionsFor = null
                scope.launch {
                    clientDocuments = withContext(Dispatchers.IO) {
                        container.db.documents().forClient(client.id)
                    }
                    // Ανοίγει πάντα, ακόμη και άδειος: ένα μήνυμα «δεν έχει
                    // έντυπα» σε κλειστό dialog αφήνει τον χρήστη να αναρωτιέται
                    // τι να κάνει. Ο επιλογέας δείχνει τη διέξοδο.
                    sendDocumentsFor = client
                }
            },
            onFetch = {
                actionsFor = null
                onFetchFor(client.id)
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

    sendDocumentsFor?.let { client ->
        if (clientDocuments.isEmpty()) {
            AlertDialog(
                onDismissRequest = { sendDocumentsFor = null },
                title = { Text("Καθόλου έντυπα") },
                text = {
                    Text(
                        "Δεν έχει κατέβει κανένα έντυπο για τον ${client.displayName}. " +
                            "Κάνε πρώτα λήψη και μετά στείλε — ή διάλεξε «Λήψη και " +
                            "αποστολή» στην οθόνη λήψης για να γίνουν μαζί.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        sendDocumentsFor = null
                        onFetchFor(client.id)
                    }) { Text("Λήψη εντύπων") }
                },
                dismissButton = {
                    TextButton(onClick = { sendDocumentsFor = null }) { Text("Άκυρο") }
                },
            )
            return@let
        }
        SelectDocumentsDialog(
            client = client,
            documents = clientDocuments,
            onDismiss = { sendDocumentsFor = null },
            onConfirm = { documents, note ->
                sendDocumentsFor = null
                scope.launch {
                    status = "Αποστολή ${documents.size} εντύπων…"
                    status = try {
                        val token = authorizer.accessToken()
                        val send = withContext(Dispatchers.IO) {
                            container.mail.sendDocuments(token, client, documents, note)
                        }
                        if (send.failed) "Απέτυχε: ${send.error}"
                        else "Στάλθηκαν ${documents.size} έντυπα στον ${client.displayName}."
                    } catch (e: GoogleAuthorizer.ConsentRequired) {
                        "Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις."
                    } catch (e: Exception) {
                        "Απέτυχε: ${e.message}"
                    }
                }
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
                    selecting = false
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
                    selecting = false
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

/** Τι μπορεί να γίνει με έναν πελάτη, χωρίς να ανοίξει η καρτέλα του. */
@Composable
private fun ClientActionsDialog(
    client: ClientEntity,
    onDismiss: () -> Unit,
    onOpenCard: () -> Unit,
    onSendDetails: () -> Unit,
    onSendDocuments: () -> Unit,
    onFetch: () -> Unit,
) {
    val hasEmail = client.effectiveEmail.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(client.displayName) },
        text = {
            Column {
                Text(
                    "ΑΦΜ ${client.afm}" + if (client.doy.isNotBlank()) " · ${client.doy}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    client.effectiveEmail.ifBlank { "— χωρίς διεύθυνση email —" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasEmail) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onOpenCard, modifier = Modifier.fillMaxWidth()) {
                    Text("Άνοιγμα καρτέλας", modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onFetch, modifier = Modifier.fillMaxWidth()) {
                    Text("Λήψη εντύπων", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onSendDocuments,
                    enabled = hasEmail,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Αποστολή εντύπων", modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onSendDetails,
                    enabled = hasEmail,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Αποστολή στοιχείων & κωδικών", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Κλείσιμο") } },
    )
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
