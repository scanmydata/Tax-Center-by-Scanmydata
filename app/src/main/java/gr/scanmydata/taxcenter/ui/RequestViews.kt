package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.gdpr.Exports
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestDetail
import gr.scanmydata.taxcenter.requests.RequestSends
import gr.scanmydata.taxcenter.requests.RequestSummary
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
                    when {
                        request.answered -> "ΝΕΑ ΑΠΑΝΤΗΣΗ"
                        request.waitingClient && request.ask.isNotBlank() -> "Η ΑΑΔΕ ΖΗΤΑ ΚΑΤΙ ΑΠΟ ΤΟΝ ΠΕΛΑΤΗ"
                        request.waitingClient -> "ΘΕΛΕΙ ΕΝΕΡΓΕΙΑ ΤΟΥ ΠΕΛΑΤΗ"
                        AadeRequests.stage(request.status, request.statusCode) == AadeRequests.Kind.PROCESSING -> "ΣΕ ΕΠΕΞΕΡΓΑΣΙΑ"
                        else -> "ΑΛΛΑΓΗ ΚΑΤΑΣΤΑΣΗΣ"
                    },
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
                // Σύντομη κατάσταση και όριο πλάτους: η πλήρης («Σε εκκρεμότητα
                // από τον συναλλασσόμενο») έσπρωχνε τον τίτλο εκτός κάρτας.
                StatusPill(request, Modifier.widthIn(max = 150.dp))
            }

            // Το ζητούμενο της ΑΑΔΕ, πάνω από όλα τα υπόλοιπα: είναι ο λόγος που
            // ανοίγει κανείς την κάρτα.
            if (request.ask.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                AskBox(request.ask, request.askDate, lines = 3)
            }

            Spacer(Modifier.height(10.dp))
            Row {
                DateCell("Υποβολή", request.submitted, Modifier.weight(1f))
                DateCell("Τελευταία ενημέρωση", request.updated, Modifier.weight(1f))
            }
            // Χωρίς τη διεύθυνση της υπηρεσίας και χωρίς τμήμα που την επαναλαμβάνει.
            val handling = listOf(
                RequestSummary.service(request.handlingService).name,
                RequestSummary.department(request.handlingDept, request.handlingService),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (handling.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Το χειρίζεται", style = MaterialTheme.typography.labelSmall, color = dim)
                Text(
                    handling,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
internal fun StatusPill(request: AadeRequests.Request, modifier: Modifier = Modifier, full: Boolean = false) {
    val color: Color = when {
        request.rejected -> MaterialTheme.colorScheme.error
        // Θέλει τον πελάτη: το χρώμα που τραβά το μάτι, όχι το «λάθος».
        request.waitingClient -> MaterialTheme.colorScheme.tertiary
        request.answered -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .border(1.dp, color.copy(alpha = 0.7f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            if (full) {
                request.status.ifBlank { AadeRequests.statusLabel(request.statusCode) }
                    .ifBlank { AadeRequests.shortStatus(request) }
            } else {
                AadeRequests.shortStatus(request)
            },
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = if (full) 3 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** «Η ΑΑΔΕ ζητά»: το μήνυμα της ενδιάμεσης επικοινωνίας που περιμένει τον πελάτη. */
@Composable
private fun AskBox(text: String, date: String, lines: Int = Int.MAX_VALUE) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "Η ΑΑΔΕ ζητά" + if (date.isBlank()) "" else " · " + AadeRequests.date(date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            if (lines == Int.MAX_VALUE) {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            } else {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------- το πλήρες αίτημα

/**
 * Το αίτημα ολόκληρο, σε όλη την οθόνη.
 *
 * Διαβάζει **μόνο τον δίσκο**. Ό,τι έχει αποθηκευτεί — κείμενο, απάντηση,
 * συνημμένα — ανοίγει αμέσως και χωρίς σύνδεση, όσες φορές κι αν ανοιχτεί.
 * Σύνδεση στην ΑΑΔΕ γίνεται μόνο με το κουμπί **ανανέωσης** πάνω δεξιά.
 *
 * Η πρώτη έκδοση κατέβαζε το αίτημα την ώρα που άνοιγε. Αυτό σήμαινε μία
 * συνεδρία GSIS για κάθε αίτημα που ήθελε κανείς απλώς να διαβάσει — αναμονή
 * κάθε φορά, και μετά από αρκετές, κίνδυνος κλειδώματος του λογαριασμού. Τώρα
 * τα αιτήματα αποθηκεύονται όλα μαζί στον έλεγχο ([RequestsRefresh.run]).
 *
 * ## Η σειρά
 *
 * Από το «τι γίνεται» προς το «τι γράφει»: πρώτα η **κατάσταση** με τα βήματά
 * της ([RequestSummary]), μετά ό,τι ζητά η ΑΑΔΕ ή ό,τι απάντησε, μετά **ποιος
 * το έχει**, η επικοινωνία, και στο τέλος το κείμενο του αιτήματος. Τα
 * δικαιολογητικά της διαδικασίας είναι κείμενο της πύλης, ίδιο σε κάθε αίτημα
 * του είδους — μένουν κλειστά. Τα συνημμένα ανοίγουν σε δικό τους παράθυρο,
 * ώστε έξι αρχεία να μην πιάνουν τρεις οθόνες ανάμεσα στα κείμενα.
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
    var showFiles by remember(client.id, request.id) { mutableStateOf(false) }

    FetchBusyOverlay(
        container = container,
        visible = refreshing,
        title = if (detail == null) "Λήψη αιτήματος" else "Ανανέωση αιτήματος",
        text = request.title + " — " + client.displayName,
        detail = "Σύνδεση στην ΑΑΔΕ, ανάγνωση του αιτήματος και λήψη των συνημμένων του. " +
            "Μετά ανοίγει χωρίς σύνδεση.",
    )

    Dialog(
        onDismissRequest = { opener.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                // Πίσω αριστερά, όπου το ψάχνει το χέρι· η ανανέωση δεξιά, με
                // χρώμα, γιατί είναι η μόνη ενέργεια της οθόνης.
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { opener.close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            if (showClient) client.displayName else "Αίτημα προς την ΑΑΔΕ",
                            style = MaterialTheme.typography.labelMedium,
                            color = dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            request.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        enabled = !blocked,
                        primary = true,
                        onClick = { opener.refresh(client, request.id) },
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = if (detail == null) "Λήψη από την ΑΑΔΕ" else "Ανανέωση από την ΑΑΔΕ",
                        )
                    }
                }
                HorizontalDivider()
                if (opener.status.isNotBlank() && !refreshing && !showFiles) {
                    Text(
                        opener.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    )
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(12.dp))

                    // 1. Πού βρίσκεται.
                    StatusHero(request, detail)
                    Spacer(Modifier.height(6.dp))
                    SavedNote(loaded = loaded != null, detail = detail, outdated = outdated, refreshing = refreshing)

                    // 2. Ό,τι ζητά τώρα η ΑΑΔΕ, πριν από οτιδήποτε άλλο.
                    val ask = detail?.pendingAsk
                    if (ask != null) {
                        Spacer(Modifier.height(12.dp))
                        AskBox(ask.text, RequestDetail.stamp(ask.date))
                        if (ask.files.isNotEmpty()) {
                            FileLinks(ask.files, onClick = { showFiles = true })
                        }
                    } else if (request.ask.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        AskBox(request.ask, request.askDate)
                    }

                    // 3. Η απάντηση — όταν υπάρχει. «Δεν απαντήθηκε» το λέει ήδη η κατάσταση.
                    val answer = (
                        // Η λίστα προηγείται όταν το αποθηκευμένο είναι παλιό: η
                        // απάντηση που ήρθε μετά υπάρχει μόνο εκεί.
                        if (outdated) request.answer.ifBlank { detail?.answer.orEmpty() }
                        else detail?.answer.orEmpty().ifBlank { request.answer }
                        )
                    val replies = detail?.replies.orEmpty()
                    val rejectReason = detail?.rejectReason.orEmpty()
                    val reviewReason = detail?.reviewReason.orEmpty()
                    if (answer.isNotBlank() || replies.isNotEmpty() || rejectReason.isNotBlank() ||
                        reviewReason.isNotBlank() || request.answered
                    ) {
                        Spacer(Modifier.height(12.dp))
                        AnswerCard(
                            answer = answer.ifBlank {
                                if (replies.isNotEmpty()) "Χωρίς κείμενο — η απάντηση είναι τα έγγραφα."
                                else if (request.answered) "Η πύλη το δείχνει απαντημένο, χωρίς κείμενο."
                                else ""
                            },
                            rejectReason = rejectReason,
                            reviewReason = reviewReason,
                            replies = replies.size,
                            onFiles = { showFiles = true },
                        )
                    }

                    // 4. Όλα τα συνημμένα, σε ένα κουμπί.
                    if (detail != null && detail.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        AttachmentsButton(detail, onClick = { showFiles = true })
                    }

                    // 5. Ποιος το έχει, και τα στοιχεία του.
                    Spacer(Modifier.height(12.dp))
                    InfoCard(
                        request = request,
                        detail = detail,
                        client = client,
                        showClient = showClient,
                        onOpenClient = onOpenClient,
                    )

                    // 6. Η επικοινωνία, ως συνομιλία.
                    if (detail != null && detail.timeline.isNotEmpty()) {
                        Conversation(detail.timeline, onFiles = { showFiles = true })
                    }

                    // 7. Το κείμενο του αιτήματος, και τα δικαιολογητικά κλειστά.
                    Collapsible("Το αίτημα", initiallyOpen = true) {
                        Body(detail?.text.orEmpty().ifBlank { request.text }.ifBlank { "Χωρίς κείμενο." })
                    }
                    if (detail != null && detail.supporting.isNotBlank()) {
                        Collapsible("Δικαιολογητικά της διαδικασίας", initiallyOpen = false) {
                            Body(detail.supporting)
                        }
                    }
                    if (detail?.archived == true) {
                        Spacer(Modifier.height(10.dp))
                        Text("Αρχειοθετημένο στην πύλη.", style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                    // Το τέλος του κειμένου πάνω από την πλοήγηση του συστήματος.
                    Spacer(Modifier.navigationBarsPadding().height(24.dp))
                }
            }
        }
    }

    if (showFiles && detail != null) {
        AttachmentsDialog(
            detail = detail,
            container = container,
            client = client,
            caseNumber = request.caseNumber,
            status = opener.status,
            onStatus = { opener.status = it },
            onDismiss = { showFiles = false; opener.status = "" },
        )
    }
}

/** Τι βρέθηκε στον δίσκο για το αίτημα — βλ. τη χρήση του στο [RequestDialog]. */
private data class Loaded(val detail: RequestDetail.Detail?)

// ------------------------------------------------------------- κατάσταση

@Composable
private fun RequestSummary.Tone.color(): Color = when (this) {
    RequestSummary.Tone.DONE -> MaterialTheme.colorScheme.primary
    RequestSummary.Tone.ACTION -> MaterialTheme.colorScheme.tertiary
    RequestSummary.Tone.WORKING -> MaterialTheme.colorScheme.secondary
    RequestSummary.Tone.BAD -> MaterialTheme.colorScheme.error
    RequestSummary.Tone.CLOSED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
}

/**
 * Η κατάσταση, μεγάλη και στο χρώμα της, με τα τρία βήματα από κάτω. Είναι το
 * πρώτο που διαβάζεται, και αρκεί για να ξέρει κανείς αν πρέπει να κάνει κάτι.
 */
@Composable
private fun StatusHero(request: AadeRequests.Request, detail: RequestDetail.Detail?) {
    val headline = RequestSummary.headline(request, detail, replies = detail?.replies?.size ?: 0)
    val color = headline.tone.color()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (headline.tone) {
                        RequestSummary.Tone.DONE -> Icons.Filled.CheckCircle
                        RequestSummary.Tone.ACTION -> Icons.Outlined.Warning
                        RequestSummary.Tone.WORKING -> Icons.Outlined.Schedule
                        RequestSummary.Tone.BAD -> Icons.Filled.Error
                        RequestSummary.Tone.CLOSED -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(headline.title, style = MaterialTheme.typography.titleMedium, color = color)
                    if (headline.subtitle.isNotBlank()) {
                        Text(
                            headline.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Stepper(RequestSummary.steps(request), color)
        }
    }
}

@Composable
private fun Stepper(steps: List<RequestSummary.Step>, accent: Color) {
    val idle = MaterialTheme.colorScheme.outlineVariant
    val error = MaterialTheme.colorScheme.error
    Row(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, step ->
            val dotColor = when (step.state) {
                RequestSummary.StepState.DONE, RequestSummary.StepState.CURRENT -> accent
                RequestSummary.StepState.FAILED -> error
                RequestSummary.StepState.TODO -> idle
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val filled = step.state == RequestSummary.StepState.DONE || step.state == RequestSummary.StepState.FAILED
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (filled) dotColor else Color.Transparent)
                            .border(2.dp, dotColor, CircleShape),
                    )
                    if (i < steps.lastIndex) {
                        val next = steps[i + 1].state
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                                .height(2.dp)
                                .background(if (next == RequestSummary.StepState.TODO) idle else accent),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (step.state == RequestSummary.StepState.TODO) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(end = 4.dp),
                )
                if (step.date.isNotBlank()) {
                    Text(
                        step.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

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
                    "Πάτα την ανανέωση πάνω δεξιά — ή «Έλεγχος τώρα», που τα αποθηκεύει όλα μαζί."
            outdated ->
                "Άλλαξε στην ΑΑΔΕ από τότε που αποθηκεύτηκε (" + AthensDates.stamp(detail.retrievedAt) +
                    ") — πάτα την ανανέωση πάνω δεξιά."
            detail.missing > 0 ->
                "Αποθηκευμένο από " + AthensDates.stamp(detail.retrievedAt) + " · " +
                    (if (detail.missing == 1) "ένα συνημμένο δεν κατέβηκε" else "${detail.missing} συνημμένα δεν κατέβηκαν") +
                    " — δοκίμασε την ανανέωση."
            else -> "Αποθηκευμένο από " + AthensDates.stamp(detail.retrievedAt) + " · ανοίγει χωρίς σύνδεση"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (warn && !refreshing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

// ------------------------------------------------------------- απάντηση

@Composable
private fun AnswerCard(
    answer: String,
    rejectReason: String,
    reviewReason: String,
    replies: Int,
    onFiles: () -> Unit,
) {
    val rejected = rejectReason.isNotBlank()
    val color = if (rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Η απάντηση της ΑΑΔΕ", style = MaterialTheme.typography.labelLarge, color = color)
            if (answer.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Body(answer)
            }
            if (rejected) {
                Spacer(Modifier.height(8.dp))
                Text("Αιτιολογία απόρριψης", style = MaterialTheme.typography.labelMedium, color = color)
                Body(rejectReason)
            }
            if (reviewReason.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Παρατήρηση της υπηρεσίας",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Body(reviewReason)
            }
            if (replies > 0) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onFiles, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (replies == 1) "1 έγγραφο απάντησης" else "$replies έγγραφα απάντησης")
                }
            }
        }
    }
}

// ------------------------------------------------------------- στοιχεία

/** Ανάθεση, αριθμός υπόθεσης, ημερομηνίες και πελάτης — σε μία κάρτα. */
@Composable
private fun InfoCard(
    request: AadeRequests.Request,
    detail: RequestDetail.Detail?,
    client: ClientEntity,
    showClient: Boolean,
    onOpenClient: (() -> Unit)?,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val handler = RequestSummary.service(request.handlingService)
    val dept = RequestSummary.department(request.handlingDept, request.handlingService)
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) {
            // Η ανάθεση: ποιος το χειρίζεται τώρα. Από το αποθηκευμένο αίτημα
            // όταν είναι νεότερο από τη λίστα — βλ. RequestDetail.merge.
            Text("Το χειρίζεται", style = MaterialTheme.typography.labelSmall, color = dim)
            if (handler.name.isBlank() && dept.isBlank()) {
                Text("Δεν έχει ανατεθεί ακόμη", style = MaterialTheme.typography.bodyMedium, color = dim)
            } else {
                SelectionContainer {
                    Column {
                        if (handler.name.isNotBlank()) Text(handler.name, style = MaterialTheme.typography.titleSmall)
                        if (dept.isNotBlank()) Text(dept, style = MaterialTheme.typography.bodySmall)
                        if (handler.address.isNotBlank()) {
                            Text(handler.address, style = MaterialTheme.typography.bodySmall, color = dim)
                        }
                    }
                }
            }
            if (request.service.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Υποβλήθηκε σε: " + request.service,
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row {
                Cell("Αρ. υπόθεσης", request.caseNumber, Modifier.weight(1f))
                Cell("Θεματική", request.topic.takeIf { it != request.title }.orEmpty(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Cell("Υποβολή", AadeRequests.date(request.submitted), Modifier.weight(1f))
                Cell("Τελευταία ενημέρωση", AadeRequests.date(request.updated), Modifier.weight(1f))
            }
            detail?.reference?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Cell("Σχετίζεται με το αίτημα", it)
            }

            if (showClient || onOpenClient != null) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Πελάτης", style = MaterialTheme.typography.labelSmall, color = dim)
                        Text(client.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("ΑΦΜ " + client.afm, style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                    if (onOpenClient != null) {
                        OutlinedButton(onClick = onOpenClient) { Text("Καρτέλα") }
                    }
                }
            }
        }
    }
}

/** Ετικέτα και τιμή σε κελί πλέγματος· κενή τιμή γράφεται «—», ώστε το πλέγμα να μένει ίσιο. */
@Composable
private fun Cell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        SelectionContainer {
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ----------------------------------------------------------- επικοινωνία

/**
 * Η ενδιάμεση επικοινωνία ως συνομιλία: η ΑΑΔΕ αριστερά, ο πελάτης δεξιά,
 * νεότερη πρώτα. Οι δύο πιο πρόσφατες φαίνονται· οι υπόλοιπες με ένα πάτημα.
 */
@Composable
private fun Conversation(notes: List<RequestDetail.Note>, onFiles: () -> Unit) {
    var all by remember(notes) { mutableStateOf(notes.size <= 3) }
    Section("Επικοινωνία με την ΑΑΔΕ · ${notes.size}")
    val shown = if (all) notes else notes.take(2)
    shown.forEach { note ->
        val aade = note.fromAade
        val bubble = if (aade) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        val onBubble = if (aade) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = if (aade) Arrangement.Start else Arrangement.End,
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.9f)
                    .background(bubble, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    (if (aade) note.label else "Πελάτης · " + note.label.lowercase()) +
                        if (note.date.isBlank()) "" else " · " + AadeRequests.date(RequestDetail.stamp(note.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = onBubble.copy(alpha = 0.8f),
                )
                if (note.text.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    SelectionContainer {
                        Text(note.text, style = MaterialTheme.typography.bodyMedium, color = onBubble)
                    }
                }
                if (note.files.isNotEmpty()) FileLinks(note.files, onClick = onFiles, color = onBubble)
            }
        }
    }
    if (!all) {
        TextButton(onClick = { all = true }) {
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Όλη η επικοινωνία (${notes.size})")
        }
    }
}

/** Ονόματα συνημμένων ως σύνδεσμοι — ανοίγουν το παράθυρο των συνημμένων. */
@Composable
private fun FileLinks(names: List<String>, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    Column(Modifier.padding(top = 4.dp)) {
        names.forEach { name ->
            Row(
                Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------- βοηθοί

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

/** Ενότητα που ανοιγοκλείνει από τον τίτλο της. */
@Composable
private fun Collapsible(title: String, initiallyOpen: Boolean, content: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (open) "Κλείσιμο" else "Άνοιγμα",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) content()
}

/** Κείμενο που **αντιγράφεται**: η απάντηση της ΑΑΔΕ πολύ συχνά προωθείται στον πελάτη. */
@Composable
private fun Body(text: String) {
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
}

// ------------------------------------------------------------- συνημμένα

/** Το κουμπί που ανοίγει όλα τα συνημμένα, με πόσα είναι και πόσα λείπουν. */
@Composable
private fun AttachmentsButton(detail: RequestDetail.Detail, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Συνημμένα · ${detail.attachments.size}" +
                if (detail.missing > 0) " (${detail.missing} δεν κατέβηκαν)" else "",
            modifier = Modifier.weight(1f),
        )
        Text(
            listOf(
                detail.submitted.size.takeIf { it > 0 }?.let { "$it του πελάτη" },
                detail.replies.size.takeIf { it > 0 }?.let { "$it της ΑΑΔΕ" },
            ).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Όλα τα συνημμένα του αιτήματος σε ένα παράθυρο, χωρισμένα σε ό,τι έστειλε ο
 * πελάτης, ό,τι έστειλε η ΑΑΔΕ και ό,τι ήρθε με την επικοινωνία.
 */
@Composable
private fun AttachmentsDialog(
    detail: RequestDetail.Detail,
    container: AppContainer,
    client: ClientEntity,
    caseNumber: String,
    status: String,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Συνημμένα · ${detail.attachments.size}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Κλείσιμο")
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    FileGroup("Από την ΑΑΔΕ", detail.replies, container, client, caseNumber, onStatus)
                    FileGroup("Από την επικοινωνία", detail.threadFiles, container, client, caseNumber, onStatus)
                    FileGroup("Υποβλήθηκαν με το αίτημα", detail.submitted, container, client, caseNumber, onStatus)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun FileGroup(
    title: String,
    files: List<RequestDetail.Attachment>,
    container: AppContainer,
    client: ClientEntity,
    caseNumber: String,
    onStatus: (String) -> Unit,
) {
    if (files.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        "$title · ${files.size}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    files.forEach { FileRow(it, container, client, caseNumber, onStatus) }
}

/**
 * Ένα συνημμένο: όνομα, μέγεθος, άνοιγμα και κοινοποίηση.
 *
 * Η κοινοποίηση γράφεται **δύο φορές**, για δύο αναγνώστες. Στο αρχείο
 * ενεργειών, γιατί ένα έγγραφο πελάτη φεύγει από την εφαρμογή προς άλλη και
 * αυτό πρέπει να μπορεί να αποδειχθεί αργότερα — εκεί μόνο ο αριθμός της
 * υπόθεσης, ποτέ το όνομα του αρχείου. Και στο ημερολόγιο αποστολών, που είναι
 * το εργαλείο δουλειάς του λογιστή («τι έστειλα στον τάδε τον Οκτώβριο»), με το
 * όνομα του αρχείου όπως για κάθε έντυπο.
 */
@Composable
private fun FileRow(
    file: RequestDetail.Attachment,
    container: AppContainer,
    client: ClientEntity,
    caseNumber: String,
    onStatus: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    fun open() {
        val target = RequestsStore.attachment(context.filesDir, client.afm, file.saved)
        onStatus(if (target == null) GONE else DocumentActions.open(context, target, file.saved))
    }
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(enabled = file.downloaded) { open() }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (file.downloaded) {
                val size = RequestDetail.size(file.size)
                if (size.isNotBlank()) Text(size, style = MaterialTheme.typography.bodySmall, color = dim)
            } else {
                Text(
                    "Δεν κατέβηκε" + if (file.error.isBlank()) "" else " (${file.error})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (file.downloaded) {
            IconButton(onClick = { open() }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Άνοιγμα")
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = {
                val target = RequestsStore.attachment(context.filesDir, client.afm, file.saved)
                if (target == null) {
                    onStatus(GONE)
                } else {
                    onStatus("")
                    val failure = runCatching {
                        Exports.share(
                            context,
                            target,
                            DocumentActions.mimeOf(file.saved),
                            "Κοινοποίηση συνημμένου",
                        )
                    }.exceptionOrNull()
                    if (failure != null) onStatus("Δεν άνοιξε η κοινοποίηση: ${failure.message}")
                    scope.launch {
                        // Και στο ημερολόγιο αποστολών: ένα έγγραφο της ΑΑΔΕ
                        // που προωθήθηκε είναι «τι έστειλα και πότε», όπως
                        // κάθε άλλο έντυπο.
                        runCatching {
                            container.db.sends().log(
                                RequestSends.file(
                                    clientId = client.id,
                                    afm = client.afm,
                                    clientName = client.displayName,
                                    caseNumber = caseNumber,
                                    fileName = file.name,
                                    at = System.currentTimeMillis(),
                                    error = failure?.let { it.message ?: it.toString() }.orEmpty(),
                                ),
                            )
                        }
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
            }) {
                Icon(Icons.Filled.Share, contentDescription = "Κοινοποίηση")
            }
        }
    }
}

private const val GONE = "Το αρχείο δεν υπάρχει πια στη συσκευή — πάτα την ανανέωση πάνω δεξιά."
