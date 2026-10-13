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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Ό,τι μοιράζονται οι δύο οθόνες των αιτημάτων — η καρτέλα του πελάτη και η
 * συγκεντρωτική λίστα: η κάρτα ενός αιτήματος, και το αίτημα ολόκληρο.
 *
 * Ένα αρχείο και όχι δύο αντίγραφα: το ίδιο αίτημα πρέπει να δείχνει ίδιο από
 * όπου κι αν ανοίξει, και μια διόρθωση στη μία οθόνη δεν πρέπει να περιμένει
 * κάποιον να θυμηθεί την άλλη.
 */

// ------------------------------------------------------- ποιο είναι ανοιχτό

/**
 * Το αίτημα που είναι ανοιχτό, και η ανανέωσή του από την πύλη.
 *
 * Ζει στην **οθόνη** και όχι μέσα στον διάλογο: αν ο χρήστης κλείσει το αίτημα
 * στη μέση μιας ανανέωσης, αυτή συνεχίζει, και το αποτέλεσμά της περιμένει
 * όταν το ξανανοίξει.
 */
@Stable
internal class RequestOpener(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val filesDir: File,
    private val onChanged: () -> Unit,
) {
    /** Ο πελάτης και ο αριθμός του ανοιχτού αιτήματος. */
    var opened by mutableStateOf<Pair<ClientEntity, String>?>(null)
        private set

    /** `clientId:id` του αιτήματος που ανανεώνεται τώρα, ή κενό. */
    var refreshing by mutableStateOf("")
        private set

    var status by mutableStateOf("")

    fun open(client: ClientEntity, id: String) {
        status = ""
        opened = client to id
    }

    fun close() {
        opened = null
    }

    fun isRefreshing(client: ClientEntity, id: String): Boolean = refreshing == key(client, id)

    /**
     * Ξαναφέρνει από την ΑΑΔΕ το αίτημα [id] με τα συνημμένα του. Γίνεται
     * **μόνο όταν το ζητήσει ο χρήστης** — το άνοιγμα δεν συνδέεται ποτέ.
     */
    fun refresh(client: ClientEntity, id: String) {
        if (refreshing.isNotBlank()) return
        scope.launch {
            refreshing = key(client, id)
            status = "Σύνδεση στην ΑΑΔΕ — λήψη του αιτήματος και των συνημμένων…"
            try {
                val result = RequestsRefresh.fetchDetails(
                    filesDir = filesDir,
                    fetch = container.fetch,
                    repository = container.repository,
                    client = client,
                    ids = listOf(id),
                )
                status = when {
                    result.outcome != RequestsRefresh.Outcome.OK -> result.message
                    result.saved == 0 -> "Η ΑΑΔΕ δεν έδωσε το αίτημα. Δοκίμασε ξανά σε λίγο."
                    else -> ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Απέτυχε: ${e.message}"
            } finally {
                refreshing = ""
                onChanged()
            }
        }
    }

    private fun key(client: ClientEntity, id: String) = "${client.id}:$id"
}

@Composable
internal fun rememberRequestOpener(container: AppContainer, onChanged: () -> Unit): RequestOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)
    return remember(container) { RequestOpener(container, scope, context.filesDir) { changed() } }
}

// ---------------------------------------------------------------- η κάρτα

/**
 * Ένα αίτημα στη λίστα: είδος, ημερομηνίες υποβολής και ενημέρωσης, κατάσταση.
 *
 * @param clientName στη συγκεντρωτική λίστα, ποιανού είναι· κενό στην καρτέλα
 *   του πελάτη, όπου είναι αυτονόητο
 * @param saved πόσα συνημμένα είναι στη συσκευή, ή `null` όταν το αίτημα δεν
 *   έχει αποθηκευτεί ολόκληρο
 * @param outdated αποθηκευμένο, αλλά άλλαξε στην πύλη από τότε
 */
