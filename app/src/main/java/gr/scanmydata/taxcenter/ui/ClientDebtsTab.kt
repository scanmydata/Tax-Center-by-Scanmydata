package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.Normalize
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.debts.DebtMessage
import gr.scanmydata.taxcenter.debts.Debts
import gr.scanmydata.taxcenter.debts.DebtsRefresh
import gr.scanmydata.taxcenter.debts.DebtsStore
import gr.scanmydata.taxcenter.mail.MailTemplateStore
import gr.scanmydata.taxcenter.sched.DebtWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Η καρτέλα **«Οφειλές»** του πελάτη: τι χρωστά σε ΑΑΔΕ και ΚΕΑΟ, τώρα.
 *
 * Δεν είναι άλλη μορφή των PDF. Τα έντυπα είναι για να φύγουν στον πελάτη· αυτή
 * η οθόνη είναι για τον λογιστή που έχει τον πελάτη στο τηλέφωνο και θέλει να
 * απαντήσει «πόσο είναι η δόση και πότε λήγει» χωρίς να ανοίξει πέντε αρχεία.
 *
 * Τρία πράγματα γίνονται από εδώ:
 *
 *  * **Ενημέρωση τώρα** — σύνδεση στις πύλες και ανανέωση των ποσών, χωρίς λήψη
 *    εντύπων. Δευτερόλεπτα για την ΑΑΔΕ.
 *  * **Αυτόματη ενημέρωση** — ο πελάτης μπαίνει στο πρόγραμμα των Ρυθμίσεων.
 *  * **Μήνυμα** — SMS ή Viber με την ταυτότητα, το ποσό και τη λήξη της δόσης.
 *
 * Η οθόνη δείχνει πάντα **πότε** ήταν η τελευταία ενημέρωση: ένα ποσό οφειλής
 * χωρίς ημερομηνία είναι χειρότερο από κανένα ποσό.
 *
 * ## ΑΑΔΕ και ΚΕΑΟ, χωριστά
 *
 * Οι δύο πύλες έχουν δική τους όψη, με δικό της σύνολο, δική της ώρα και δική
 * της ενημέρωση. Ήταν μία λίστα με τέσσερις ομάδες, και ο πελάτης που ρωτούσε
 * «τι χρωστάω στην εφορία» έπαιρνε απάντηση ανακατεμένη με τον ΕΦΚΑ. Η επιλογή
 * οφειλών για μήνυμα **διασχίζει** τις δύο όψεις: ένα μήνυμα μπορεί να έχει
 * και τα δύο.
 */
