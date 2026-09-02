package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.RunLogEntity
import gr.scanmydata.taxcenter.gdpr.Exports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ένα αρχείο, δύο όψεις.
 *
 * **Εκτελέσεις** — τι έτρεξε σε ποιον πελάτη, πόσο κράτησε, τι κατέβηκε και,
 * όταν απέτυχε, τι είπε η πύλη. Εργαλείο διάγνωσης.
 *
 * **Ενέργειες** — το αρχείο δραστηριοτήτων επεξεργασίας του άρθρου 30 ΓΚΠΔ.
 * Νομικό έγγραφο: δεν φιλτράρεται, δεν διαγράφεται, δεν περιέχει ποτέ τιμές
 * — μόνο ποιος, πότε, ποιου πελάτη δεδομένα και ποια ενέργεια.
 *
 * Ήταν δύο χωριστά σημεία (θέση μενού η μία, κουμπί στις Ρυθμίσεις η άλλη) και
 * κανείς δεν ήξερε ποιο να ανοίξει: και οι δύο απαντούν «τι έγινε και πότε».
 */
@Composable
fun LogsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }

    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Εκτελέσεις") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Ενέργειες") })
        }
        when (tab) {
            0 -> RunLogsTab(container)
            else -> AuditTab(container)
        }
    }
}

@Composable
private fun RunLogsTab(container: AppContainer) {
    val logs: List<RunLogEntity> by container.db.runLogs().observeRecent()
        .collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var onlyFailed by remember { mutableStateOf(false) }

    val shown = remember(logs, query, onlyFailed) {
        val q = query.trim().lowercase()
        logs.filter { log ->
            (!onlyFailed || !log.ok) &&
                (q.isBlank() || log.afm.contains(q) || log.configId.lowercase().contains(q))
        }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Αναζήτηση σε ΑΦΜ ή διαδικασία") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${shown.size} εκτελέσεις",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onlyFailed = !onlyFailed }) {
                Text(if (onlyFailed) "Όλες" else "Μόνο αποτυχίες")
            }
        }
        Spacer(Modifier.height(6.dp))

        LazyColumn {
            items(shown, key = { it.id }) { log ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${log.configId} — ${log.afm}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            AthensDates.stamp(log.startedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(if (log.ok) "✓ " else "✗ ")
                                append(if (log.ok) "${log.fileCount} αρχεία" else log.reason)
                                append(" · ${log.durationMs / 1000}s")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (log.ok) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.error,
                        )
                        if (log.lines.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                log.lines.lines().takeLast(6).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditTab(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries: List<AuditEntity> by container.db.audit().observeRecent()
        .collectAsState(initial = emptyList())
    var status by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Αρχείο δραστηριοτήτων επεξεργασίας — άρθρο 30 ΓΚΠΔ. Καταγράφει τι " +
                "έγινε και σε ποιον, ποτέ τιμές. Δεν διαγράφεται από την πολιτική " +
                "διατήρησης.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                status = "Δημιουργία αρχείου…"
                status = try {
                    val file = withContext(Dispatchers.IO) { Exports.auditCsv(context, container.db) }
                    Exports.share(context, file, "text/csv", "Αρχείο ενεργειών")
                    "Έτοιμο."
                } catch (e: Exception) {
                    "Απέτυχε: ${e.message}"
                }
            }
        }) { Text("Εξαγωγή σε CSV") }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn {
            items(entries, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row {
                        Text(
                            entry.action,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            AthensDates.stamp(entry.ts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    if (entry.afm.isNotBlank() || entry.detail.isNotBlank()) {
                        Text(
                            listOf(entry.afm, entry.detail).filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}
