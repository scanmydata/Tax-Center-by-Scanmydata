package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.sched.DebtWatch

/** Η περίληψη της ομάδας «Οφειλές», όσο είναι κλειστή στις Ρυθμίσεις. */
fun debtWatchSummary(settings: Settings): String {
    val frequency = DebtWatch.Frequency.of(settings.debtWatchFrequency)
    val clients = settings.debtWatchClients.size
    return when {
        frequency == DebtWatch.Frequency.OFF -> "αυτόματη ενημέρωση κλειστή"
        clients == 0 -> frequency.label.lowercase() + " · κανένας πελάτης"
        else -> frequency.label.lowercase() + ", " +
            settings.debtWatchHour.toString().padStart(2, '0') + ":00 · " +
            (if (clients == 1) "1 πελάτης" else "$clients πελάτες")
    }
}

/**
 * Ο χρονοπρογραμματισμός της ενημέρωσης οφειλών, και το πρότυπο του μηνύματος.
 *
 * Κάθε κείμενο εδώ λέει **τι θα συμβεί** και όχι τι ρυθμίζεται: το πρόγραμμα
 * ανοίγει πραγματικές συνδέσεις στο TAXISnet με κωδικούς πελατών ενώ κανείς δεν
 * κοιτά, και ο λογιστής πρέπει να ξέρει τι υπόγραψε πριν ανοίξει τον διακόπτη.
 */
@Composable
fun DebtWatchSettings(
    container: AppContainer,
    onEditTemplate: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val settings = container.settings
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    var frequency by remember { mutableStateOf(DebtWatch.Frequency.of(settings.debtWatchFrequency)) }
    var hour by remember { mutableStateOf(settings.debtWatchHour) }
    var watched by remember { mutableStateOf(settings.debtWatchClients) }
    var picking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun commit() {
        settings.debtWatchFrequency = frequency.name
        settings.debtWatchHour = hour
        settings.debtWatchClients = watched
        DebtWatch.apply(context, settings)
        onChanged()
    }

    Text("Αυτόματη ενημέρωση καρτελών οφειλών", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(2.dp))
    Text(
        "Η εφαρμογή συνδέεται μόνη της στην ΑΑΔΕ και στο ΚΕΑΟ για τους πελάτες που " +
            "διαλέγεις και ανανεώνει την καρτέλα «Οφειλές» τους. Διαβάζει ποσά, δόσεις " +
            "και ταυτότητες — δεν κατεβάζει έντυπα και δεν στέλνει τίποτα.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )
    Spacer(Modifier.height(8.dp))

    DebtWatch.Frequency.entries.forEach { option ->
        Row(
            Modifier.fillMaxWidth().clickable { frequency = option; commit() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == frequency, onClick = { frequency = option; commit() })
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ώρα", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { hour = (hour + 23) % 24; commit() }) { Text("−") }
        Text(
            hour.toString().padStart(2, '0') + ":00",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = { hour = (hour + 1) % 24; commit() }) { Text("+") }
    }
    Text(
        "Ώρα Ελλάδας, και περίπου: το Android εκτελεί τις εργασίες παρασκηνίου όταν " +
            "υπάρχει δίκτυο και η συσκευή δεν κοιμάται βαθιά, όχι στο λεπτό. Μετά από " +
            "κάθε αλλαγή εδώ, η πρώτη εκτέλεση γίνεται την επόμενη φορά που θα έρθει " +
            "αυτή η ώρα.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (watched.size) {
                0 -> "Κανένας πελάτης στο πρόγραμμα"
                1 -> "1 πελάτης στο πρόγραμμα"
                else -> "${watched.size} πελάτες στο πρόγραμμα"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { picking = true }) { Text("Επιλογή") }
    }
    Text(
        "Προστίθενται και από την καρτέλα «Οφειλές» του κάθε πελάτη.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )

    Spacer(Modifier.height(10.dp))
    Text(
        "Αν οι κωδικοί ενός πελάτη απορριφθούν, βγαίνει μόνος του από το πρόγραμμα: " +
            "το GSIS κλειδώνει τον λογαριασμό μετά από επανειλημμένες αποτυχίες, και μια " +
            "εργασία που ξαναδοκιμάζει κάθε πρωί θα τον κλείδωνε χωρίς να το δει κανείς. " +
            "Η ενημέρωση περιμένει επίσης όσο τρέχει λήψη που έβαλες εσύ.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            enabled = watched.isNotEmpty(),
            onClick = {
                DebtWatch.runNow(context)
                status = "Ξεκίνησε στο παρασκήνιο. Το αποτέλεσμα θα φανεί εδώ και στις καρτέλες."
            },
        ) { Text("Εκτέλεση τώρα") }
    }
    if (status.isNotBlank()) {
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    val lastRun = settings.debtWatchLastRun
    if (lastRun != 0L) {
        Text(
            "Τελευταία εκτέλεση " + AthensDates.stamp(lastRun) + ": " + settings.debtWatchLastSummary,
            style = MaterialTheme.typography.bodySmall,
            color = dim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(10.dp))
    Text("Μήνυμα οφειλής (SMS / Viber)", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(2.dp))
    Text(
        "Το κείμενο που προτείνεται όταν στέλνεις μια οφειλή από την καρτέλα του πελάτη: " +
            "ονομασία, ποσό και λήξη της δόσης, ταυτότητα πληρωμής. Το βλέπεις και το " +
            "διορθώνεις πριν φύγει.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onEditTemplate) { Text("Πρότυπο μηνύματος") }

    if (picking) {
        WatchedClientsDialog(
            container = container,
            initial = watched,
            onDismiss = { picking = false },
            onConfirm = { chosen ->
                picking = false
                watched = chosen
                commit()
            },
        )
    }
}

/**
 * Ποιοι πελάτες μπαίνουν στο πρόγραμμα.
 *
 * Λίστα με ονόματα, όχι «όλοι» με έναν διακόπτη: κάθε τσεκ είναι ένας
 * λογαριασμός TAXISnet που θα ανοίγεται χωρίς επίβλεψη, και αυτό αξίζει να το
 * αποφασίζει κανείς πελάτη-πελάτη.
 */
@Composable
private fun WatchedClientsDialog(
    container: AppContainer,
    initial: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }

    val shown = remember(clients, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) clients
        else clients.filter { it.afm.contains(q) || it.displayName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Πελάτες στο πρόγραμμα (${chosen.size})") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Αναζήτηση σε ΑΦΜ ή επωνυμία") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = { chosen = chosen + shown.map { it.id } }) { Text("Όλοι οι εμφανιζόμενοι") }
                    TextButton(onClick = { chosen = emptySet() }) { Text("Κανένας") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { client ->
                        val on = client.id in chosen
                        val toggle = { chosen = if (on) chosen - client.id else chosen + client.id }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { toggle() })
                            Column(Modifier.weight(1f)) {
                                Text(client.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    client.afm,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text("Αποθήκευση") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}
