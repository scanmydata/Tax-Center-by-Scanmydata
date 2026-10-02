package gr.scanmydata.taxcenter.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.sched.RequestWatch

/** Η περίληψη της ομάδας «Αιτήματα ΑΑΔΕ», όσο είναι κλειστή στις Ρυθμίσεις. */
fun requestWatchSummary(settings: Settings): String {
    val clients = settings.requestWatchClients.size
    return when {
        !settings.requestWatchDaily -> "καθημερινή παρακολούθηση κλειστή"
        clients == 0 -> "κάθε μέρα · κανένας πελάτης"
        else -> "κάθε μέρα, " + settings.requestWatchHour.toString().padStart(2, '0') + ":00 · " +
            (if (clients == 1) "1 πελάτης" else "$clients πελάτες")
    }
}

/**
 * Η καθημερινή παρακολούθηση των αιτημάτων ΑΑΔΕ.
 *
 * Όπως και στις οφειλές, κάθε κείμενο εδώ λέει **τι θα συμβεί**: ο διακόπτης
 * ανοίγει πραγματικές συνδέσεις στο TAXISnet με κωδικούς πελατών, κάθε μέρα,
 * χωρίς άνθρωπο μπροστά.
 */
@Composable
fun RequestWatchSettings(
    container: AppContainer,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val settings = container.settings
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    var daily by remember { mutableStateOf(settings.requestWatchDaily) }
    var hour by remember { mutableStateOf(settings.requestWatchHour) }
    var watched by remember { mutableStateOf(settings.requestWatchClients) }
    var picking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    // Χωρίς την άδεια, η παρακολούθηση θα δούλευε και δεν θα έλεγε τίποτα σε
    // κανέναν — δηλαδή ακριβώς ό,τι υπάρχει για να αποτρέψει. Τη ζητάμε τη
    // στιγμή που ανοίγει ο διακόπτης, όταν είναι προφανές γιατί.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            status = "Οι ειδοποιήσεις είναι κλειστές για την εφαρμογή: ο έλεγχος θα γίνεται, " +
                "αλλά δεν θα έρχεται ειδοποίηση. Άνοιξέ τες από τις ρυθμίσεις του Android."
        }
    }

    fun commit() {
        settings.requestWatchDaily = daily
        settings.requestWatchHour = hour
        settings.requestWatchClients = watched
        RequestWatch.apply(context, settings)
        onChanged()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Καθημερινή άντληση από την ΑΑΔΕ", style = MaterialTheme.typography.titleSmall)
            Text(
                "Η εφαρμογή συνδέεται μία φορά τη μέρα στην ΑΑΔΕ για τους πελάτες που " +
                    "διαλέγεις, διαβάζει «Τα Αιτήματά μου» και, αν απαντήθηκε κάτι, στέλνει " +
                    "ειδοποίηση στο κινητό. Δεν υποβάλλει και δεν απαντά τίποτα.",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
        }
        Switch(
            checked = daily,
            onCheckedChange = { on ->
                daily = on
                commit()
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ώρα", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { hour = (hour + 23) % 24; commit() }) { Text("−") }
        Text(hour.toString().padStart(2, '0') + ":00", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { hour = (hour + 1) % 24; commit() }) { Text("+") }
    }
    Text(
        "Ώρα Ελλάδας, και περίπου — το Android εκτελεί τις εργασίες παρασκηνίου όταν " +
            "υπάρχει δίκτυο και η συσκευή δεν κοιμάται βαθιά. Αν την ίδια ώρα τρέχει η " +
            "ενημέρωση οφειλών ή δική σου λήψη, ο έλεγχος περιμένει τη σειρά του.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (watched.size) {
                0 -> "Κανένας πελάτης στην παρακολούθηση"
                1 -> "1 πελάτης στην παρακολούθηση"
                else -> "${watched.size} πελάτες στην παρακολούθηση"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { picking = true }) { Text("Επιλογή") }
    }
    Text(
        "Προστίθενται και από την καρτέλα «Αιτήματα» του κάθε πελάτη. Βάλε όσους έχουν " +
            "αίτημα σε εκκρεμότητα — κάθε πελάτης στη λίστα είναι μία σύνδεση τη μέρα.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )

    Spacer(Modifier.height(10.dp))
    Text(
        "Η ειδοποίηση γράφει μόνο πόσα αιτήματα απαντήθηκαν, όχι σε ποιους: φαίνεται και " +
            "στην οθόνη κλειδώματος. Τα ονόματα εμφανίζονται στην κορυφή της λίστας " +
            "πελατών μόλις ανοίξεις την εφαρμογή. Αν οι κωδικοί ενός πελάτη απορριφθούν, " +
            "βγαίνει μόνος του από την παρακολούθηση, για να μην κλειδωθεί ο λογαριασμός του.",
        style = MaterialTheme.typography.bodySmall,
        color = dim,
    )

    Spacer(Modifier.height(10.dp))
    Button(
        enabled = watched.isNotEmpty(),
        onClick = {
            RequestWatch.runNow(context)
            status = "Ο έλεγχος ξεκίνησε στο παρασκήνιο. Το αποτέλεσμα θα φανεί εδώ."
        },
    ) { Text("Έλεγχος τώρα") }
    if (status.isNotBlank()) {
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    val lastRun = settings.requestWatchLastRun
    if (lastRun != 0L) {
        Text(
            "Τελευταίος έλεγχος " + AthensDates.stamp(lastRun) + ": " + settings.requestWatchLastSummary,
            style = MaterialTheme.typography.bodySmall,
            color = dim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

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
