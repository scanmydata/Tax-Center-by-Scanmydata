package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.mail.MailTemplateStore

/** Ποιο από τα δύο πρότυπα επεξεργάζεται. */
enum class TemplateKind(val title: String) {
    CREDENTIALS("Πρότυπο — στοιχεία & κωδικοί πελάτη"),
    DOCUMENTS("Πρότυπο — αποστολή εντύπων"),
}

/**
 * Επεξεργασία ενός προτύπου email.
 *
 * Τα δυναμικά πεδία είναι **διακόπτες**, όχι placeholders μέσα στο κείμενο. Ένα
 * ξεχασμένο `{{συνθηματικό}}` σε ελεύθερο κείμενο θα έστελνε τον κωδικό κάθε
 * πελάτη χωρίς να το προσέξει κανείς· ένας διακόπτης που λέει «Συνθηματικό
 * TAXISnet» διαβάζεται με μια ματιά.
 *
 * Τα λίγα placeholders που υπάρχουν αφορούν μόνο κείμενο, όχι μυστικά.
 */
@Composable
fun TemplateEditorDialog(
    kind: TemplateKind,
    store: MailTemplateStore,
    onDismiss: () -> Unit,
) {
    val initial = remember(kind) {
        when (kind) {
            TemplateKind.CREDENTIALS -> store.credentials
            TemplateKind.DOCUMENTS -> store.documents
        }
    }

    var subject by remember(initial) { mutableStateOf(initial.subject) }
    var intro by remember(initial) { mutableStateOf(initial.intro) }
    var closing by remember(initial) { mutableStateOf(initial.closing) }
    val fields = remember(initial) { mutableStateListOf<String>().apply { addAll(initial.fields) } }

    fun toggle(key: String) {
        if (key in fields) fields.remove(key) else fields.add(key)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Θέμα") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it },
                    label = { Text("Εισαγωγικό κείμενο") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = closing,
                    onValueChange = { closing = it },
                    label = { Text("Καταληκτικό κείμενο") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Διαθέσιμα πεδία κειμένου", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${MailTemplateStore.PLACEHOLDER_NAME} — η επωνυμία του πελάτη\n" +
                                "${MailTemplateStore.PLACEHOLDER_AFM} — το ΑΦΜ του" +
                                if (kind == TemplateKind.DOCUMENTS) {
                                    "\n${MailTemplateStore.PLACEHOLDER_COUNT} — πλήθος συνημμένων"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text("Τι περιλαμβάνεται", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))

                when (kind) {
                    TemplateKind.CREDENTIALS -> {
                        MailTemplateStore.CredentialField.entries.forEach { field ->
                            FieldSwitch(
                                label = field.label,
                                checked = field.key in fields,
                                warning = if (field.sensitive) {
                                    "Φεύγει μόνο αν το ζητήσεις **και** στη συγκεκριμένη αποστολή."
                                } else {
                                    ""
                                },
                                onToggle = { toggle(field.key) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Η προειδοποίηση ασφαλείας στα μηνύματα με κωδικούς δεν " +
                                "απενεργοποιείται: είναι η μόνη ένδειξη που παίρνει ο " +
                                "πελάτης ότι κρατά κάτι που πρέπει να σβήσει.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }

                    TemplateKind.DOCUMENTS -> {
                        MailTemplateStore.DocumentField.entries.forEach { field ->
                            FieldSwitch(
                                label = field.label,
                                checked = field.key in fields,
                                warning = "",
                                onToggle = { toggle(field.key) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = MailTemplateStore.Template(
                    subject = subject,
                    intro = intro,
                    closing = closing,
                    fields = fields.toSet(),
                )
                when (kind) {
                    TemplateKind.CREDENTIALS -> store.credentials = updated
                    TemplateKind.DOCUMENTS -> store.documents = updated
                }
                onDismiss()
            }) { Text("Αποθήκευση") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Άκυρο") }
                TextButton(onClick = {
                    when (kind) {
                        TemplateKind.CREDENTIALS -> store.resetCredentials()
                        TemplateKind.DOCUMENTS -> store.resetDocuments()
                    }
                    onDismiss()
                }) { Text("Επαναφορά") }
            }
        },
    )
}

@Composable
private fun FieldSwitch(
    label: String,
    checked: Boolean,
    warning: String,
    onToggle: () -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = { onToggle() })
            Text(
                "  $label",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (checked && warning.isNotBlank()) {
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp),
            )
        }
    }
}
