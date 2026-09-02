package gr.scanmydata.taxcenter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import gr.scanmydata.taxcenter.data.ImportPreview
import gr.scanmydata.taxcenter.data.XlsxReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Εισαγωγή πελατών από το «Κωδικοί Υπόχρεων».
 *
 * **Τίποτα δεν γράφεται πριν την έγκριση.** Ο χρήστης βλέπει πρώτα ακριβώς τι θα
 * αλλάξει, με τα μυστικά μασκαρισμένα, και μετά αποφασίζει. Ο κανόνας έρχεται
 * από το `timologio-downloader`, όπου έχει αποδειχθεί σωστός σε πραγματική χρήση.
 */
@Composable
fun ImportScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportPreview.Result?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf<gr.scanmydata.taxcenter.data.ClientRepository.ImportResult?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = ""
            preview = null
            applied = null
            try {
                fileName = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "Excel" }
                preview = withContext(Dispatchers.IO) {
                    val sheets = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Δεν άνοιξε το αρχείο" }
                        XlsxReader.read(input)
                    }
                    ImportPreview.build(sheets, container.repository.existingAfms())
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Column(modifier.padding(16.dp)) {
        Text("Εισαγωγή από Excel", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Εξάγετε τους «Κωδικούς Υπόχρεων» από το λογιστικό σας πρόγραμμα ως .xlsx " +
                "και επιλέξτε το αρχείο. Τίποτα δεν αποθηκεύεται πριν το εγκρίνετε.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    // Το SAF δεν φιλτράρει αξιόπιστα τα .xlsx: ο πάροχος του Drive
                    // δηλώνει άλλον τύπο και τα αρχεία εμφανίζονται γκρίζα.
                    picker.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                enabled = !busy,
            ) { Text("Επιλογή αρχείου…") }

            if (fileName.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(fileName, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (busy) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Ανάγνωση…")
            }
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Card(colors = errorCardColors()) {
                Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        applied?.let { result ->
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Η εισαγωγή ολοκληρώθηκε", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${result.created} νέοι · ${result.updated} ενημερώσεις · " +
                            "${result.credentialsWritten} διαπιστευτήρια",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Διαγράψτε τώρα το αρχείο Excel από τη συσκευή: είναι το μόνο " +
                            "σημείο όπου οι κωδικοί υπάρχουν σε καθαρό κείμενο.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        preview?.let { result ->
            Spacer(Modifier.height(16.dp))
            PreviewSummary(result)

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            applied = withContext(Dispatchers.IO) {
                                container.repository.applyImport(result, fileName)
                            }
                            preview = null
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && result.rows.isNotEmpty(),
            ) { Text("Εισαγωγή ${result.rows.size} πελατών") }

            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(result.rows, key = { it.afm }) { row -> PreviewRow(row) }
            }
        }
    }
}

@Composable
private fun PreviewSummary(result: ImportPreview.Result) {
    Column {
        Text(result.summary, style = MaterialTheme.typography.titleSmall)
        if (result.skippedRows > 0) {
            Text(
                "${result.skippedRows} γραμμές χωρίς ΑΦΜ αγνοήθηκαν",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Οι στήλες που μοιάζουν χρήσιμες αλλά δεν είναι — ο χρήστης πρέπει να
        // ξέρει ότι τις είδαμε και τις αφήσαμε επίτηδες.
        result.ignoredColumns.forEach { (header, reason) ->
            Text(
                "Αγνοήθηκε η στήλη «$header»: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PreviewRow(row: ImportPreview.Row) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        row.afm,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                ActionChip(row.action)
            }

            val user = row.masked(Field.TAXIS_USER)
            val pass = row.masked(Field.TAXIS_PASS)
            if (user.isNotBlank() || pass.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "TAXISnet: ${user.ifBlank { "—" }} / ${pass.ifBlank { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            row.warnings.forEach { warning ->
                Text(
                    "⚠ $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ActionChip(action: ImportPreview.Action) {
    val (label, tint) = when (action) {
        ImportPreview.Action.NEW -> "ΝΕΟΣ" to MaterialTheme.colorScheme.primary
        ImportPreview.Action.UPDATE -> "ΕΝΗΜΕΡΩΣΗ" to MaterialTheme.colorScheme.secondary
        ImportPreview.Action.UNCHANGED -> "ΑΜΕΤΑΒΛΗΤΟΣ" to
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(labelColor = tint),
    )
}

@Composable
private fun errorCardColors() = androidx.compose.material3.CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
)
