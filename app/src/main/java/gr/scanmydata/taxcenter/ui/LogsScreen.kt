package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.gdpr.Exports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Το αρχείο δραστηριοτήτων επεξεργασίας — άρθρο 30 ΓΚΠΔ.
 *
 * Καταγράφει **τι** έγινε και **σε ποιον**: ποιος, πότε, ποιου πελάτη δεδομένα,
 * ποια ενέργεια. Ποτέ τιμές — καμία γραμμή δεν περιέχει κωδικό.
 *
 * Δεν είναι θέση του μενού: ανοίγει από τις Ρυθμίσεις. Δεν είναι καθημερινή
 * δουλειά — είναι κάτι που ανοίγεις όταν σου το ζητήσουν.
 *
 * **Το ιστορικό εκτελέσεων αφαιρέθηκε από την οθόνη.** Οι γραμμές του log του
 * engine περιέχουν τα URL των πυλών — ολόκληρο τον χάρτη των endpoints που
 * χρησιμοποιεί η εφαρμογή. Δεν είναι μυστικό με την αυστηρή έννοια, αλλά δεν
 * έχει καμία αξία για τον λογιστή και κάθε λόγο να μη φαίνεται σε στιγμιότυπο
 * οθόνης. Οι εκτελέσεις εξακολουθούν να καταγράφονται στη βάση (τι έτρεξε, σε
 * ποιον, αν πέτυχε)· οι **αναλυτικές γραμμές** κρατιούνται μόνο όταν είναι
 * ανοιχτά τα διαγνωστικά.
 */
@Composable
fun LogsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries: List<AuditEntity> by container.db.audit().observeRecent()
        .collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }

    val shown = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) entries
        else entries.filter {
            it.afm.contains(q) || it.action.lowercase().contains(q) ||
                it.detail.lowercase().contains(q)
        }
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Ποιος, πότε, ποιου πελάτη δεδομένα, ποια ενέργεια. Ποτέ τιμές. Δεν " +
                "θίγεται από την πολιτική διατήρησης.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Αναζήτηση σε ΑΦΜ ή ενέργεια") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            OutlinedButton(onClick = { confirmWipe = true }) { Text("Εκκαθάριση") }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(10.dp))
        Text("${shown.size} εγγραφές", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { entry ->
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

    if (confirmWipe) {
        WipeDialog(
            onDismiss = { confirmWipe = false },
            onWipe = { months ->
                confirmWipe = false
                scope.launch {
                    val cutoff = if (months == 0) {
                        System.currentTimeMillis()
                    } else {
                        System.currentTimeMillis() - months * 30L * 24 * 60 * 60 * 1000
                    }
                    val removed = withContext(Dispatchers.IO) {
                        val count = container.db.audit().wipeBefore(cutoff)
                        container.db.runLogs().wipe()
                        // Η ίδια η εκκαθάριση καταγράφεται: αλλιώς το αρχείο θα
                        // έλεγε ψέματα με τη σιωπή του.
                        container.db.audit().log(
                            AuditEntity(
                                ts = System.currentTimeMillis(),
                                action = "AUDIT_WIPE",
                                detail = if (months == 0) {
                                    "εκκαθάριση όλων ($count εγγραφές)"
                                } else {
                                    "εκκαθάριση παλαιότερων $months μηνών ($count εγγραφές)"
                                },
                            ),
                        )
                        count
                    }
                    status = "Διαγράφηκαν $removed εγγραφές."
                }
            },
        )
    }
}

/**
 * Η εκκαθάριση του αρχείου, με προειδοποίηση που δεν μασάει τα λόγια της.
 *
 * Το αρχείο του άρθρου 30 είναι ακριβώς αυτό που αποδεικνύει σε έλεγχο τι έγινε
 * και πότε. Η δυνατότητα υπάρχει επειδή ο υπεύθυνος επεξεργασίας ορίζει την
 * πολιτική διατήρησής του — όχι επειδή είναι αθώα ενέργεια.
 */
@Composable
private fun WipeDialog(onDismiss: () -> Unit, onWipe: (months: Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Εκκαθάριση αρχείου") },
        text = {
            Column {
                Text(
                    "Το αρχείο ενεργειών είναι το τεκμήριο συμμόρφωσης του γραφείου " +
                        "(άρθρο 30 ΓΚΠΔ): δείχνει ποιανού δεδομένα άγγιξε ποιος και " +
                        "πότε. Διάγραψέ το μόνο αν αυτό επιβάλλει η δική σου πολιτική " +
                        "διατήρησης.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Μαζί καθαρίζεται και το ιστορικό εκτελέσεων του engine.\n\n" +
                        "Η ίδια η εκκαθάριση καταγράφεται — το αρχείο δεν αδειάζει " +
                        "σιωπηλά.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onWipe(0) }) {
                Text("Διαγραφή όλων", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Άκυρο") }
                TextButton(onClick = { onWipe(24) }) { Text("Άνω των 24 μηνών") }
            }
        },
    )
}
