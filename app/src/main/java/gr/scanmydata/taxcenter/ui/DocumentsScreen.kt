package gr.scanmydata.taxcenter.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Τα ληφθέντα έντυπα, ανά πελάτη, και η αποστολή τους.
 *
 * Η μαζική αποστολή περνά **υποχρεωτικά** από οθόνη επιβεβαίωσης που δείχνει
 * ονομαστικά κάθε παραλήπτη. Ένα λάθος email εδώ σημαίνει φορολογικό έντυπο σε
 * λάθος άνθρωπο — και ένα περιστατικό παραβίασης με προθεσμία 72 ωρών.
 *
 * Πάντα **ένα μήνυμα ανά πελάτη**. Ποτέ κοινοποίηση, ποτέ δεύτερος παραλήπτης.
 */
@Composable
fun DocumentsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())
    val documents: List<DocumentEntity> by container.db.documents().observeRecent()
        .collectAsState(initial = emptyList())

    var onlyUnsent by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sendTarget by remember { mutableStateOf<ClientEntity?>(null) }
    var bulkConfirm by remember { mutableStateOf(false) }
    // Πολλαπλή επιλογή εγγράφων, διασχίζοντας πελάτες. Το «σβήσε τα περσινά»
    // δεν σταματά στα όρια ενός πελάτη.
    val pickedDocuments = remember { mutableStateListOf<Long>() }
    var confirmDeleteDocs by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val byClient = remember(clients, documents, onlyUnsent, query) {
        val q = query.trim().lowercase()
        val grouped = documents
            .filter { !onlyUnsent || it.sentAt == 0L }
            .groupBy { it.clientId }
        clients
            .filter { q.isBlank() || it.afm.contains(q) || it.displayName.lowercase().contains(q) }
            .mapNotNull { client ->
                val docs = grouped[client.id].orEmpty()
                if (docs.isEmpty()) null else client to docs
            }
    }

    val bulkTargets = remember(byClient) {
        byClient.filter { (client, _) -> client.effectiveEmail.isNotBlank() }
    }

    Column(modifier.padding(16.dp)) {

        // Η διαγραφή είναι **πάνω δεξιά**, εκεί που την ψάχνει το χέρι, και
        // εμφανίζεται μόνο όταν υπάρχει επιλογή. Ήταν κουμπί κειμένου στο κάτω
        // μέρος, δίπλα στο «Άκυρο»: δύο παρόμοια κουμπιά σε μέγεθος αντίχειρα,
        // το ένα μη αναστρέψιμο.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pickedDocuments.isEmpty()) "Έγγραφα"
                else "${pickedDocuments.size} επιλεγμένα",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (pickedDocuments.isNotEmpty()) {
                TextButton(onClick = { pickedDocuments.clear() }) { Text("Άκυρο") }
                IconButton(onClick = { confirmDeleteDocs = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Διαγραφή ${pickedDocuments.size} εντύπων",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Αναζήτηση με ΑΦΜ ή επωνυμία") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = onlyUnsent, onCheckedChange = { onlyUnsent = it })
            Text(
                "  Μόνο όσα δεν έχουν σταλεί",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = bulkTargets.isNotEmpty(),
                onClick = { bulkConfirm = true },
            ) { Text("Μαζική αποστολή") }
        }

        Text(
            "Πάτημα σε έντυπο το ανοίγει· παρατεταμένο πάτημα το επιλέγει.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))
        if (byClient.isEmpty()) {
            Text(
                if (documents.isEmpty()) "Δεν έχει κατέβει κανένα έντυπο ακόμη."
                else "Όλα τα έντυπα έχουν σταλεί.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(byClient, key = { it.first.id }) { (client, docs) ->
                ClientDocumentsCard(
                    client = client,
                    documents = docs,
                    picked = pickedDocuments,
                    onSend = { sendTarget = client },
                    onOpen = { doc -> status = DocumentActions.open(context, doc) },
                    onToggle = { doc ->
                        if (doc.id in pickedDocuments) pickedDocuments.remove(doc.id)
                        else pickedDocuments.add(doc.id)
                    },
                )
            }
        }

    }

    if (confirmDeleteDocs) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDocs = false },
            title = { Text("Διαγραφή ${pickedDocuments.size} εντύπων") },
            text = {
                Text(
                    "Τα αρχεία σβήνονται από τη συσκευή και δεν ανακτώνται. Οι " +
                        "καρτέλες των πελατών και το ιστορικό αποστολών δεν θίγονται — " +
                        "αν χρειαστούν ξανά, κατεβαίνουν από την πύλη.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteDocs = false
                    scope.launch {
                        val targets = documents.filter { it.id in pickedDocuments }
                        val count = withContext(Dispatchers.IO) {
                            DocumentActions.delete(context, container.db, targets)
                        }
                        pickedDocuments.clear()
                        status = "Διαγράφηκαν $count έντυπα."
                    }
                }) { Text("Διαγραφή", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDocs = false }) { Text("Άκυρο") }
            },
        )
    }

    // ------------------------------------------------- αποστολή ενός πελάτη

    sendTarget?.let { client ->
        val docs = byClient.firstOrNull { it.first.id == client.id }?.second.orEmpty()
        SelectDocumentsDialog(
            client = client,
            documents = docs,
            onDismiss = { sendTarget = null },
            onConfirm = { chosen, note ->
                sendTarget = null
                scope.launch {
                    status = "Αποστολή σε ${client.effectiveEmail}…"
                    status = sendOne(container, authorizer, client, chosen, note)
                }
            },
        )
    }

    // ------------------------------------------------------ μαζική αποστολή

    if (bulkConfirm) {
        BulkConfirmDialog(
            targets = bulkTargets,
            skipped = byClient.filter { (client, _) -> client.effectiveEmail.isBlank() }.map { it.first },
            onDismiss = { bulkConfirm = false },
            onConfirm = {
                bulkConfirm = false
                scope.launch {
                    var sent = 0
                    var failed = 0
                    for ((client, docs) in bulkTargets) {
                        status = "Αποστολή ${sent + failed + 1}/${bulkTargets.size} — ${client.displayName}…"
                        val result = sendOne(container, authorizer, client, docs, "")
                        if (result.startsWith("Απέτυχε")) failed++ else sent++
                    }
                    status = "Ολοκληρώθηκε: $sent εστάλησαν, $failed απέτυχαν. " +
                        "Δες το ημερολόγιο αποστολών για λεπτομέρειες."
                }
            },
        )
    }
}

