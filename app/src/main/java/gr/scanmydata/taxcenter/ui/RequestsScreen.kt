package gr.scanmydata.taxcenter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.requests.RequestBoard
import gr.scanmydata.taxcenter.requests.RequestsStore
import gr.scanmydata.taxcenter.sched.RequestWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **«Αιτήματα ΑΑΔΕ»** — τα αιτήματα όλων των πελατών, σε μία λίστα.
 *
 * Η καρτέλα του πελάτη απαντά στο «τι γίνεται με τα αιτήματα του τάδε». Εδώ
 * απαντιέται η ερώτηση του γραφείου: **τι περιμένει απάντηση, σε ποιον, και τι
 * άλλαξε από χθες** — χωρίς να ανοίξει κανείς καρτέλες μία-μία.
 *
 * Η οθόνη **διαβάζει μόνο τον δίσκο**: δείχνει ό,τι έφεραν οι έλεγχοι — ο
 * καθημερινός, ή αυτός που ξεκινά από εδώ. Ένα αίτημα ανοίγει ολόκληρο με ένα
 * πάτημα, από τη συσκευή.
 *
 * Κάθε κάρτα γράφει **ποιανού** είναι το αίτημα, τι είδους, πότε υποβλήθηκε,
 * πότε ενημερώθηκε, σε ποια κατάσταση βρίσκεται, και αν είναι αποθηκευμένο.
 * Η σειρά αλλάζει με ένα πάτημα — βλ. [RequestBoard.arrange].
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
    val shown = remember(rows, sort, reversed, filter, query) {
        RequestBoard.arrange(rows, sort, reversed, filter, query)
    }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    Column(modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Αναζήτηση: πελάτης, ΑΦΜ, είδος, αρ. υπόθεσης") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RequestBoard.Filter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        Text(
            orderLabel(sort, reversed) + " · " + when (shown.size) {
                rows.size -> if (rows.size == 1) "1 αίτημα" else "${rows.size} αιτήματα"
                else -> "${shown.size} από ${rows.size}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = dim,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )

        if (fetchState.running) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.weight(1f)) {
            item {
                Spacer(Modifier.height(6.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val summary = RequestBoard.summary(rows)
                        Text("Όλοι οι πελάτες", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                board == null -> "Φόρτωση…"
                                rows.isEmpty() ->
                                    "Δεν έχουν διαβαστεί ακόμη αιτήματα για κανέναν πελάτη. Βάλε πελάτες " +
                                        "στην παρακολούθηση και πάτα «Έλεγχος τώρα», ή άνοιξε την καρτέλα " +
                                        "«Αιτήματα» ενός πελάτη."
                                else -> buildString {
                                    append(if (summary.requests == 1) "1 αίτημα" else "${summary.requests} αιτήματα")
                                    append(if (summary.clients == 1) " σε 1 πελάτη" else " σε ${summary.clients} πελάτες")
                                    append(" · ")
                                    append(
                                        when (summary.pending) {
                                            0 -> "κανένα σε αναμονή"
                                            1 -> "1 σε αναμονή"
                                            else -> "${summary.pending} σε αναμονή"
                                        },
                                    )
                                    if (summary.fresh > 0) {
                                        append(" · ")
                                        append(if (summary.fresh == 1) "1 νέο" else "${summary.fresh} νέα")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                watched.isEmpty() ->
                                    "Κανένας πελάτης στην παρακολούθηση. Διάλεξε από τις Ρυθμίσεις, ή με " +
                                        "τον διακόπτη στην καρτέλα «Αιτήματα» του καθενός."
                                !settings.requestWatchDaily ->
                                    (if (watched.size == 1) "1 πελάτης" else "${watched.size} πελάτες") +
                                        " στην παρακολούθηση, αλλά ο καθημερινός έλεγχος είναι κλειστός: " +
                                        "ελέγχονται μόνο όταν το ζητήσεις."
                                else ->
                                    "Καθημερινός έλεγχος περίπου στις " +
                                        settings.requestWatchHour.toString().padStart(2, '0') + ":00 · " +
                                        (if (watched.size == 1) "1 πελάτης" else "${watched.size} πελάτες") +
                                        ". Αν απαντηθεί ή αλλάξει κάτι, έρχεται ειδοποίηση."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = dim,
                        )
                        // Διαβάζεται σε κάθε ανασύνθεση: ο έλεγχος το γράφει από το παρασκήνιο.
                        val lastRun = remember(reload, fetchState.running) { settings.requestWatchLastRun }
                        if (lastRun > 0L) {
                            Text(
                                "Τελευταίος έλεγχος " + AthensDates.stamp(lastRun) + ": " +
                                    settings.requestWatchLastSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = dim,
                            )
                        }
                        // Ο «Έλεγχος τώρα» αφορά όσους είναι στην παρακολούθηση. Όποιος έχει
                        // αιτήματα εδώ αλλά δεν είναι, πρέπει να φαίνεται — αλλιώς τα αιτήματά
                        // του θα έμοιαζαν ενημερωμένα ενώ δεν τα κοίταξε κανείς.
                        val count = (rows.map { it.clientId }.toSet() - watched).size
                        if (count > 0) {
                            Text(
                                (if (count == 1) "1 πελάτης της λίστας δεν είναι" else "$count πελάτες της λίστας δεν είναι") +
                                    " στην παρακολούθηση: τα αιτήματά τους ενημερώνονται μόνο από την καρτέλα τους.",
                                style = MaterialTheme.typography.bodySmall,
                                color = dim,
                            )
                        }

                        // Χωρίς την άδεια, ο έλεγχος δουλεύει και δεν το λέει σε κανέναν.
                        if (!notificationsOn) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Οι ειδοποιήσεις της εφαρμογής είναι κλειστές: ο έλεγχος θα γίνεται, αλλά " +
                                    "δεν θα έρχεται ειδοποίηση όταν απαντηθεί κάτι.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                enabled = watched.isNotEmpty() && !fetchState.running,
                                onClick = {
                                    // Αποθηκεύει και ό,τι λείπει από τη συσκευή, όχι
                                    // μόνο ό,τι άλλαξε: τον ζήτησε άνθρωπος.
                                    RequestWatch.runNow(context, full = true)
                                    status = "Ο έλεγχος ξεκίνησε για " +
                                        (if (watched.size == 1) "1 πελάτη" else "${watched.size} πελάτες") +
                                        ", έναν-έναν. Η λίστα ανανεώνεται καθώς προχωρά· μπορείς να φύγεις " +
                                        "από την οθόνη."
                                },
                            ) { Text(if (fetchState.running) "Γίνεται έλεγχος…" else "Έλεγχος τώρα") }
                            if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                OutlinedButton(
                                    onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                ) { Text("Άνοιγμα ειδοποιήσεων") }
                            }
                            OutlinedButton(onClick = onOpenSettings) { Text("Ρυθμίσεις") }
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
                Spacer(Modifier.height(6.dp))
            }

            if (board != null && rows.isNotEmpty() && shown.isEmpty()) {
                item {
                    Text(
                        "Κανένα αίτημα δεν ταιριάζει με το φίλτρο ή την αναζήτηση.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            items(shown, key = { it.key }) { row ->
                RequestCard(
                    request = row.request,
                    fresh = row.fresh,
                    saved = row.saved,
                    outdated = row.outdated,
                    clientName = row.clientName,
                    clientAfm = row.afm,
                    onOpen = {
                        val client = clients.firstOrNull { it.id == row.clientId } ?: return@RequestCard
                        opener.open(client, row.request.id)
                        // Το άνοιγμα **είναι** η ανάγνωση.
                        if (row.fresh) {
                            scope.launch {
                                val left = withContext(Dispatchers.IO) {
                                    RequestsStore.markSeen(context.filesDir, client.afm, setOf(row.request.id))
                                }
                                if (left.isEmpty()) {
                                    settings.requestWatchFresh = settings.requestWatchFresh - client.id
                                }
                                reload++
                            }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

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
        if (reversed) "παλαιότερη ενημέρωση πρώτα" else "πιο πρόσφατη ενημέρωση πρώτα"
    RequestBoard.Sort.SUBMITTED ->
        if (reversed) "παλαιότερη υποβολή πρώτα" else "πιο πρόσφατη υποβολή πρώτα"
    RequestBoard.Sort.STATUS ->
        if (reversed) "όσα έκλεισαν πρώτα" else "νέα και όσα περιμένουν πρώτα"
    RequestBoard.Sort.CLIENT ->
        if (reversed) "πελάτες Ω → Α" else "πελάτες Α → Ω"
}
