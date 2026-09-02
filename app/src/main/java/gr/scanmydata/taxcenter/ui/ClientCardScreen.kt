package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Η καρτέλα ενός πελάτη, σε τρεις όψεις.
 *
 * **Στοιχεία** — η φόρμα με τα διαπιστευτήρια και την άντληση από το Μητρώο.
 * **Έγγραφα** — τι έχει κατέβει, με άνοιγμα, αποστολή και διαγραφή.
 * **Αποστολές** — τι στάλθηκε, πότε και τι ακριβώς περιείχε.
 *
 * Ήταν τρεις διαφορετικές οθόνες. Όταν ο πελάτης τηλεφωνεί και ρωτά «μου
 * στείλατε το Ε1;», η απάντηση χρειάζεται και τα τρία — και το να ψάχνεται σε
 * τρία μέρη ήταν ο λόγος που κανείς δεν κοίταζε το ιστορικό.
 */
@Composable
fun ClientCardScreen(
    container: AppContainer,
    clientId: Long,
    onDone: () -> Unit,
    onFetchFor: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var client by remember { mutableStateOf<ClientEntity?>(null) }

    LaunchedEffect(clientId) {
        client = withContext(Dispatchers.IO) { container.db.clients().byId(clientId) }
    }

    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Στοιχεία") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Έγγραφα") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Αποστολές") })
        }
        when (tab) {
            0 -> ClientEditScreen(container = container, clientId = clientId, onDone = onDone)
            1 -> ClientDocumentsTab(
                container = container,
                client = client,
                onFetch = { onFetchFor(clientId) },
            )
            else -> ClientSendsTab(container = container, clientId = clientId)
        }
    }
}

// ---------------------------------------------------------------- έγγραφα

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClientDocumentsTab(
    container: AppContainer,
    client: ClientEntity?,
    onFetch: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val documents: List<DocumentEntity> by remember(client?.id) {
        container.db.documents().observeForClient(client?.id ?: 0L)
    }.collectAsState(initial = emptyList())

    val picked = remember { mutableStateListOf<Long>() }
    var status by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    val selected = remember(documents, picked.toList()) {
        documents.filter { it.id in picked }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        if (documents.isEmpty()) {
            Text(
                "Δεν έχει κατέβει κανένα έντυπο για αυτόν τον πελάτη.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onFetch) { Text("Λήψη εντύπων") }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (picked.isEmpty()) "${documents.size} έντυπα"
                else "${picked.size} επιλεγμένα",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                if (picked.size == documents.size) picked.clear()
                else {
                    picked.clear()
                    picked.addAll(documents.map { it.id })
                }
            }) { Text(if (picked.size == documents.size) "Κανένα" else "Όλα") }
        }
        Text(
            "Πάτημα ανοίγει το έντυπο· παρατεταμένο πάτημα το επιλέγει.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        if (status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(documents, key = { it.id }) { document ->
                DocumentRow(
                    document = document,
                    checked = document.id in picked,
                    selecting = picked.isNotEmpty(),
                    onClick = {
                        if (picked.isNotEmpty()) {
                            if (document.id in picked) picked.remove(document.id)
                            else picked.add(document.id)
                        } else {
                            status = DocumentActions.open(context, document)
                        }
                    },
                    onLongClick = {
                        if (document.id in picked) picked.remove(document.id)
                        else picked.add(document.id)
                    },
                )
            }
        }

        if (picked.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    enabled = !sending && client?.effectiveEmail?.isNotBlank() == true,
                    onClick = {
                        val target = client ?: return@Button
                        scope.launch {
                            sending = true
                            status = "Αποστολή ${selected.size} εντύπων…"
                            status = try {
                                val token = authorizer.accessToken()
                                val send = withContext(Dispatchers.IO) {
                                    container.mail.sendDocuments(token, target, selected)
                                }
                                if (send.failed) "Απέτυχε: ${send.error}"
                                else "Στάλθηκαν ${selected.size} έντυπα."
                            } catch (e: GoogleAuthorizer.ConsentRequired) {
                                "Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις."
                            } catch (e: Exception) {
                                "Απέτυχε: ${e.message}"
                            }
                            sending = false
                            picked.clear()
                        }
                    },
                ) { Text("Αποστολή ${picked.size}") }
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Text("Διαγραφή ${picked.size}")
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Διαγραφή ${picked.size} εντύπων") },
            text = {
                Text(
                    "Τα αρχεία σβήνονται από τη συσκευή και δεν ανακτώνται. Η " +
                        "καρτέλα του πελάτη και το ιστορικό αποστολών δεν θίγονται — " +
                        "αν χρειαστούν ξανά, κατεβαίνουν από την πύλη.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val count = withContext(Dispatchers.IO) {
                            DocumentActions.delete(context, container.db, selected)
                        }
                        picked.clear()
                        status = "Διαγράφηκαν $count έντυπα."
                    }
                }) { Text("Διαγραφή", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Άκυρο") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentRow(
    document: DocumentEntity,
    checked: Boolean,
    selecting: Boolean,
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
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
            }
            Column(Modifier.weight(1f)) {
                Text(document.fileName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(AthensDates.stamp(document.createdAt))
                        append("  ·  ").append(document.bytes / 1024).append(" KB")
                        if (document.year.isNotBlank()) append("  ·  ").append(document.year)
                        if (document.sentAt != 0L) append("  ·  στάλθηκε")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

// -------------------------------------------------------------- αποστολές

@Composable
private fun ClientSendsTab(container: AppContainer, clientId: Long) {
    val sends: List<SendEntity> by remember(clientId) {
        container.db.sends().observeForClient(clientId)
    }.collectAsState(initial = emptyList())

    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        if (sends.isEmpty()) {
            Text(
                "Δεν έχει σταλεί τίποτα σε αυτόν τον πελάτη.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Text("${sends.size} αποστολές", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(sends, key = { it.id }) { send ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (send.kind) {
                                    SendEntity.KIND_CREDENTIALS -> "Στοιχεία & κωδικοί"
                                    else -> "Φορολογικά έντυπα"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                AthensDates.stamp(send.sentAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Text(send.toEmail, style = MaterialTheme.typography.bodySmall)
                        if (send.items.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                send.items.lines().filter { it.isNotBlank() }
                                    .joinToString("\n") { "• $it" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            )
                        }
                        if (send.failed) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Απέτυχε: ${send.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