/**
 * Μία αποστολή, με το σφάλμα ως κείμενο αντί για εξαίρεση.
 *
 * Η μαζική αποστολή δεν πρέπει να σταματά στον πρώτο πελάτη που δεν έχει
 * σωστό email ή που τον απέρριψε το Gmail — οι υπόλοιποι 39 πρέπει να φύγουν.
 */
private suspend fun sendOne(
    container: AppContainer,
    authorizer: GoogleAuthorizer,
    client: ClientEntity,
    documents: List<DocumentEntity>,
    note: String,
): String = try {
    val token = authorizer.accessToken()
    val send = withContext(Dispatchers.IO) {
        container.mail.sendDocuments(token, client, documents, note)
    }
    if (send.failed) "Απέτυχε: ${send.error}" else "Στάλθηκαν ${documents.size} έντυπα στον ${client.displayName}."
} catch (e: GoogleAuthorizer.ConsentRequired) {
    "Απέτυχε: χρειάζεται σύνδεση με Google από τις Ρυθμίσεις."
} catch (e: Exception) {
    "Απέτυχε: ${e.message}"
}

@Composable
private fun ClientDocumentsCard(
    client: ClientEntity,
    documents: List<DocumentEntity>,
    picked: List<Long>,
    onSend: () -> Unit,
    onOpen: (DocumentEntity) -> Unit,
    onToggle: (DocumentEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) documents else documents.take(4)

    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(client.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        client.effectiveEmail.ifBlank { "— χωρίς email —" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (client.effectiveEmail.isBlank()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = onSend, enabled = client.effectiveEmail.isNotBlank()) {
                    Text("Αποστολή (${documents.size})")
                }
            }
            Spacer(Modifier.height(4.dp))
            shown.forEach { doc ->
                DocumentRow(
                    document = doc,
                    checked = doc.id in picked,
                    selecting = picked.isNotEmpty(),
                    onClick = { if (picked.isNotEmpty()) onToggle(doc) else onOpen(doc) },
                    onLongClick = { onToggle(doc) },
                )
            }
            if (documents.size > 4) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) "Λιγότερα"
                        else "… και άλλα ${documents.size - 4}",
                    )
                }
            }
        }
    }
}

/**
 * Χρησιμοποιείται και από τη λίστα πελατών: το «στείλε τα έντυπα αυτού του
 * πελάτη» είναι η ίδια ενέργεια, από άλλη αφετηρία.
 */
@Composable
internal fun SelectDocumentsDialog(
    client: ClientEntity,
    documents: List<DocumentEntity>,
    onDismiss: () -> Unit,
    onConfirm: (List<DocumentEntity>, String) -> Unit,
) {
    val picked = remember(documents) { mutableStateListOf<Long>().apply { addAll(documents.map { it.id }) } }
    var note by remember { mutableStateOf("") }
    val formatter = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale("el", "GR")).apply {
            timeZone = TimeZone.getTimeZone("Europe/Athens")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Αποστολή εντύπων") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Προς: ${client.effectiveEmail}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                documents.forEach { doc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = doc.id in picked,
                            onCheckedChange = {
                                if (doc.id in picked) picked.remove(doc.id) else picked.add(doc.id)
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(doc.fileName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                buildString {
                                    append(formatter.format(Date(doc.createdAt)))
                                    append(" · ")
                                    append(doc.bytes / 1024)
                                    append(" KB")
                                    if (doc.sentAt != 0L) append(" · έχει σταλεί")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Σημείωση στο μήνυμα (προαιρετικά)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = { onConfirm(documents.filter { it.id in picked }, note) },
            ) { Text("Αποστολή ${picked.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}

/**
 * Η υποχρεωτική οθόνη επιβεβαίωσης παραληπτών.
 *
 * Δείχνει **κάθε** διεύθυνση ονομαστικά, όχι πλήθος: το «αποστολή σε 42
 * πελάτες» δεν επιτρέπει να προσέξεις ότι ο ένας έχει λάθος email.
 */
@Composable
private fun BulkConfirmDialog(
    targets: List<Pair<ClientEntity, List<DocumentEntity>>>,
    skipped: List<ClientEntity>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Επιβεβαίωση παραληπτών") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Θα σταλεί ένα μήνυμα σε καθέναν από τους παρακάτω — ποτέ " +
                        "κοινοποίηση, ποτέ δεύτερος παραλήπτης.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                targets.forEach { (client, docs) ->
                    Text(
                        "${client.displayName} → ${client.effectiveEmail}  (${docs.size})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (skipped.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Παραλείπονται ${skipped.size} χωρίς email:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    skipped.forEach {
                        Text(
                            "· ${it.displayName} (${it.afm})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Αποστολή σε ${targets.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}
