package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import gr.scanmydata.taxcenter.engine.DocumentNaming
import gr.scanmydata.taxcenter.ui.theme.OkGreen
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
    /** Άνοιγμα **άλλης** καρτέλας — σήμερα μόνο του συζύγου. */
    onOpenClient: (Long) -> Unit = {},
    initialTab: Int = 0,
    modifier: Modifier = Modifier,
) {
    var tab by remember(clientId, initialTab) { mutableStateOf(initialTab) }
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
            0 -> ClientEditScreen(
                container = container,
                clientId = clientId,
                onDone = onDone,
                onOpenClient = onOpenClient,
            )
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
            // Το «Όλα» έχει νόημα μόνο αφού ξεκινήσει επιλογή. Πριν από αυτό
            // είναι ένα κουμπί που επιλέγει τα πάντα με ένα πάτημα, δίπλα σε ένα
            // εικονίδιο διαγραφής.
            if (picked.isNotEmpty()) {
                TextButton(onClick = {
                    if (picked.size == documents.size) picked.clear()
                    else {
                        picked.clear()
                        picked.addAll(documents.map { it.id })
                    }
                }) { Text(if (picked.size == documents.size) "Κανένα" else "Όλα") }
            }
            if (picked.isEmpty()) {
                TextButton(onClick = onFetch) { Text("Λήψη") }
            } else {
                // Ίδια θέση και ίδιο εικονίδιο με τη λίστα πελατών και τα
                // Έγγραφα: η διαγραφή πρέπει να είναι στο ίδιο σημείο σε κάθε
                // οθόνη, αλλιώς κάποια στιγμή πατιέται κατά λάθος.
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Διαγραφή ${picked.size} εντύπων",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
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
                OutlinedButton(onClick = { picked.clear() }) { Text("Άκυρο") }
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

    // Φίλτρο περιόδου. Σε πελάτη με δύο χρόνια ιστορικό η λίστα είναι δεκάδες
    // κάρτες, και η ερώτηση είναι σχεδόν πάντα «τι του έστειλα *τότε*».
    var year by remember(clientId) { mutableStateOf("") }
    var month by remember(clientId) { mutableStateOf("") }

    val years = remember(sends) {
        sends.map { AthensDates.year(it.sentAt) }.distinct().sortedDescending()
    }
    val shown = remember(sends, year, month) {
        sends.filter { send ->
            (year.isBlank() || AthensDates.year(send.sentAt) == year) &&
                (month.isBlank() || AthensDates.month(send.sentAt) == month)
        }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        if (sends.isEmpty()) {
            Text(
                "Δεν έχει σταλεί τίποτα σε αυτόν τον πελάτη.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeriodPicker(
                label = "Έτος",
                value = year,
                options = years,
                modifier = Modifier.weight(1f),
                onPick = { year = it },
            )
            PeriodPicker(
                label = "Μήνας",
                value = month,
                options = MONTH_VALUES,
                display = { MONTH_NAMES[it] ?: it },
                modifier = Modifier.weight(1f),
                onPick = { month = it },
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (shown.size == sends.size) {
                    "${sends.size} αποστολές"
                } else {
                    "${shown.size} από ${sends.size} αποστολές"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (year.isNotBlank() || month.isNotBlank()) {
                TextButton(onClick = { year = ""; month = "" }) { Text("Όλες") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                "Καμία αποστολή σε αυτή την περίοδο.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        LazyColumn {
            items(shown, key = { it.id }) { send ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Πράσινο τικ ή κόκκινο θαυμαστικό, πριν από τον
                            // τίτλο: η μόνη ερώτηση που έχει κανείς κοιτώντας
                            // αυτή τη λίστα είναι «έφτασε;».
                            Icon(
                                if (send.failed) Icons.Filled.Error else Icons.Filled.CheckCircle,
                                contentDescription = if (send.failed) "Απέτυχε" else "Στάλθηκε",
                                tint = if (send.failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    OkGreen
                                },
                                modifier = Modifier.size(20.dp).padding(end = 2.dp),
                            )
                            Spacer(Modifier.size(8.dp))
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
                                // Ίδια ονόματα με αυτά που είδε ο πελάτης στο
                                // μήνυμα — αλλιώς οι δύο λίστες δεν συγκρίνονται.
                                send.items.lines().filter { it.isNotBlank() }
                                    .joinToString("\n") { "• " + DocumentNaming.line(it) },
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

/** Οι μήνες, με τη σειρά του ημερολογίου. */
private val MONTH_VALUES = (1..12).map { it.toString().padStart(2, '0') }

private val MONTH_NAMES = mapOf(
    "01" to "Ιανουάριος", "02" to "Φεβρουάριος", "03" to "Μάρτιος",
    "04" to "Απρίλιος", "05" to "Μάιος", "06" to "Ιούνιος",
    "07" to "Ιούλιος", "08" to "Αύγουστος", "09" to "Σεπτέμβριος",
    "10" to "Οκτώβριος", "11" to "Νοέμβριος", "12" to "Δεκέμβριος",
)

/**
 * Πτυσσόμενη επιλογή περιόδου, με «όλα» πάντα πρώτο.
 *
 * Κενή τιμή σημαίνει «χωρίς φίλτρο» και είναι η προεπιλογή: ένα φίλτρο που
 * ξεκινά ενεργό κρύβει δεδομένα πριν καν το ζητήσει κανείς.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPicker(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    display: (String) -> String = { it },
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = if (value.isBlank()) "όλα" else display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("όλα") },
                onClick = { onPick(""); expanded = false },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = { onPick(option); expanded = false },
                )
            }
        }
    }
}
