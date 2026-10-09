package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.gdpr.Exports
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestDetail
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
 * ## Μία κάρτα ανά αίτημα
 *
 * Κάθε κάρτα γράφει τα τέσσερα πράγματα με τα οποία ξεχωρίζει κανείς ένα
 * αίτημα από ένα άλλο: **τι είδους** είναι, **πότε υποβλήθηκε**, **πότε
 * ενημερώθηκε** τελευταία φορά, και **σε ποια κατάσταση** βρίσκεται.
 *
 * Το πάτημα ανοίγει το αίτημα **ολόκληρο** — και, την πρώτη φορά, το φέρνει
 * από την πύλη μαζί με τα συνημμένα του: όσα υπέβαλε ο πελάτης και όσα έστειλε
 * η υπηρεσία. Η λίστα δεν τα έχει· γι' αυτό είναι χωριστή σύνδεση, που γίνεται
 * μόνο όταν ζητηθεί και ξαναγίνεται μόνο αν το αίτημα άλλαξε στο μεταξύ.
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

    // Το αίτημα που είναι ανοιχτό, και η λήψη του. Ζουν **εδώ** και όχι μέσα
    // στον διάλογο: αν ο χρήστης τον κλείσει στη μέση, η λήψη συνεχίζει και το
    // αποτέλεσμά της περιμένει όταν τον ξανανοίξει.
    var openedId by remember(client.id) { mutableStateOf("") }
    var downloadingId by remember(client.id) { mutableStateOf("") }
    var detailStatus by remember(client.id) { mutableStateOf("") }

    val snapshot by produceState<RequestsStore.Snapshot?>(null, client.afm, reload) {
        value = withContext(Dispatchers.IO) { RequestsStore.load(context.filesDir, client.afm) }
    }
    val downloaded by produceState(emptyMap<String, Int>(), client.afm, reload) {
        value = withContext(Dispatchers.IO) { RequestsStore.downloaded(context.filesDir, client.afm) }
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

    /** Φέρνει ολόκληρο το αίτημα [id] με τα συνημμένα του. */
    fun download(id: String) {
        if (downloadingId.isNotBlank()) return
        scope.launch {
            downloadingId = id
            detailStatus = "Σύνδεση στην ΑΑΔΕ — λήψη του αιτήματος και των συνημμένων…"
            try {
                val result = RequestsRefresh.fetchDetail(container.fetch, container.repository, client, id)
                detailStatus = if (result.outcome == RequestsRefresh.Outcome.OK) "" else result.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detailStatus = "Απέτυχε: ${e.message}"
            } finally {
                downloadingId = ""
                reload++
            }
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
                    attachments = downloaded[request.id],
                    onOpen = {
                        detailStatus = ""
                        openedId = request.id
                        // Το άνοιγμα **είναι** η ανάγνωση.
                        if (request.id in current.fresh) seen(setOf(request.id))
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    val opened = current?.requests?.firstOrNull { it.id == openedId }
    if (opened != null) {
        RequestDialog(
            container = container,
            client = client,
            request = opened,
            reload = reload,
            downloading = downloadingId == opened.id,
            // Η ουρά είναι μία: όσο τρέχει οποιαδήποτε λήψη, δεν ξεκινά δεύτερη.
            queueBusy = busy || downloadingId.isNotBlank(),
            status = detailStatus,
            onStatus = { detailStatus = it },
            onDownload = { download(opened.id) },
            onDismiss = { openedId = "" },
        )
    }
}

// ---------------------------------------------------------------- η κάρτα

/**
 * Ένα αίτημα στη λίστα: είδος, ημερομηνίες υποβολής και ενημέρωσης, κατάσταση.
 *
 * @param attachments πόσα συνημμένα έχουν ήδη κατέβει, ή `null` όταν το αίτημα
 *   δεν έχει ανοιχτεί ποτέ ολόκληρο
 */
@Composable
private fun RequestCard(
    request: AadeRequests.Request,
    fresh: Boolean,
    attachments: Int?,
    onOpen: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onOpen),
        colors = if (fresh) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = BorderStroke(
            1.dp,
            if (fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (fresh) {
                Text(
                    if (request.answered) "ΝΕΑ ΑΠΑΝΤΗΣΗ" else "ΑΛΛΑΓΗ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Είδος αιτήματος", style = MaterialTheme.typography.labelSmall, color = dim)
                    Text(request.title, style = MaterialTheme.typography.titleSmall)
                    if (request.topic.isNotBlank() && request.topic != request.title) {
                        Text(request.topic, style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                }
                StatusPill(request)
            }

            Spacer(Modifier.height(10.dp))
            Row {
                DateCell("Υποβολή", request.submitted, Modifier.weight(1f))
                DateCell("Ενημέρωση", request.updated, Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (request.caseNumber.isBlank()) "" else "Αρ. " + request.caseNumber,
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    when (attachments) {
                        null -> "πάτησε για το πλήρες αίτημα"
                        0 -> "κατεβασμένο · χωρίς συνημμένα"
                        1 -> "κατεβασμένο · 1 συνημμένο"
                        else -> "κατεβασμένο · $attachments συνημμένα"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (attachments == null) dim else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DateCell(label: String, raw: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            if (raw.isBlank()) "—" else AadeRequests.date(raw),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Η κατάσταση, σε πλαίσιο με το χρώμα της: κόκκινο όταν απορρίφθηκε, το χρώμα
 * του θέματος όταν απαντήθηκε, ουδέτερο όσο περιμένει.
 */
@Composable
private fun StatusPill(request: AadeRequests.Request) {
    val color: Color = when {
        request.rejected -> MaterialTheme.colorScheme.error
        request.answered -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.7f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            when {
                request.rejected -> "Απορρίφθηκε"
                request.status.isNotBlank() -> request.status
                request.answered -> "Απαντημένο"
                else -> "Σε αναμονή"
            },
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

// ------------------------------------------------------- το πλήρες αίτημα

/**
 * Το αίτημα ολόκληρο, σε όλη την οθόνη.
 *
 * Ό,τι υπάρχει ήδη στη λίστα — κείμενο, απάντηση, ημερομηνίες — φαίνεται
 * αμέσως. Τα συνημμένα και όσα πεδία δίνει μόνο η πλήρης προβολή έρχονται από
 * την πύλη: **αυτόματα** την πρώτη φορά, και ξανά μόνο αν το αίτημα
 * ενημερώθηκε μετά την τελευταία λήψη. Κάθε άλλο άνοιγμα διαβάζει τον δίσκο
 * και δεν ανοίγει σύνδεση — μια συνεδρία GSIS για να ξαναδεί κανείς ό,τι ήδη
 * έχει θα ήταν σπατάλη, και μετά από μερικές, κλείδωμα.
 *
 * @param reload αλλάζει όταν γράφτηκε κάτι στον δίσκο
 */
@Composable
private fun RequestDialog(
    container: AppContainer,
    client: ClientEntity,
    request: AadeRequests.Request,
    reload: Int,
    downloading: Boolean,
    queueBusy: Boolean,
    status: String,
    onStatus: (String) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    // `null` = ο δίσκος δεν έχει διαβαστεί ακόμη· άλλο πράγμα από «δεν υπάρχει».
    var loaded by remember(request.id) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(request.id, reload) {
        loaded = Loaded(withContext(Dispatchers.IO) { RequestsStore.detail(context.filesDir, client.afm, request.id) })
    }
    val detail = loaded?.detail

    // Μία αυτόματη προσπάθεια ανά άνοιγμα: αν αποτύχει, δεν ξαναδοκιμάζει μόνη
    // της — ο λόγος φαίνεται στην οθόνη και το κουμπί είναι εκεί.
    var tried by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(loaded, queueBusy) {
        val now = loaded ?: return@LaunchedEffect
        if (tried || queueBusy) return@LaunchedEffect
        val found = now.detail
        if (found == null || RequestDetail.stale(found.retrievedAt, request.updated)) {
            tried = true
            onDownload()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Αίτημα προς την ΑΑΔΕ", style = MaterialTheme.typography.labelMedium, color = dim)
                        Text(request.title, style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Κλείσιμο")
                    }
                }
                if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(request)
                        if (detail?.archived == true) {
                            Spacer(Modifier.width(10.dp))
                            Text("αρχειοθετημένο", style = MaterialTheme.typography.labelMedium, color = dim)
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    Field("Είδος αιτήματος", request.title)
                    if (request.topic != request.title) Field("Θεματική ενότητα", request.topic)
                    Field("Υπηρεσία", request.service)
                    Field("Αριθμός υπόθεσης", request.caseNumber)
                    Field("Υποβολή", AadeRequests.date(request.submitted))
                    Field("Τελευταία ενημέρωση", AadeRequests.date(request.updated))
                    Field("Σχετίζεται με το αίτημα", detail?.reference.orEmpty())

                    Section("Το αίτημα")
                    Body(detail?.text.orEmpty().ifBlank { request.text }.ifBlank { "Χωρίς κείμενο." })
                    if (detail != null) {
                        Attachments(
                            title = "Συνημμένα που υποβλήθηκαν",
                            files = detail.submitted,
                            container = container,
                            client = client,
                            caseNumber = request.caseNumber,
                            onStatus = onStatus,
                        )
                        if (detail.supporting.isNotBlank()) {
                            Section("Δικαιολογητικά")
                            Body(detail.supporting)
                        }
                    }

                    Section("Η απάντηση της ΑΑΔΕ")
                    Body(
                        detail?.answer.orEmpty().ifBlank { request.answer }.ifBlank {
                            when {
                                detail != null && detail.replies.isNotEmpty() ->
                                    "Χωρίς κείμενο — η απάντηση είναι τα έγγραφα που ακολουθούν."
                                request.answered ->
                                    "Η πύλη το δείχνει απαντημένο, χωρίς κείμενο."
                                else -> "Δεν έχει απαντηθεί ακόμη."
                            }
                        },
                    )
                    if (detail != null) {
                        Field("Αιτιολογία απόρριψης", detail.rejectReason)
                        Field("Παρατήρηση της υπηρεσίας", detail.reviewReason)
                        Attachments(
                            title = "Έγγραφα της απάντησης",
                            files = detail.replies,
                            container = container,
                            client = client,
                            caseNumber = request.caseNumber,
                            onStatus = onStatus,
                        )

                        if (detail.thread.isNotEmpty() || detail.threadFiles.isNotEmpty()) {
                            Section("Μετέπειτα αλληλογραφία")
                            detail.thread.forEach { note ->
                                if (note.date.isNotBlank()) {
                                    Text(
                                        AadeRequests.date(note.date),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dim,
                                    )
                                }
                                Body(note.text)
                                Spacer(Modifier.height(6.dp))
                            }
                            Attachments(
                                title = "",
                                files = detail.threadFiles,
                                container = container,
                                client = client,
                                caseNumber = request.caseNumber,
                                onStatus = onStatus,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        when {
                            detail != null -> buildString {
                                append("Διαβάστηκε από την πύλη: ")
                                append(AthensDates.stamp(detail.retrievedAt)).append('.')
                                if (detail.missing > 0) {
                                    append(' ')
                                    append(
                                        if (detail.missing == 1) "Ένα συνημμένο δεν κατέβηκε"
                                        else "${detail.missing} συνημμένα δεν κατέβηκαν",
                                    )
                                    append(" — δοκίμασε «Λήψη ξανά».")
                                }
                            }
                            downloading -> "Κατεβαίνει το πλήρες αίτημα…"
                            loaded == null -> ""
                            else ->
                                "Φαίνεται ό,τι έχει η λίστα. Τα συνημμένα έρχονται με τη λήψη του " +
                                    "πλήρους αιτήματος."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (downloading) dim else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = !queueBusy,
                            onClick = {
                                tried = true
                                onDownload()
                            },
                        ) {
                            Text(
                                when {
                                    downloading -> "Λήψη…"
                                    detail == null -> "Λήψη πλήρους αιτήματος"
                                    else -> "Λήψη ξανά"
                                },
                            )
                        }
                        OutlinedButton(onClick = onDismiss) { Text("Κλείσιμο") }
                    }
                }
            }
        }
    }
}

/** Τι βρέθηκε στον δίσκο για το αίτημα — βλ. τη χρήση του στο [RequestDialog]. */
private data class Loaded(val detail: RequestDetail.Detail?)

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

/** Κείμενο που **αντιγράφεται**: η απάντηση της ΑΑΔΕ πολύ συχνά προωθείται στον πελάτη. */
@Composable
private fun Body(text: String) {
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

/** Ετικέτα και τιμή· κενή τιμή δεν πιάνει γραμμή. */
@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * Τα συνημμένα μιας ενότητας, το καθένα με άνοιγμα και κοινοποίηση.
 *
 * Η κοινοποίηση γράφεται στο αρχείο ενεργειών: ένα έγγραφο πελάτη φεύγει από
 * την εφαρμογή προς άλλη, και αυτό είναι ακριβώς το είδος της κίνησης που
 * πρέπει να μπορεί να αποδειχθεί αργότερα. Γράφεται ο αριθμός της υπόθεσης,
 * ποτέ το όνομα του αρχείου.
 */
@Composable
private fun Attachments(
    title: String,
    files: List<RequestDetail.Attachment>,
    container: AppContainer,
    client: ClientEntity,
    caseNumber: String,
    onStatus: (String) -> Unit,
) {
    if (files.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val shape = MaterialTheme.shapes.small

    if (title.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = dim)
    }
    files.forEach { file ->
        Column(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOf(RequestDetail.size(file.size), file.origin.label)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
            if (!file.downloaded) {
                Text(
                    "Δεν κατέβηκε" + if (file.error.isBlank()) "." else " (${file.error}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val target = RequestsStore.attachment(context.filesDir, client.afm, file.saved)
                        onStatus(
                            if (target == null) GONE else DocumentActions.open(context, target, file.saved),
                        )
                    }) { Text("Άνοιγμα") }
                    TextButton(onClick = {
                        val target = RequestsStore.attachment(context.filesDir, client.afm, file.saved)
                        if (target == null) {
                            onStatus(GONE)
                        } else {
                            onStatus("")
                            runCatching {
                                Exports.share(
                                    context,
                                    target,
                                    DocumentActions.mimeOf(file.saved),
                                    "Κοινοποίηση συνημμένου",
                                )
                            }.onFailure { onStatus("Δεν άνοιξε η κοινοποίηση: ${it.message}") }
                            scope.launch {
                                container.db.audit().log(
                                    AuditEntity(
                                        ts = System.currentTimeMillis(),
                                        action = "SHARE_REQUEST_FILE",
                                        afm = client.afm,
                                        detail = "συνημμένο αιτήματος ΑΑΔΕ" +
                                            if (caseNumber.isBlank()) "" else " $caseNumber",
                                    ),
                                )
                            }
                        }
                    }) { Text("Κοινοποίηση") }
                }
            }
        }
    }
}

private const val GONE = "Το αρχείο δεν υπάρχει πια στη συσκευή — πάτα «Λήψη ξανά»."
