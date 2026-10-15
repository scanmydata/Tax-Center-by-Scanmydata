package gr.scanmydata.taxcenter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestBoard
import gr.scanmydata.taxcenter.requests.RequestsStore
import gr.scanmydata.taxcenter.sched.RequestWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **«Αιτήματα ΑΑΔΕ»** — τα αιτήματα όλων των πελατών, **ανά πελάτη**.
 *
 * Η καρτέλα του πελάτη απαντά στο «τι γίνεται με τα αιτήματα του τάδε». Εδώ
 * απαντιέται η ερώτηση του γραφείου: **ποιος περιμένει απάντηση, και σε ποιον
 * άλλαξε κάτι από χθες** — χωρίς να ανοίξει κανείς καρτέλες μία-μία.
 *
 * ## Μία κάρτα ανά πελάτη
 *
 * Κάθε κάρτα γράφει ποιος είναι, **πόσα** αιτήματα έχει και **σε ποια
 * κατάσταση** το καθένα. Το πάτημα ανοίγει τα αιτήματά του από κάτω — ή, όταν
 * έχει ένα, το ίδιο το αίτημα. Δύο κάρτες με το ίδιο όνομα σκόρπιες στη λίστα
 * δεν έλεγαν «έχει δύο»· το έλεγαν μόνο σε όποιον τις μετρούσε.
 *
 * ## Η οθόνη είναι η λίστα
 *
 * Αναζήτηση, φίλτρα και σειρά ζουν πίσω από **δύο κουμπιά** στην κορυφή. Όταν
 * ήταν πάντα ανοιχτά έπιαναν τη μισή οθόνη, για κάτι που αλλάζει κανείς μία
 * φορά. Η γραμμή δίπλα στα κουμπιά λέει πάντα τι ισχύει — ένα φίλτρο που δεν
 * φαίνεται είναι ο γρηγορότερος τρόπος να νομίσει κανείς ότι χάθηκαν αιτήματα.
 *
 * Η οθόνη **διαβάζει μόνο τον δίσκο**: δείχνει ό,τι έφεραν οι έλεγχοι.
 *
 * @param onOpenClient η καρτέλα «Αιτήματα» ενός πελάτη
 */