@Composable
internal fun RequestCard(
    request: AadeRequests.Request,
    fresh: Boolean,
    saved: Int?,
    outdated: Boolean,
    onOpen: () -> Unit,
    clientName: String = "",
    clientAfm: String = "",
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
                    if (request.answered) "ΝΕΑ ΑΠΑΝΤΗΣΗ" else "ΑΛΛΑΓΗ ΚΑΤΑΣΤΑΣΗΣ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
            }
            if (clientName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clientName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    if (clientAfm.isNotBlank()) {
                        Text("ΑΦΜ $clientAfm", style = MaterialTheme.typography.labelSmall, color = dim)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Είδος αιτήματος", style = MaterialTheme.typography.labelSmall, color = dim)
                    Text(
                        request.title,
                        style = if (clientName.isBlank()) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    )
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
                    when {
                        saved == null -> "δεν έχει αποθηκευτεί ολόκληρο"
                        outdated -> "άλλαξε στην ΑΑΔΕ · θέλει ανανέωση"
                        saved == 0 -> "αποθηκευμένο · χωρίς συνημμένα"
                        saved == 1 -> "αποθηκευμένο · 1 συνημμένο"
                        else -> "αποθηκευμένο · $saved συνημμένα"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        saved == null -> dim
                        outdated -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
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
internal fun StatusPill(request: AadeRequests.Request) {
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
 * Διαβάζει **μόνο τον δίσκο**. Ό,τι έχει αποθηκευτεί — κείμενο, απάντηση,
 * συνημμένα — ανοίγει αμέσως και χωρίς σύνδεση, όσες φορές κι αν ανοιχτεί.
 * Σύνδεση στην ΑΑΔΕ γίνεται μόνο με το κουμπί **«Ανανέωση»**.
 *
 * Η πρώτη έκδοση κατέβαζε το αίτημα την ώρα που άνοιγε. Αυτό σήμαινε μία
 * συνεδρία GSIS για κάθε αίτημα που ήθελε κανείς απλώς να διαβάσει — αναμονή
 * κάθε φορά, και μετά από αρκετές, κίνδυνος κλειδώματος του λογαριασμού. Τώρα
 * τα αιτήματα αποθηκεύονται όλα μαζί στον έλεγχο ([RequestsRefresh.run]).
 *
 * @param reload αλλάζει όταν γράφτηκε κάτι στον δίσκο
 * @param outdated το αποθηκευμένο είναι παλαιότερο από τη λίστα
 * @param queueBusy τρέχει άλλη λήψη — η ανανέωση περιμένει
 * @param showClient στη συγκεντρωτική λίστα, γράφει και ποιανού είναι
 */
@Composable
internal fun RequestDialog(
    container: AppContainer,
    client: ClientEntity,
    request: AadeRequests.Request,
    reload: Int,
    outdated: Boolean,
    queueBusy: Boolean,
    opener: RequestOpener,
    showClient: Boolean = false,
    onOpenClient: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val refreshing = opener.isRefreshing(client, request.id)
    val blocked = queueBusy || opener.refreshing.isNotBlank()

    // `null` = ο δίσκος δεν έχει διαβαστεί ακόμη· άλλο πράγμα από «δεν υπάρχει».
    var loaded by remember(client.id, request.id) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(client.id, request.id, reload) {
        loaded = Loaded(withContext(Dispatchers.IO) { RequestsStore.detail(context.filesDir, client.afm, request.id) })
    }
    val detail = loaded?.detail

    Dialog(
        onDismissRequest = { opener.close() },
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
                    IconButton(enabled = !blocked, onClick = { opener.refresh(client, request.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Ανανέωση από την ΑΑΔΕ")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { opener.close() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Κλείσιμο")
                    }
                }
                if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()

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

                    // Πού βρισκόμαστε: αποθηκευμένο, παλιό, ή μόνο η λίστα.
                    SavedNote(loaded = loaded != null, detail = detail, outdated = outdated, refreshing = refreshing)
                    Spacer(Modifier.height(6.dp))

                    if (showClient) Field("Πελάτης", client.displayName + " · ΑΦΜ " + client.afm)
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
                            onStatus = { opener.status = it },
                        )
                        if (detail.supporting.isNotBlank()) {
                            Section("Δικαιολογητικά")
                            Body(detail.supporting)
                        }
                    }

                    Section("Η απάντηση της ΑΑΔΕ")
                    Body(
                        // Η λίστα προηγείται όταν το αποθηκευμένο είναι παλιό: η
                        // απάντηση που ήρθε μετά υπάρχει μόνο εκεί.
                        (if (outdated) request.answer.ifBlank { detail?.answer.orEmpty() }
                        else detail?.answer.orEmpty().ifBlank { request.answer }).ifBlank {
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
                            onStatus = { opener.status = it },
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
                                onStatus = { opener.status = it },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (opener.status.isNotBlank()) {
                        Text(
                            opener.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (refreshing) dim else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(enabled = !blocked, onClick = { opener.refresh(client, request.id) }) {
                            Text(
                                when {
                                    refreshing -> "Ανανέωση…"
                                    detail == null -> "Λήψη από την ΑΑΔΕ"
                                    else -> "Ανανέωση"
                                },
                            )
                        }
                        if (onOpenClient != null) {
                            OutlinedButton(onClick = onOpenClient) { Text("Καρτέλα πελάτη") }
                        }
                        OutlinedButton(onClick = { opener.close() }) { Text("Κλείσιμο") }
                    }
                }
            }
        }
    }
}

/** Τι βρέθηκε στον δίσκο για το αίτημα — βλ. τη χρήση του στο [RequestDialog]. */
private data class Loaded(val detail: RequestDetail.Detail?)

/**
 * Μία γραμμή που λέει τι βλέπει ο χρήστης: το αποθηκευμένο αίτημα και από πότε,
 * ή μόνο ό,τι έχει η λίστα. Χωρίς αυτήν, ένα αίτημα χωρίς συνημμένα και ένα
 * αίτημα που δεν έχει κατέβει δείχνουν ίδια.
 */
@Composable
private fun SavedNote(loaded: Boolean, detail: RequestDetail.Detail?, outdated: Boolean, refreshing: Boolean) {
    if (!loaded) return
    val warn = detail == null || outdated || detail.missing > 0
    Text(
        when {
            refreshing -> "Γίνεται λήψη από την ΑΑΔΕ…"
            detail == null ->
                "Δεν έχει αποθηκευτεί ακόμη ολόκληρο: φαίνεται ό,τι έχει η λίστα, χωρίς συνημμένα. " +
                    "Πάτα «Λήψη από την ΑΑΔΕ» — ή «Έλεγχος τώρα», που τα αποθηκεύει όλα μαζί."
            outdated ->
                "Το αίτημα άλλαξε στην ΑΑΔΕ από τότε που αποθηκεύτηκε (" +
                    AthensDates.stamp(detail.retrievedAt) + "). Πάτα «Ανανέωση» για την τωρινή του μορφή " +
                    "και τα νέα έγγραφα."
            detail.missing > 0 ->
                "Αποθηκευμένο στη συσκευή από " + AthensDates.stamp(detail.retrievedAt) + ". " +
                    (if (detail.missing == 1) "Ένα συνημμένο δεν κατέβηκε" else "${detail.missing} συνημμένα δεν κατέβηκαν") +
                    " — δοκίμασε «Ανανέωση»."
            else ->
                "Αποθηκευμένο στη συσκευή από " + AthensDates.stamp(detail.retrievedAt) +
                    " — ανοίγει χωρίς σύνδεση."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (warn && !refreshing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

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

private const val GONE = "Το αρχείο δεν υπάρχει πια στη συσκευή — πάτα «Ανανέωση»."
