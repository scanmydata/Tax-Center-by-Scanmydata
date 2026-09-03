package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.gdpr.Exports
import gr.scanmydata.taxcenter.google.GoogleAuthorizer
import gr.scanmydata.taxcenter.google.rememberGoogleAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ημερολόγιο αποστολών.
 *
 * Δείχνει, ανά μήνα ή εβδομάδα, τι στάλθηκε και σε ποιον. Οι **αποτυχημένες**
 * αποστολές εμφανίζονται κι αυτές, με κόκκινο: μια αποστολή που δεν έφτασε είναι
 * ακριβώς αυτό που πρέπει να δει ο λογιστής.
 *
 * Οι ώρες υπολογίζονται σε ζώνη Αθηνών, όχι UTC — το «τι έστειλα χθες» πρέπει να
 * σημαίνει χθες για τον χρήστη.
 */
private val ZONE: ZoneId = ZoneId.of("Europe/Athens")
private val GREEK = Locale("el", "GR")

@Composable
fun SendCalendarScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val context = LocalContext.current

    var weekView by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(LocalDate.now(ZONE)) }
    // **Καμία** προεπιλεγμένη ημέρα.
    //
    // Ήταν «σήμερα», και επειδή τις περισσότερες μέρες δεν στέλνεται τίποτα, το
    // ημερολόγιο άνοιγε άδειο: ο χρήστης έβλεπε μια οθόνη χωρίς αποστολές και
    // συμπέραινε ότι χάθηκαν. Η σωστή προεπιλογή είναι ολόκληρη η περίοδος.
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var kindFilter by remember { mutableStateOf("") }
    var failedOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    // Τα φίλτρα είναι κλειστά εξ ορισμού. Έπαιρναν τέσσερις σειρές μόνιμα, και
    // σε τηλέφωνο έμεναν δύο-τρεις γραμμές για τη λίστα — που είναι η ουσία.
    var showFilters by remember { mutableStateOf(false) }
    // Το πλέγμα **συμπιέζεται**, δεν εξαφανίζεται.
    //
    // Η προηγούμενη έκδοση το έκρυβε ολόκληρο, και μαζί του κάθε ένδειξη για το
    // πού βρίσκεσαι μέσα στον μήνα — η οθόνη έμοιαζε να «χάνει τα πάντα».
    // Συμπιεσμένο σημαίνει μία σειρά αντί για έξι: η εβδομάδα γύρω από την
    // επιλεγμένη ημέρα, με τους ίδιους αριθμούς αποστολών στα κελιά.
    //
    // Κρίσιμο: η σύμπτυξη αλλάζει **μόνο** το πλέγμα. Το διάστημα που ρωτιέται
    // η βάση μένει ο μήνας, οπότε η λίστα από κάτω δεν κονταίνει.
    //
    // `MutableState` και όχι `by`: η τιμή γράφεται μέσα από τη σύνδεση
    // nested-scroll παρακάτω, που ζει σε `remember` και δεν κρατά delegate.
    val calendarOpen = remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // Σύμπτυξη με την **κίνηση**, όχι μόνο με την επιλογή ημέρας.
    //
    // Η προηγούμενη έκδοση μάζευε το πλέγμα μόνο όταν διάλεγες ημέρα. Όποιος
    // κατέβαινε κατευθείαν στη λίστα κρατούσε το ημερολόγιο στη μέση οθόνη και
    // κυλούσε τις αποστολές μέσα από μια γραμματοθυρίδα.
    //
    // Ο έλεγχος γίνεται στο `onPreScroll`, δηλαδή στην πρόθεση του χρήστη πριν
    // καν την καταναλώσει η λίστα: έτσι δουλεύει και όταν η λίστα είναι κοντή
    // και δεν κυλά καθόλου. Δεν καταναλώνεται τίποτα — επιστρέφεται μηδέν.
    val collapseOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -3f) {
                    calendarOpen.value = false
                } else if (available.y > 3f && !listState.canScrollBackward) {
                    // Ξαναπάνω από την κορυφή της λίστας: ο χρήστης γυρίζει στο
                    // ημερολόγιο, όχι στις εγγραφές.
                    calendarOpen.value = true
                }
                return Offset.Zero
            }
        }
    }

    val range = remember(anchor, weekView) { visibleRange(anchor, weekView) }
    val all: List<SendEntity> by container.db.sends()
        .observeBetween(range.first.atStartOfDay(ZONE).toInstant().toEpochMilli(),
                        range.second.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli())
        .collectAsState(initial = emptyList())

    val sends = remember(all, kindFilter, failedOnly, query) {
        val q = query.trim().lowercase()
        all.filter { send ->
            (kindFilter.isBlank() || send.kind == kindFilter) &&
                (!failedOnly || send.failed) &&
                (q.isBlank() || send.afm.contains(q) || send.clientName.lowercase().contains(q))
        }
    }

    val byDay = remember(sends) { sends.groupBy { it.sentAt.toLocalDate() } }

    Column(modifier.padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                headerLabel(anchor, weekView),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { anchor = if (weekView) anchor.minusWeeks(1) else anchor.minusMonths(1) }) {
                    Text("‹", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { anchor = if (weekView) anchor.plusWeeks(1) else anchor.plusMonths(1) }) {
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
                // Σύμπτυξη/ανάπτυξη. Δεν κρύβει το ημερολόγιο — αλλάζει μεταξύ
                // έξι σειρών και μίας.
                IconButton(
                    enabled = !weekView,
                    onClick = { calendarOpen.value = !calendarOpen.value },
                ) {
                    Text(
                        if (calendarOpen.value && !weekView) "⌃" else "⌄",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = !weekView, onClick = { weekView = false }, label = { Text("Μήνας") })
            FilterChip(selected = weekView, onClick = { weekView = true }, label = { Text("Εβδομάδα") })
            Spacer(Modifier.weight(1f))
            val activeFilters = listOf(
                kindFilter.isNotBlank(),
                failedOnly,
                query.isNotBlank(),
            ).count { it }
            TextButton(onClick = { showFilters = !showFilters }) {
                Text(if (activeFilters > 0) "Φίλτρα ($activeFilters)" else "Φίλτρα")
            }
        }

        if (showFilters) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = kindFilter.isBlank(),
                    onClick = { kindFilter = "" },
                    label = { Text("Όλα") },
                )
                FilterChip(
                    selected = kindFilter == SendEntity.KIND_DOCUMENTS,
                    onClick = { kindFilter = SendEntity.KIND_DOCUMENTS },
                    label = { Text("Έντυπα") },
                )
                FilterChip(
                    selected = kindFilter == SendEntity.KIND_CREDENTIALS,
                    onClick = { kindFilter = SendEntity.KIND_CREDENTIALS },
                    label = { Text("Στοιχεία") },
                )
                FilterChip(
                    selected = failedOnly,
                    onClick = { failedOnly = !failedOnly },
                    label = { Text("Μόνο αποτυχίες") },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Φίλτρο πελάτη (ΑΦΜ ή επωνυμία)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))
        // Σύνοψη της περιόδου: το «πόσες απέτυχαν» είναι η μόνη ερώτηση που
        // θέλει άμεση απάντηση όταν ανοίγεις το ημερολόγιο.
        Text(
            buildString {
                append(sends.size).append(" αποστολές")
                val failed = sends.count { it.failed }
                if (failed > 0) append("  ·  ").append(failed).append(" απέτυχαν")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (sends.any { it.failed }) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(12.dp))
        WeekdayHeader()
        CalendarGrid(
            // Συμπιεσμένο = η εβδομάδα γύρω από την επιλεγμένη (ή τη σημερινή)
            // ημέρα. Οι ημέρες εκτός μήνα ξεθωριάζουν ήδη από το `currentMonth`.
            days = if (weekView || !calendarOpen.value) {
                calendarDays(selected ?: LocalDate.now(ZONE), true)
            } else {
                calendarDays(anchor, false)
            },
            currentMonth = YearMonth.from(anchor),
            byDay = byDay,
            selected = selected,
            onSelect = {
                // Δεύτερο πάτημα στην ίδια ημέρα την ξεδιαλέγει. Χωρίς αυτό, ο
                // μόνος τρόπος να γυρίσεις σε ολόκληρη την περίοδο ήταν ένα
                // κουμπί κειμένου παρακάτω, που δεν το έβρισκε κανείς.
                selected = if (selected == it) null else it
                // Η δουλειά είναι πια η λίστα από κάτω — αλλά το ημερολόγιο
                // μένει, συμπιεσμένο.
                calendarOpen.value = false
            },
        )

        Spacer(Modifier.height(12.dp))
        // Μια επιλεγμένη ημέρα **χωρίς** αποστολές δεν αδειάζει την οθόνη: το
        // λέει, και δείχνει πάλι ολόκληρη την περίοδο. Μια λίστα που γίνεται
        // κενή μοιάζει με απώλεια δεδομένων, όχι με φίλτρο που δεν βρήκε τίποτα.
        val ofDay = selected?.let { byDay[it].orEmpty() }.orEmpty()
        val dayEmpty = selected != null && ofDay.isEmpty()
        val listed = if (selected == null || dayEmpty) sends else ofDay
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        selected == null -> "Όλη η περίοδος"
                        dayEmpty -> selected!!.format(dayFormatter) + " — καμία αποστολή"
                        else -> selected!!.format(dayFormatter)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        dayEmpty -> "εμφανίζονται και οι ${sends.size} της περιόδου"
                        listed.isEmpty() -> "καμία αποστολή σε αυτή την περίοδο"
                        else -> "${listed.size} αποστολές"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (selected != null) {
                TextButton(onClick = { selected = null }) { Text("Όλη η περίοδος") }
            }
            TextButton(
                enabled = sends.isNotEmpty(),
                onClick = {
                    scope.launch {
                        status = try {
                            val file = withContext(Dispatchers.IO) { Exports.sendsCsv(context, sends) }
                            Exports.share(context, file, "text/csv", "Ημερολόγιο αποστολών")
                            ""
                        } catch (e: Exception) {
                            "Η εξαγωγή απέτυχε: ${e.message}"
                        }
                    }
                },
            ) { Text("Εξαγωγή CSV") }
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(6.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).nestedScroll(collapseOnScroll),
        ) {
            items(listed, key = { it.id }) { send ->
                SendRow(
                    send = send,
                    onRetry = if (send.failed) {
                        {
                            scope.launch {
                                status = "Επανάληψη προς ${send.toEmail}…"
                                status = retrySend(container, authorizer, send)
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Ξαναστέλνει μια αποτυχημένη αποστολή.
 *
 * Δεν «επιδιορθώνει» την παλιά εγγραφή: δημιουργείται **νέα**, ώστε το
 * ημερολόγιο να δείχνει και την αποτυχία και την επιτυχία. Το αρχείο του τι
 * πραγματικά συνέβη δεν ξαναγράφεται.
 *
 * Τα έγγραφα βρίσκονται από τα ονόματα αρχείων που κρατήθηκαν. Αν η πολιτική
 * διατήρησης τα έχει σβήσει στο μεταξύ, το λέει αντί να στείλει άδειο μήνυμα.
 */
private suspend fun retrySend(
    container: AppContainer,
    authorizer: GoogleAuthorizer,
    send: SendEntity,
): String = try {
    val client = container.db.clients().byId(send.clientId)
    when {
        client == null -> "Ο πελάτης δεν υπάρχει πια."
        send.kind == SendEntity.KIND_CREDENTIALS -> {
            // Αν η αρχική αποστολή περιείχε κωδικούς, το λέει η λίστα
            // περιεχομένου — δεν το ξαναμαντεύουμε από τις ρυθμίσεις.
            val hadSecrets = send.items.contains("Συνθηματικό")
            val token = authorizer.accessToken()
            val result = withContext(Dispatchers.IO) {
                container.mail.sendOwnDetails(token, client, hadSecrets)
            }
            if (result.failed) "Απέτυχε ξανά: ${result.error}" else "Στάλθηκε."
        }
        else -> {
            val names = send.items.lines().filter { it.isNotBlank() }
            val documents = container.db.documents().byClientAndNames(client.id, names)
            if (documents.isEmpty()) {
                "Τα έντυπα δεν υπάρχουν πια στη συσκευή — κατέβασέ τα ξανά."
            } else {
                val token = authorizer.accessToken()
                val result = withContext(Dispatchers.IO) {
                    container.mail.sendDocuments(token, client, documents)
                }
                if (result.failed) "Απέτυχε ξανά: ${result.error}" else "Στάλθηκε."
            }
        }
    }
} catch (e: GoogleAuthorizer.ConsentRequired) {
    "Χρειάζεται σύνδεση με Google από τις Ρυθμίσεις."
} catch (e: Exception) {
    "Απέτυχε: ${e.message}"
}

// --------------------------------------------------------------------- πλέγμα

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        // Η εβδομάδα ξεκινά Δευτέρα, όπως στην Ελλάδα.
        for (d in DayOfWeek.entries) {
            Text(
                d.getDisplayName(TextStyle.NARROW, GREEK),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    days: List<LocalDate>,
    currentMonth: YearMonth,
    byDay: Map<LocalDate, List<SendEntity>>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    Column {
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(
                        day = day,
                        inMonth = YearMonth.from(day) == currentMonth,
                        sends = byDay[day].orEmpty(),
                        isSelected = day == selected,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    sends: List<SendEntity>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFailure = sends.any { it.failed }
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (inMonth) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            if (sends.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                // Αριθμός και όχι κουκκίδα: «3 αποστολές» και «30 αποστολές»
                // έδειχναν ακριβώς το ίδιο, και ο λογιστής άνοιγε κάθε μέρα
                // ξεχωριστά για να δει αν άξιζε.
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(
                            if (hasFailure) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        sends.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasFailure) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- μία γραμμή

@Composable
private fun SendRow(send: SendEntity, onRetry: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(send.clientName, style = MaterialTheme.typography.titleSmall)
                Text(
                    send.sentAt.toLocalTimeLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Text(send.toEmail, style = MaterialTheme.typography.bodySmall)
            Text(
                buildString {
                    append(
                        when (send.kind) {
                            SendEntity.KIND_CREDENTIALS -> "Στοιχεία πελάτη"
                            else -> "Φορολογικά έντυπα"
                        },
                    )
                    if (send.itemCount > 0) append(" · ${send.itemCount}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            if (send.failed) {
                Text(
                    "Απέτυχε: ${send.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("Επανάληψη") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ βοηθητικά

private val dayFormatter = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", GREEK)
private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm", GREEK)

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZONE).toLocalDate()

private fun Long.toLocalTimeLabel(): String =
    Instant.ofEpochMilli(this).atZone(ZONE).toLocalTime().format(timeFormatter)

private fun headerLabel(anchor: LocalDate, weekView: Boolean): String = if (weekView) {
    val start = anchor.with(DayOfWeek.MONDAY)
    "${start.dayOfMonth} – ${start.plusDays(6).format(dayFormatter)}"
} else {
    val month = YearMonth.from(anchor)
    "${month.month.getDisplayName(TextStyle.FULL_STANDALONE, GREEK)} ${month.year}"
}

/** Το διάστημα που ερωτάται η βάση — καλύπτει ό,τι φαίνεται στο πλέγμα. */
private fun visibleRange(anchor: LocalDate, weekView: Boolean): Pair<LocalDate, LocalDate> {
    val days = calendarDays(anchor, weekView)
    return days.first() to days.last()
}

/** Πάντα πλήρεις εβδομάδες Δευτέρα–Κυριακή, ώστε το πλέγμα να μη «χωλαίνει». */
private fun calendarDays(anchor: LocalDate, weekView: Boolean): List<LocalDate> {
    if (weekView) {
        val start = anchor.with(DayOfWeek.MONDAY)
        return (0L..6L).map(start::plusDays)
    }
    val month = YearMonth.from(anchor)
    val first = month.atDay(1).with(DayOfWeek.MONDAY)
    val last = month.atEndOfMonth().with(DayOfWeek.SUNDAY)
    val count = java.time.temporal.ChronoUnit.DAYS.between(first, last)
    return (0L..count).map(first::plusDays)
}