@Composable
fun RequestsScreen(
    container: AppContainer,
    onOpenClient: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settings
    val fetchState by container.fetch.state.collectAsState()
    val clients: List<ClientEntity> by container.repository.observeClients().collectAsState(initial = emptyList())

    var reload by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(RequestBoard.Sort.UPDATED) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(RequestBoard.Filter.ALL) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    // Ο «Έλεγχος τώρα» τρέχει στο WorkManager, πελάτη-πελάτη. Το παράθυρο
    // αναμονής μένει όσο υπάρχει δουλειά στην ουρά — όχι όσο τρέχει μία λήψη,
    // που σβήνει για μια στιγμή ανάμεσα σε δύο πελάτες.
    var watching by remember { mutableStateOf(false) }
    val watchInfos by remember(context) {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(RequestWatch.WORK_CONTINUE)
    }.collectAsState(initial = emptyList())
    val watchActive = watchInfos.any { !it.state.isFinished }
    LaunchedEffect(watching, watchActive) {
        // Ένα περιθώριο, γιατί αμέσως μετά το πάτημα η ουρά δείχνει ακόμη τον
        // προηγούμενο, τελειωμένο έλεγχο.
        if (watching && !watchActive) {
            delay(3_000)
            watching = false
            reload++
        }
    }
    // Οι πελάτες των οποίων τα αιτήματα είναι ανοιχτά από κάτω.
    val expanded = remember { mutableStateListOf<Long>() }

    val opener = rememberRequestOpener(container) { reload++ }

    // Ο έλεγχος τρέχει στο παρασκήνιο, πελάτη-πελάτη, μέσα από την ίδια ουρά με
    // κάθε λήψη· κάθε φορά που τελειώνει ένας, η λίστα ξαναδιαβάζεται.
    LaunchedEffect(fetchState.running) {
        if (!fetchState.running) reload++
    }

    // `null` = δεν έχει διαβαστεί ακόμη ο δίσκος· άλλο πράγμα από «κανένα αίτημα».
    val board by produceState<Board?>(null, clients, reload) {
        value = withContext(Dispatchers.IO) { load(context.filesDir, clients) }
    }

    var notificationsOn by remember { mutableStateOf(notificationsAllowed(context)) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsOn = granted
        if (!granted) {
            status = "Οι ειδοποιήσεις έμειναν κλειστές. Άνοιξέ τες από τις ρυθμίσεις του Android " +
                "για την εφαρμογή — αλλιώς ο έλεγχος γίνεται, αλλά δεν θα το μαθαίνεις."
        }
    }

    val watched = settings.requestWatchClients
    val rows = board?.rows.orEmpty()
    val groups = remember(rows, sort, reversed, filter, query) {
        RequestBoard.groups(rows, sort, reversed, filter, query)
    }
    val shown = groups.sumOf { it.rows.size }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    /** Το άνοιγμα **είναι** η ανάγνωση: σβήνει τη σήμανση «νέο». */
    fun openRequest(row: RequestBoard.Row) {
        val client = clients.firstOrNull { it.id == row.clientId } ?: return
        opener.open(client, row.request.id)
        if (!row.fresh) return
        scope.launch {
            val left = withContext(Dispatchers.IO) {
                RequestsStore.markSeen(context.filesDir, client.afm, setOf(row.request.id))
            }
            if (left.isEmpty()) settings.requestWatchFresh = settings.requestWatchFresh - client.id
            reload++
        }
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        // Όλη η «εργαλειοθήκη» σε μία γραμμή: τι βλέπεις, και δύο κουμπιά.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                val summary = RequestBoard.summary(rows)
                Text(
                    when {
                        board == null -> "Φόρτωση…"
                        rows.isEmpty() -> "Κανένα αίτημα ακόμη"
                        else -> buildString {
                            append(if (summary.clients == 1) "1 πελάτης" else "${summary.clients} πελάτες")
                            append(" · ")
                            append(if (summary.requests == 1) "1 αίτημα" else "${summary.requests} αιτήματα")
                            if (summary.fresh > 0) {
                                append(" · ")
                                append(if (summary.fresh == 1) "1 νέο" else "${summary.fresh} νέα")
                            }
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        // Το φίλτρο πρώτο: είναι αυτό που κρύβει αιτήματα.
                        if (filter != RequestBoard.Filter.ALL) append(filter.label).append(" · ")
                        append(orderLabel(sort, reversed))
                        if (shown != rows.size) append(" · φαίνονται $shown από ${rows.size}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (filter != RequestBoard.Filter.ALL || query.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        dim
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { searching = true }) {
                Icon(Icons.Filled.Search, contentDescription = "Αναζήτηση")
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { showFilters = !showFilters }) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = if (showFilters) "Κλείσιμο φίλτρων" else "Φίλτρα και σειρά",
                    tint = if (showFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Η αναζήτηση που ισχύει φαίνεται πάντα, και σβήνει με ένα πάτημα.
        if (query.isNotBlank()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Αναζήτηση: «$query»",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                TextButton(onClick = { query = "" }) { Text("Καθαρισμός") }
            }
        }

        if (showFilters) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Φίλτρο", style = MaterialTheme.typography.labelMedium, color = dim)
                RequestBoard.Filter.entries.forEach { option ->
                    FilterChip(
                        selected = option == filter,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Σειρά", style = MaterialTheme.typography.labelMedium, color = dim)
                    RequestBoard.Sort.entries.forEach { option ->
                        FilterChip(
                            selected = option == sort,
                            onClick = {
                                // Δεύτερο πάτημα στο ίδιο κριτήριο γυρίζει τη φορά.
                                if (option == sort) {
                                    reversed = !reversed
                                } else {
                                    sort = option
                                    reversed = false
                                }
                            },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { reversed = !reversed }) {
                    Icon(
                        if (reversed) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                        contentDescription = "Αντιστροφή σειράς",
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        if (fetchState.running) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f)) {
            item(key = "check") {
                CheckCard(
                    watched = watched.size,
                    daily = settings.requestWatchDaily,
                    hour = settings.requestWatchHour,
                    // Διαβάζεται ξανά όταν τελειώνει ένας έλεγχος: τον γράφει το παρασκήνιο.
                    lastRun = remember(reload, fetchState.running) { settings.requestWatchLastRun },
                    lastSummary = remember(reload, fetchState.running) { settings.requestWatchLastSummary },
                    // Ο «Έλεγχος τώρα» αφορά όσους είναι στην παρακολούθηση. Όποιος έχει
                    // αιτήματα εδώ αλλά δεν είναι, πρέπει να φαίνεται — αλλιώς τα αιτήματά
                    // του θα έμοιαζαν ενημερωμένα ενώ δεν τα κοίταξε κανείς.
                    unwatched = (rows.map { it.clientId }.toSet() - watched).size,
                    empty = board != null && rows.isEmpty(),
                    running = fetchState.running,
                    notificationsOn = notificationsOn,
                    status = status,
                    onCheck = {
                        // Αποθηκεύει και ό,τι λείπει από τη συσκευή, όχι μόνο ό,τι
                        // άλλαξε: τον ζήτησε άνθρωπος.
                        RequestWatch.runNow(context, full = true)
                        watching = true
                        status = ""
                    },
                    onNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onSettings = onOpenSettings,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (board != null && rows.isNotEmpty() && groups.isEmpty()) {
                item(key = "none") {
                    Text(
                        "Κανένα αίτημα δεν ταιριάζει με το φίλτρο ή την αναζήτηση.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            groups.forEach { group ->
                val unfolded = group.clientId in expanded
                item(key = "client:${group.clientId}") {
                    ClientGroupCard(
                        group = group,
                        open = unfolded,
                        onClick = {
                            // Ένα αίτημα: ανοίγει το ίδιο — δεν υπάρχει τίποτα να «ξεδιπλωθεί».
                            if (group.rows.size == 1) {
                                openRequest(group.rows.first())
                            } else if (unfolded) {
                                expanded.remove(group.clientId)
                            } else {
                                expanded.add(group.clientId)
                            }
                        },
                    )
                }
                if (unfolded && group.rows.size > 1) {
                    items(group.rows, key = { it.key }) { row ->
                        // Μέσα στον πελάτη: χωρίς το όνομά του, και λίγο πιο μέσα.
                        Box(Modifier.padding(start = 14.dp)) {
                            RequestCard(
                                request = row.request,
                                fresh = row.fresh,
                                saved = row.saved,
                                outdated = row.outdated,
                                onOpen = { openRequest(row) },
                            )
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (searching) {
        SearchDialog(
            initial = query,
            onDismiss = { searching = false },
            onSearch = {
                query = it.trim()
                searching = false
            },
        )
    }

    FetchBusyOverlay(
        container = container,
        visible = watching,
        title = "Έλεγχος αιτημάτων ΑΑΔΕ",
        text = (if (watched.size == 1) "1 πελάτης" else "${watched.size} πελάτες") +
            " στην παρακολούθηση, ένας-ένας.",
        detail = "Για κάθε πελάτη: σύνδεση στην ΑΑΔΕ, η λίστα των αιτημάτων, και όσα " +
            "λείπουν ή άλλαξαν με τα συνημμένα τους. Ο έλεγχος συνεχίζει και αν " +
            "κλείσεις το παράθυρο ή φύγεις από την οθόνη.",
        onHide = { watching = false },
        onCancel = {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RequestWatch.WORK_CONTINUE)
            container.fetch.cancel()
            watching = false
            status = "Ο έλεγχος διακόπηκε. Ό,τι αποθηκεύτηκε μέχρι εκεί μένει."
        },
    )

    val opened = opener.opened
    if (opened != null) {
        val (client, id) = opened
        val row = rows.firstOrNull { it.clientId == client.id && it.request.id == id }
        if (row != null) {
            RequestDialog(
                container = container,
                client = client,
                request = row.request,
                reload = reload,
                outdated = row.outdated,
                queueBusy = fetchState.running,
                opener = opener,
                showClient = true,
                onOpenClient = {
                    opener.close()
                    onOpenClient(client.id)
                },
            )
        }
    }
}

// ------------------------------------------------------------- ο πελάτης

/**
 * Ένας πελάτης με τα αιτήματά του: ποιος, πόσα, σε ποια κατάσταση.
 *
 * Κάτω από τους μετρητές γράφεται **κάθε** αίτημα σε μία γραμμή — είδος και
 * κατάσταση — ώστε η κάρτα να απαντά μόνη της στο «τι έχει» χωρίς να ανοίξει.
 */
@Composable
private fun ClientGroupCard(
    group: RequestBoard.Group,
    open: Boolean,
    onClick: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val fresh = group.fresh > 0
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
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
                    if (group.fresh == 1) "1 ΝΕΟ — ΑΠΑΝΤΗΣΗ Η ΑΛΛΑΓΗ" else "${group.fresh} ΝΕΑ — ΑΠΑΝΤΗΣΕΙΣ Η ΑΛΛΑΓΕΣ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.clientName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text("ΑΦΜ ${group.afm}", style = MaterialTheme.typography.labelSmall, color = dim)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), shape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        group.count,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(group.statuses, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }

            // Κάθε αίτημα σε μία γραμμή. Κρύβονται όταν είναι ανοιχτά από κάτω.
            if (!open || group.rows.size == 1) {
                Spacer(Modifier.height(8.dp))
                group.rows.take(LINES).forEach { row ->
                    Row {
                        Text(
                            row.request.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(
                            statusOf(row.request),
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                row.request.rejected -> MaterialTheme.colorScheme.error
                                row.request.answered -> MaterialTheme.colorScheme.primary
                                else -> dim
                            },
                        )
                    }
                }
                if (group.rows.size > LINES) {
                    Text(
                        "και άλλα ${group.rows.size - LINES}",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (group.updated.isBlank()) "" else "Τελευταία κίνηση " + AadeRequests.date(group.updated),
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(
                    when {
                        group.rows.size == 1 -> "άνοιγμα αιτήματος"
                        open -> "κλείσιμο"
                        else -> "άνοιγμα των ${group.rows.size}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Πόσα αιτήματα γράφει η κάρτα του πελάτη πριν πει «και άλλα». */
private const val LINES = 4

/** Η κατάσταση με τα λόγια της πύλης — ίδιος κανόνας με το [StatusPill]. */
private fun statusOf(request: AadeRequests.Request): String = when {
    request.rejected -> "Απορρίφθηκε"
    request.status.isNotBlank() -> request.status
    request.answered -> "Απαντημένο"
    else -> "Σε αναμονή"
}

// ------------------------------------------------------------- ο έλεγχος

/**
 * Ο έλεγχος όλων των πελατών, μαζεμένος: σε τι κατάσταση είναι η παρακολούθηση,
 * πότε έτρεξε, και το κουμπί. Κυλά μαζί με τη λίστα — δεν είναι κάτι που
 * χρειάζεται κανείς μπροστά του όσο διαβάζει αιτήματα.
 */
@Composable
private fun CheckCard(
    watched: Int,
    daily: Boolean,
    hour: Int,
    lastRun: Long,
    lastSummary: String,
    unwatched: Int,
    empty: Boolean,
    running: Boolean,
    notificationsOn: Boolean,
    status: String,
    onCheck: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val who = if (watched == 1) "1 πελάτης" else "$watched πελάτες"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    watched == 0 -> "Κανένας πελάτης στην παρακολούθηση — διάλεξε από τις Ρυθμίσεις."
                    !daily -> "Καθημερινός έλεγχος κλειστός · $who στη λίστα"
                    else -> "Καθημερινός έλεγχος " + hour.toString().padStart(2, '0') + ":00 · $who"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (empty) {
                Text(
                    "Δεν έχουν διαβαστεί ακόμη αιτήματα. Πάτα «Έλεγχος τώρα», ή άνοιξε την καρτέλα " +
                        "«Αιτήματα» ενός πελάτη.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
            }
            if (lastRun > 0L) {
                Text(
                    "Τελευταίος έλεγχος " + AthensDates.stamp(lastRun) + ": " + lastSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (unwatched > 0) {
                Text(
                    (if (unwatched == 1) "1 πελάτης της λίστας είναι" else "$unwatched πελάτες της λίστας είναι") +
                        " εκτός παρακολούθησης: ενημερώνονται μόνο από την καρτέλα τους.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
            }
            // Χωρίς την άδεια, ο έλεγχος δουλεύει και δεν το λέει σε κανέναν.
            if (!notificationsOn) {
                Text(
                    "Οι ειδοποιήσεις της εφαρμογής είναι κλειστές: δεν θα μαθαίνεις όταν απαντηθεί κάτι.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(enabled = watched > 0 && !running, onClick = onCheck) {
                    Text(if (running) "Γίνεται έλεγχος…" else "Έλεγχος τώρα")
                }
                if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(onClick = onNotifications) { Text("Ειδοποιήσεις") }
                }
                OutlinedButton(onClick = onSettings) { Text("Ρυθμίσεις") }
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ----------------------------------------------------------- η αναζήτηση

/**
 * Η αναζήτηση, σε αναδυόμενο: ένα πεδίο που φαίνεται μόνο όταν το ζητήσεις.
 *
 * Ψάχνει όταν πατηθεί «Αναζήτηση» και όχι σε κάθε γράμμα: η λίστα είναι πίσω
 * από τον διάλογο, και ένα αποτέλεσμα που αλλάζει εκεί που δεν φαίνεται δεν
 * βοηθά κανέναν.
 */
@Composable
private fun SearchDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    // Το πληκτρολόγιο ανοίγει μόνο του: ο χρήστης πάτησε «αναζήτηση» για να γράψει.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Αναζήτηση αιτημάτων") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Πελάτης, ΑΦΜ, είδος ή αρ. υπόθεσης") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    "Χωρίς τόνους και κεφαλαία: το «εισοδημα» βρίσκει το «Εισόδημα».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSearch(text) }) { Text("Αναζήτηση") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Άκυρο") }
                if (initial.isNotBlank()) {
                    TextButton(onClick = { onSearch("") }) { Text("Καθαρισμός") }
                }
            }
        },
    )
}

// ------------------------------------------------------------- δεδομένα

/** Ό,τι διαβάστηκε από τον δίσκο. Χωριστός τύπος για να ξεχωρίζει το «δεν διαβάστηκε ακόμη». */
private data class Board(val rows: List<RequestBoard.Row>)

/**
 * Τα αιτήματα κάθε πελάτη που έχει ελεγχθεί έστω μία φορά. Ένα μικρό αρχείο ανά
 * πελάτη· όποιος δεν έχει φάκελο αιτημάτων κοστίζει μία αναζήτηση καταλόγου.
 */
private fun load(filesDir: java.io.File, clients: List<ClientEntity>): Board {
    val rows = ArrayList<RequestBoard.Row>()
    for (client in clients) {
        if (client.deleted || client.afm.isBlank()) continue
        val snapshot = RequestsStore.load(filesDir, client.afm)
        if (snapshot.at == 0L) continue
        for (request in snapshot.requests) {
            rows += RequestBoard.Row(
                clientId = client.id,
                clientName = client.displayName,
                afm = client.afm,
                request = request,
                fresh = request.id in snapshot.fresh,
                saved = snapshot.saved[request.id],
                outdated = request.id in snapshot.outdated,
            )
        }
    }
    return Board(rows)
}

private fun notificationsAllowed(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** Τι σημαίνει η τωρινή σειρά, με λόγια — ένα βέλος μόνο του δεν το λέει. */
private fun orderLabel(sort: RequestBoard.Sort, reversed: Boolean): String = when (sort) {
    RequestBoard.Sort.UPDATED ->
        if (reversed) "παλαιότερη κίνηση πρώτα" else "πιο πρόσφατη κίνηση πρώτα"
    RequestBoard.Sort.SUBMITTED ->
        if (reversed) "παλαιότερη υποβολή πρώτα" else "πιο πρόσφατη υποβολή πρώτα"
    RequestBoard.Sort.STATUS ->
        if (reversed) "όσα έκλεισαν πρώτα" else "νέα και όσα περιμένουν πρώτα"
    RequestBoard.Sort.CLIENT ->
        if (reversed) "πελάτες Ω → Α" else "πελάτες Α → Ω"
}
