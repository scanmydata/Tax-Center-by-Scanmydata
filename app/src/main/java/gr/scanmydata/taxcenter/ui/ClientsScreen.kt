package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Λίστα πελατών, με αναζήτηση και αποστολή στοιχείων.
 */
@Composable
fun ClientsScreen(
    container: AppContainer,
    onOpenClient: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val clients: List<ClientEntity> by container.repository.observeClients()
        .collectAsState(initial = emptyList())

    var query by remember { mutableStateOf("") }
    var sendTarget by remember { mutableStateOf<ClientEntity?>(null) }
    var status by remember { mutableStateOf("") }

    val filtered = remember(clients, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) clients
        else clients.filter { it.afm.contains(q) || it.displayName.lowercase().contains(q) }
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
        Text(
            "${filtered.size} πελάτες",
            style = MaterialTheme.typography.labelMedium,
        )

        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(filtered, key = { it.id }) { client ->
                ClientRow(
                    client = client,
                    onOpen = { onOpenClient(client.id) },
                    onSendDetails = { sendTarget = client },
                )
            }
        }
    }

    sendTarget?.let { client ->
        SendOwnDetailsDialog(
            client = client,
            defaultIncludeSecrets = container.settings.includePasswordsInClientEmail,
            onDismiss = { sendTarget = null },
            onConfirm = { includeSecrets ->
                sendTarget = null
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
}

@Composable
private fun ClientRow(client: ClientEntity, onOpen: () -> Unit, onSendDetails: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(client.displayName, style = MaterialTheme.typography.titleSmall)
                Text(client.afm, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    client.effectiveEmail.ifBlank { "— χωρίς email —" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (client.effectiveEmail.isBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            TextButton(onClick = onSendDetails, enabled = client.effectiveEmail.isNotBlank()) {
                Text("Στοιχεία")
            }
        }
    }
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
                    "Θα σταλούν το ΑΦΜ, το ΑΜΚΑ και το όνομα χρήστη TAXISnet.",
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
