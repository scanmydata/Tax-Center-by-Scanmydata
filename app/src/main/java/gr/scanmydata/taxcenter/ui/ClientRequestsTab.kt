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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestsRefresh
import gr.scanmydata.taxcenter.requests.RequestsStore
import gr.scanmydata.taxcenter.sched.RequestWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Η καρτέλα **«Αιτήματα»** του πελάτη: τι έχει ζητήσει από την ΑΑΔΕ, και τι του
 * απάντησαν.
 *
 * Είναι τα «Αιτήματά μου» της πύλης, όπως τα βλέπει ο ίδιος ο πελάτης όταν
 * συνδεθεί. Η διαφορά είναι ότι εδώ **φαίνεται τι είναι καινούργιο**: ό,τι
 * απαντήθηκε από την τελευταία φορά που το άνοιξε κάποιος είναι σημειωμένο, και
 * μένει σημειωμένο ώσπου να πατηθεί.
 *
 * Η οθόνη δείχνει πάντα **πότε** έγινε ο τελευταίος έλεγχος. «Καμία απάντηση»
 * χωρίς ημερομηνία δεν σημαίνει τίποτα.
 */
@Composable
internal fun ClientRequestsTab(
    container: AppContainer,
    client: ClientEntity?,
) {
    if (client == null) {
        Text("Φόρτωση…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settings
    val fetchState by container.fetch.state.collectAsState()

    var reload by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var watched by remember(client.id) { mutableStateOf(client.id in settings.requestWatchClients) }
    val expanded = remember(client.id) { mutableStateListOf<String>() }

    val snapshot by produceState<RequestsStore.Snapshot?>(null, client.afm, reload) {
        value = withContext(Dispatchers.IO) { RequestsStore.load(context.filesDir, client.afm) }
    }

    // Και ο χρονοπρογραμματιστής γράφει εδώ· όταν τελειώσει μια λήψη, ξαναδιαβάζουμε.
    LaunchedEffect(fetchState.running) {
        if (!fetchState.running) reload++
    }

    /** Σβήνει τη σήμανση «νέο» — για ένα αίτημα, ή για όλα όταν [ids] είναι κενό. */
    fun seen(ids: Set<String>) {
        scope.launch {
            val left = withContext(Dispatchers.IO) { RequestsStore.markSeen(context.filesDir, client.afm, ids) }
            if (left.isEmpty()) settings.requestWatchFresh = settings.requestWatchFresh - client.id
            reload++
        }
    }

    val busy = refreshing || fetchState.running
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val current = snapshot

    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp)) {
                    when {
                        current == null -> Text("Φόρτωση…", style = MaterialTheme.typography.bodyMedium)

                        current.at == 0L -> Text(
                            "Δεν έχουν διαβαστεί ακόμη τα αιτήματα αυτού του πελάτη προς την ΑΑΔΕ. " +
                                "Πάτα «Έλεγχος τώρα».",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        else -> {
                            Row {
                                Text(
                                    "Αιτήματα προς την ΑΑΔΕ",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "έλεγχος " + AthensDates.stamp(current.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = dim,
                                )
                            }
                            Text(
                                when {
                                    current.error.isNotBlank() ->
                                        "Ο τελευταίος έλεγχος δεν διάβασε τα αιτήματα. Δοκίμασε ξανά."
                                    current.requests.isEmpty() -> "Κανένα αίτημα."
                                    else -> buildString {
                                        append(current.requests.size)
                                        append(if (current.requests.size == 1) " αίτημα" else " αιτήματα")
                                        append(" · ")
                                        append(
                                            when (current.pending) {
                                                0 -> "όλα απαντημένα"
                                                1 -> "1 περιμένει απάντηση"
                                                else -> "${current.pending} περιμένουν απάντηση"
                                            },
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current.error.isNotBlank()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    refreshing = true
                                    status = "Σύνδεση στην ΑΑΔΕ…"
                                    try {
                                        val result = RequestsRefresh.run(
                                            filesDir = context.filesDir,
                                            fetch = container.fetch,
                                            repository = container.repository,
                                            settings = settings,
                                            client = client,
                                        )
                                        status = result.message
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        status = "Απέτυχε: ${e.message}"
                                    } finally {
                                        refreshing = false
                                        reload++
                                    }
                                }
                            },
                        ) { Text(if (busy) "Έλεγχος…" else "Έλεγχος τώρα") }
                        if (current != null && current.fresh.isNotEmpty()) {
                            OutlinedButton(onClick = { seen(emptySet()) }) { Text("Τα είδα όλα") }
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Καθημερινή παρακολούθηση", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            !watched -> "Τα αιτήματα ελέγχονται μόνο όταν το ζητήσεις."
                            !settings.requestWatchDaily ->
                                "Ο πελάτης είναι στη λίστα, αλλά η παρακολούθηση είναι κλειστή — " +
                                    "άνοιξέ την από Ρυθμίσεις → Αιτήματα ΑΑΔΕ."
                            else ->
                                "Κάθε μέρα, περίπου στις " +
                                    settings.requestWatchHour.toString().padStart(2, '0') +
                                    ":00. Αν απαντηθεί κάτι, έρχεται ειδοποίηση."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
                Switch(
                    checked = watched,
                    onCheckedChange = { on ->
                        watched = on
                        settings.requestWatchClients =
                            if (on) settings.requestWatchClients + client.id
                            else settings.requestWatchClients - client.id
                        RequestWatch.apply(context, settings)
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (current != null) {
            items(current.requests, key = { it.id }) { request ->
                RequestCard(
                    request = request,
                    fresh = request.id in current.fresh,
                    open = request.id in expanded,
                    onToggle = {
                        if (request.id in expanded) expanded.remove(request.id) else expanded.add(request.id)
                        // Το άνοιγμα **είναι** η ανάγνωση.
                        if (request.id in current.fresh) seen(setOf(request.id))
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Ένα αίτημα: για ποιο πράγμα, πού βρίσκεται, και — όταν ανοίξει — τι γράφτηκε. */
@Composable
private fun RequestCard(
    request: AadeRequests.Request,
    fresh: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onToggle),
        colors = if (fresh) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            if (fresh) {
                Text(
                    if (request.answered) "ΝΕΑ ΑΠΑΝΤΗΣΗ" else "ΑΛΛΑΓΗ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row {
                Text(
                    request.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    request.status.ifBlank { "—" } + if (request.rejected) " · απορρίφθηκε" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        request.rejected -> MaterialTheme.colorScheme.error
                        request.answered -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            val meta = listOf(
                if (request.caseNumber.isBlank()) "" else "Αρ. " + request.caseNumber,
                if (request.submitted.isBlank()) "" else "υποβολή " + AadeRequests.date(request.submitted),
                if (request.updated.isBlank() || request.updated == request.submitted) ""
                else "ενημέρωση " + AadeRequests.date(request.updated),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = dim)
            if (request.topic.isNotBlank() && request.topic != request.title) {
                Text(request.topic, style = MaterialTheme.typography.bodySmall, color = dim)
            }
            if (request.service.isNotBlank()) {
                Text(request.service, style = MaterialTheme.typography.bodySmall, color = dim)
            }

            if (open) {
                if (request.text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Το αίτημα", style = MaterialTheme.typography.labelMedium, color = dim)
                    Text(request.text, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("Η απάντηση της ΑΑΔΕ", style = MaterialTheme.typography.labelMedium, color = dim)
                Text(
                    request.answer.ifBlank {
                        if (request.answered) {
                            "Η πύλη το δείχνει απαντημένο, χωρίς κείμενο. Δες το στα «Αιτήματά μου» του myAADE."
                        } else {
                            "Δεν έχει απαντηθεί ακόμη."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    if (request.answered) "Πάτησε για να δεις την απάντηση." else "Περιμένει απάντηση.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