@Composable
internal fun ClientDebtsTab(
    container: AppContainer,
    client: ClientEntity?,
    onFetch: () -> Unit,
) {
    if (client == null) {
        Text("Φόρτωση…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val fetchState by container.fetch.state.collectAsState()
    val settings = container.settings

    var reload by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val picked = remember(client.id) { mutableStateListOf<String>() }
    var source by remember(client.id) { mutableStateOf(Debts.Source.AADE) }
    var composing by remember { mutableStateOf(false) }
    var watched by remember(client.id) { mutableStateOf(client.id in settings.debtWatchClients) }
    val today = remember { LocalDate.now(AthensDates.ZONE) }

    val snapshot by produceState<Debts.Snapshot?>(null, client.afm, reload) {
        value = withContext(Dispatchers.IO) { DebtsStore.load(context.filesDir, client.afm) }
    }

    // Κάθε λήψη που τελειώνει μπορεί να έχει αλλάξει την εικόνα — και αυτή που
    // ξεκίνησε από εδώ, και μια κανονική λήψη εντύπων, και η αυτόματη.
    LaunchedEffect(fetchState.running) {
        if (!fetchState.running) reload++
    }

    val busy = refreshing || fetchState.running
    val frequency = DebtWatch.Frequency.of(settings.debtWatchFrequency)

    // `null` = και οι δύο πύλες.
    fun refresh(only: Debts.Source?) {
        scope.launch {
            refreshing = true
            status = if (only == null) "Σύνδεση στις πύλες…" else "Σύνδεση: ${only.label}…"
            try {
                val plans = withContext(Dispatchers.IO) {
                    DebtsRefresh.plans(container.repository, client, only)
                }
                val items = DebtsRefresh.run(container.fetch, plans)
                status = DebtsRefresh.describe(items, only).ifBlank {
                    if (only == null) "Ενημερώθηκαν ΑΑΔΕ και ΚΕΑΟ." else "Ενημερώθηκε: ${only.label}."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Απέτυχε: ${e.message}"
            } finally {
                refreshing = false
                reload++
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        // Έξω από τη λίστα: η πύλη που βλέπεις μένει στην οθόνη όσο κυλάς.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Debts.Source.entries.forEach { option ->
                val known = snapshot?.at(option) ?: 0L
                FilterChip(
                    selected = option == source,
                    onClick = { source = option },
                    label = {
                        Text(
                            // Χωρίς λήψη δεν γράφεται ποσό: το «0,00 €» θα ήταν ψέμα.
                            if (known == 0L) option.label
                            else option.label + " · " + snapshot?.total(option).orEmpty() + " €",
                        )
                    },
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            item {
                Spacer(Modifier.height(6.dp))
                SummaryCard(
                    snapshot = snapshot,
                    source = source,
                    busy = busy,
                    status = status,
                    onRefresh = { refresh(source) },
                    onRefreshAll = { refresh(null) },
                    onFetch = onFetch,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Αυτόματη ενημέρωση", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                !watched -> "Η καρτέλα ενημερώνεται μόνο όταν το ζητήσεις."
                                frequency == DebtWatch.Frequency.OFF ->
                                    "Ο πελάτης είναι στη λίστα, αλλά το πρόγραμμα είναι κλειστό — " +
                                        "άνοιξέ το από Ρυθμίσεις → Οφειλές."
                                else ->
                                    frequency.label.lowercase() + ", περίπου στις " +
                                        settings.debtWatchHour.toString().padStart(2, '0') + ":00."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Switch(
                        checked = watched,
                        onCheckedChange = { on ->
                            watched = on
                            settings.debtWatchClients =
                                if (on) settings.debtWatchClients + client.id
                                else settings.debtWatchClients - client.id
                            DebtWatch.apply(context, settings)
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            val current = snapshot
            if (current != null && source == Debts.Source.KEAO && current.keaoPartial.isNotEmpty()) {
                item {
                    Text(
                        "Η τελευταία λήψη ΚΕΑΟ έγινε μόνο για τα μητρώα " +
                            current.keaoPartial.joinToString(", ") + ". Οι υπόλοιποι φορείς " +
                            "δεν φαίνονται εδώ — πάτα «Ενημέρωση ΚΕΑΟ» για πλήρη εικόνα.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            if (current != null && current.inSource(source).isNotEmpty()) {
                for (group in Debts.Group.entries) {
                    if (group.source != source) continue
                    val lines = current.inGroup(group)
                    if (lines.isEmpty()) continue
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group.short,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                current.total(group) + " €",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    items(lines) { line ->
                        DebtCard(
                            line = line,
                            today = today,
                            checked = line.id in picked,
                            onToggle = {
                                if (line.id in picked) picked.remove(line.id) else picked.add(line.id)
                            },
                            onCopy = {
                                clipboard.setText(AnnotatedString(line.code))
                                status = "Η ταυτότητα αντιγράφηκε."
                            },
                        )
                    }
                }
                item {
                    Text(
                        "Πάτησε μία ή περισσότερες οφειλές για να στείλεις μήνυμα στον πελάτη. " +
                            "Η επιλογή μένει όταν αλλάζεις πύλη, οπότε ένα μήνυμα μπορεί να " +
                            "έχει οφειλές και από τις δύο.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        if (picked.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { composing = true }) {
                    Text(if (picked.size == 1) "Μήνυμα οφειλής" else "Μήνυμα για ${picked.size} οφειλές")
                }
                OutlinedButton(onClick = { picked.clear() }) { Text("Άκυρο") }
            }
        }
    }

    val chosen = snapshot?.lines.orEmpty().filter { it.id in picked }
    if (composing && chosen.isNotEmpty()) {
        DebtMessageDialog(
            container = container,
            client = client,
            lines = chosen,
            today = today,
            scope = scope,
            onDismiss = { composing = false },
            onSent = { message ->
                composing = false
                picked.clear()
                status = message
            },
        )
    }
}

/**
 * Η σύνοψη της πύλης που βλέπεις: πόσα, και **πότε** διαβάστηκαν.
 *
 * Η ώρα ενημέρωσης δεν είναι λεπτομέρεια: οι οφειλές αλλάζουν με κάθε καταβολή
 * και με τις προσαυξήσεις, και ένα ποσό τριών εβδομάδων δεν πρέπει να
 * διαβάζεται σαν σημερινό.
 */
@Composable
private fun SummaryCard(
    snapshot: Debts.Snapshot?,
    source: Debts.Source,
    busy: Boolean,
    status: String,
    onRefresh: () -> Unit,
    onRefreshAll: () -> Unit,
    onFetch: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            when {
                snapshot == null -> Text("Φόρτωση…", style = MaterialTheme.typography.bodyMedium)

                snapshot.at(source) == 0L -> Text(
                    when (source) {
                        Debts.Source.AADE ->
                            "Δεν έχουν διαβαστεί ακόμη οφειλές από την ΑΑΔΕ για αυτόν τον " +
                                "πελάτη. Πάτα «Ενημέρωση ΑΑΔΕ»."
                        Debts.Source.KEAO ->
                            "Δεν έχουν διαβαστεί ακόμη οφειλές από το ΚΕΑΟ για αυτόν τον " +
                                "πελάτη. Πάτα «Ενημέρωση ΚΕΑΟ» — θέλει ΑΜΚΑ στην καρτέλα, " +
                                "άρα γίνεται μόνο για φυσικά πρόσωπα."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                source == Debts.Source.AADE -> {
                    SourceSummary(
                        name = "ΑΑΔΕ",
                        at = snapshot.aadeAt,
                        rows = buildList {
                            val open = snapshot.inGroup(Debts.Group.AADE_OPEN)
                            val arranged = snapshot.inGroup(Debts.Group.AADE_ARRANGED)
                            if (open.isNotEmpty()) {
                                add(
                                    "Εκτός ρύθμισης: " + snapshot.total(Debts.Group.AADE_OPEN) + " €" +
                                        " · ληξιπρόθεσμα " + snapshot.aadeOverdue + " €",
                                )
                            }
                            if (arranged.isNotEmpty()) {
                                add("Σε ρύθμιση: " + snapshot.total(Debts.Group.AADE_ARRANGED) + " €")
                            }
                            if (open.isEmpty() && arranged.isEmpty()) add("Καμία οφειλή.")
                        },
                    )
                }

                else -> {
                    SourceSummary(
                        name = "ΚΕΑΟ",
                        at = snapshot.keaoAt,
                        rows = buildList {
                            val carriers = snapshot.inGroup(Debts.Group.KEAO_CARRIER)
                            if (carriers.isEmpty()) {
                                add("Καμία οφειλή.")
                            } else {
                                add("Υπόλοιπο: " + snapshot.total(Debts.Group.KEAO_CARRIER) + " €")
                                val regulations = snapshot.inGroup(Debts.Group.KEAO_ARRANGED).size
                                if (regulations > 0) add("Ενεργές ρυθμίσεις: $regulations")
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !busy, onClick = onRefresh) {
                    Text(if (busy) "Ενημέρωση…" else "Ενημέρωση " + source.label)
                }
                OutlinedButton(onClick = onFetch) { Text("Λήψη εντύπων") }
            }
            TextButton(enabled = !busy, onClick = onRefreshAll) {
                Text("Ενημέρωση και των δύο (ΑΑΔΕ και ΚΕΑΟ)")
            }
            Text(
                "Η ενημέρωση διαβάζει ποσά, δόσεις και ταυτότητες — χωρίς να κατεβάσει PDF. " +
                    "Τα έντυπα κατεβαίνουν από τη «Λήψη εντύπων».",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceSummary(name: String, at: Long, rows: List<String>) {
    Row {
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            if (at == 0L) "δεν έχει ληφθεί" else "ενημέρωση " + AthensDates.stamp(at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
    // Χωρίς λήψη δεν ξέρουμε αν υπάρχει οφειλή· το «καμία οφειλή» θα ήταν ψέμα.
    if (at != 0L) {
        rows.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Μία οφειλή: τι είναι, πόσο, η δόση που τρέχει, και ο κωδικός πληρωμής της. */
@Composable
private fun DebtCard(
    line: Debts.Line,
    today: LocalDate,
    checked: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onToggle),
        colors = if (checked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Row {
                    Text(
                        line.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    if (line.total.isNotBlank()) {
                        Text(line.total + " €", style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (line.detail.isNotBlank()) {
                    Text(line.detail, style = MaterialTheme.typography.bodySmall, color = dim)
                }

                val next = line.next
                if (next != null) {
                    val expired = next.dueDate?.isBefore(today) == true
                    Text(
                        if (expired) "Δόση ${next.amount} € · έληξε ${next.due}"
                        else "Επόμενη δόση ${next.amount} € · λήξη ${next.due}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                val late = line.late(today)
                if (late.size > 1) {
                    Text(
                        "${late.size} ληξιπρόθεσμες δόσεις · ${line.lateTotal(today)} €",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (line.instalments > 0) {
                    Text(
                        "Απομένουν ${line.unpaid.size} από ${line.instalments} δόσεις",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }

                if (line.code.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            line.codeLabel + ": " + line.code,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onCopy) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Αντιγραφή ταυτότητας",
                                modifier = Modifier.size(18.dp),
                                tint = dim,
                            )
                        }
                    }
                } else {
                    Text(
                        "Η πύλη δεν έδωσε ταυτότητα πληρωμής για αυτή τη γραμμή.",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ μήνυμα

private enum class DebtChannel(val label: String) {
    SMS("SMS"),
    VIBER("Viber"),
}

/**
 * Το μήνυμα οφειλής, πριν φύγει: κανάλι, παραλήπτης, και το **ίδιο το κείμενο**.
 *
 * Το κείμενο είναι επεξεργάσιμο επίτηδες. Το πρότυπο καλύπτει τη συνηθισμένη
 * περίπτωση· η πραγματική συχνά θέλει μια φράση ακόμη («μίλησα με την εφορία,
 * πλήρωσε ως την Παρασκευή»), και ένα μήνυμα που δεν διορθώνεται καταλήγει να
 * γράφεται από την αρχή στο χέρι.
 *
 * Ο αριθμός επίσης αλλάζει — για αυτή τη μία φορά, χωρίς να αποθηκευτεί. Ο
 * πελάτης που ζητά «στείλ' το στη γυναίκα μου» δεν σημαίνει ότι αλλάζει το
 * κινητό της καρτέλας του.
 */
@Composable
private fun DebtMessageDialog(
    container: AppContainer,
    client: ClientEntity,
    lines: List<Debts.Line>,
    today: LocalDate,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit,
) {
    val context = LocalContext.current
    val template = remember { MailTemplateStore(context).debt }
    val own = remember(client.id) { Normalize.mobile(client.mobile) }
    val viberReady = remember { container.viber.installed() }

    var channel by remember { mutableStateOf(DebtChannel.SMS) }
    var to by remember(client.id) { mutableStateOf(own) }
    var text by remember(lines) {
        mutableStateOf(
            DebtMessage.text(
                clientName = client.displayName,
                afm = client.afm,
                lines = lines,
                template = template,
                office = container.settings.officeName,
                today = today,
            ),
        )
    }
    var sending by remember { mutableStateOf(false) }

    val mobile = Normalize.mobile(to)
    val blocked = when {
        mobile.isBlank() && to.isBlank() ->
            "Ο πελάτης δεν έχει κινητό στην καρτέλα — γράψε ένα, ή φέρ' το με την άντληση στοιχείων."
        mobile.isBlank() -> "Χρειάζεται ελληνικό κινητό, δέκα ψηφία που αρχίζουν από 69."
        channel == DebtChannel.VIBER && !viberReady -> "Το Viber δεν είναι εγκατεστημένο σε αυτή τη συσκευή."
        text.isBlank() -> "Το μήνυμα είναι κενό."
        else -> ""
    }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lines.size == 1) "Μήνυμα οφειλής" else "Μήνυμα για ${lines.size} οφειλές") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebtChannel.entries.forEach { option ->
                        FilterChip(
                            selected = option == channel,
                            onClick = { channel = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = to,
                    onValueChange = { raw -> to = raw.filter { it.isDigit() || it == '+' || it == ' ' } },
                    label = { Text("Κινητό παραλήπτη") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mobile.isNotBlank() && mobile != own) {
                    Text(
                        "Ισχύει μόνο για αυτό το μήνυμα — δεν αποθηκεύεται στην καρτέλα.",
                        style = MaterialTheme.typography.labelSmall,
                        color = dim,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Κείμενο") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (channel) {
                        DebtChannel.SMS -> {
                            val cost = DebtMessage.sms(text)
                            "${cost.chars} χαρακτήρες · περίπου ${cost.parts} " +
                                (if (cost.parts == 1) "τμήμα SMS" else "τμήματα SMS") +
                                (if (cost.unicode) " (με ελληνικά: 70 χαρακτήρες το τμήμα)" else "") +
                                ". Θα ανοίξουν τα Μηνύματα με τον αριθμό και το κείμενο έτοιμα· " +
                                "η αποστολή γίνεται από εκεί."
                        }
                        DebtChannel.VIBER ->
                            "Θα ανοίξει το Viber με το κείμενο, στην οθόνη «κοινή χρήση με…». " +
                                "Ο αριθμός αντιγράφεται στο πρόχειρο: κάνε επικόλληση στην " +
                                "αναζήτηση επαφής."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                )
                if (blocked.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        blocked,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = blocked.isBlank() && !sending,
                onClick = {
                    sending = true
                    val via = channel
                    val body = text
                    scope.launch {
                        val subject = DebtMessage.subject(client.displayName, client.afm, template)
                        val items = DebtMessage.items(lines)
                        // Χωρίς withContext(IO): εδώ υπάρχει `startActivity`, όχι
                        // δίκτυο. Οι εγγραφές Room αλλάζουν νήμα μόνες τους.
                        val send = runCatching {
                            when (via) {
                                DebtChannel.SMS -> container.sms.send(client, mobile, body, subject, items)
                                DebtChannel.VIBER -> container.viber.sendText(client, mobile, body, subject, items)
                            }
                        }
                        val entry = send.getOrNull()
                        onSent(
                            when {
                                entry == null -> "Απέτυχε: ${send.exceptionOrNull()?.message}"
                                entry.failed -> "Απέτυχε: ${entry.error}"
                                via == DebtChannel.SMS ->
                                    "Το μήνυμα είναι έτοιμο στα Μηνύματα — η αποστολή γίνεται από εκεί."
                                else ->
                                    "Παραδόθηκε στο Viber. Ο αριθμός είναι στο πρόχειρο — " +
                                        "επικόλλησέ τον στην αναζήτηση επαφής."
                            },
                        )
                    }
                },
            ) {
                Text(if (channel == DebtChannel.SMS) "Άνοιγμα στα Μηνύματα" else "Άνοιγμα στο Viber")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}
