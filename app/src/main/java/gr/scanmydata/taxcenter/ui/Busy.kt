package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gr.scanmydata.taxcenter.engine.FetchController
import kotlinx.coroutines.delay

/**
 * Ένα βήμα μιας μακριάς ενέργειας, όπως το βλέπει ο χρήστης στο παράθυρο
 * αναμονής: «Οφειλές ΚΕΑΟ — σε εξέλιξη».
 */
data class BusyStep(val label: String, val state: State, val note: String = "") {
    enum class State { WAITING, RUNNING, DONE, FAILED }
}

/**
 * Το «περίμενε» της εφαρμογής.
 *
 * Υπάρχει επειδή οι μακριές ενέργειες εδώ δεν είναι στιγμιαίες και δεν είναι
 * ακίνδυνες: μια σύνδεση στο GSIS κρατά δεκάδες δευτερόλεπτα, και ένας χρήστης
 * που δεν βλέπει τίποτα να συμβαίνει ξαναπατά το κουμπί — δηλαδή ανοίγει
 * δεύτερη συνεδρία, δηλαδή κλείδωμα `OAM-6`. Η επικάλυψη **μπλοκάρει τα
 * πατήματα** ακριβώς γι' αυτό.
 *
 * ## Παράθυρο, όχι γραμμή κάτω από το κουμπί
 *
 * Ήταν μια γραμμή κειμένου κάτω από τα κουμπιά, ή μια κάρτα μέσα στη σελίδα.
 * Και τα δύο χάνονταν: κάτω από το πληκτρολόγιο, έξω από την οθόνη όταν η
 * σελίδα είχε κυλήσει, ή πίσω από την πάνω μπάρα. Τώρα είναι **δικό του
 * παράθυρο** πάνω από όλη την εφαρμογή — μπάρα και μενού μαζί — και λέει
 * τέσσερα πράγματα: **τι** γίνεται ([title]), **σε ποιο σημείο** ([text]),
 * **πόσο** απομένει ([progress] ή [steps]) και **πόση ώρα** κρατά ήδη. Το
 * τελευταίο δεν είναι στολίδι: «0:48» λέει ότι η εφαρμογή δεν κόλλησε.
 *
 * Το «πίσω» του τηλεφώνου δεν το κλείνει: μια μισοτελειωμένη αποστολή ή
 * αποθήκευση είναι χειρότερη από μια που περίμενες. Κλείνει μόνο με
 * [onCancel] («Διακοπή») ή [onHide] («Συνέχεια στο παρασκήνιο»), όπου η ενέργεια
 * τα αντέχει.
 *
 * Εμφανίζεται μετά από ένα κλάσμα του δευτερολέπτου, ώστε μια ενέργεια που
 * τελειώνει αμέσως να μην αναβοσβήνει την οθόνη.
 *
 * Το παράθυρο κληρονομεί το `FLAG_SECURE` της εφαρμογής (`SecureFlagPolicy.Inherit`):
 * ό,τι γράφει δεν φαίνεται σε στιγμιότυπα όταν η ρύθμιση είναι ενεργή.
 */
@Composable
fun BusyOverlay(
    visible: Boolean,
    text: String,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
    detail: String = "",
    title: String = "",
    /** `0..1` όταν η ενέργεια ξέρει πόσο απέχει από το τέλος· αλλιώς αόριστη μπάρα. */
    progress: Float? = null,
    steps: List<BusyStep> = emptyList(),
    onCancel: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    hideLabel: String = "Στο παρασκήνιο",
) {
    var shown by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(visible) {
        if (!visible) {
            shown = false
            return@LaunchedEffect
        }
        startedAt = System.currentTimeMillis()
        elapsed = 0L
        delay(SHOW_AFTER_MS)
        shown = true
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(1_000)
        }
    }
    if (!visible || !shown) return

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Card(
            Modifier.widthIn(min = 280.dp, max = 400.dp).padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title.ifBlank { "Περίμενε" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Διάρκεια " + clock(elapsed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (text.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }

                if (steps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Column(
                        Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                    ) {
                        steps.forEach { StepRow(it) }
                    }
                }

                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }

                if (onCancel != null || onHide != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (onHide != null) {
                            OutlinedButton(onClick = onHide, modifier = Modifier.weight(1f)) {
                                Text(hideLabel, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                        if (onCancel != null) {
                            OutlinedButton(
                                onClick = onCancel,
                                tone = Tone.DELETE,
                                modifier = Modifier.weight(1f),
                            ) { Text("Διακοπή", maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: BusyStep) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (step.state) {
                BusyStep.State.RUNNING ->
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                BusyStep.State.DONE -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "ολοκληρώθηκε",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                BusyStep.State.FAILED -> Icon(
                    Icons.Filled.Close,
                    contentDescription = "απέτυχε",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                BusyStep.State.WAITING -> Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "σε αναμονή",
                    tint = dim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (step.state == BusyStep.State.RUNNING) FontWeight.SemiBold else null,
                color = if (step.state == BusyStep.State.WAITING) dim else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.note.isNotBlank()) {
                Text(
                    step.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.state == BusyStep.State.FAILED) MaterialTheme.colorScheme.error else dim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Το παράθυρο αναμονής για ενέργειες που περνούν από τις πύλες (GSIS, ΚΕΑΟ):
 * τα βήματα και η πρόοδος έρχονται **από την ίδια την παρτίδα** του
 * [FetchController], ώστε ο χρήστης να βλέπει ποια πύλη διαβάζεται τώρα και
 * ποια τελείωσε — όχι ένα γενικό «σύνδεση…» για ένα λεπτό.
 *
 * Όταν η σελίδα ζητήσει άνθρωπο (κωδικό μιας χρήσης ή CAPTCHA), το παράθυρο
 * **κλείνει** και δείχνει τον δρόμο προς την οθόνη «Λήψη», όπου φαίνεται η
 * σελίδα: τίποτα δεν παρακάμπτεται, και τίποτα δεν πρέπει να την κρύβει.
 *
 * @param cancellable η «Διακοπή» σταματά την παρτίδα — σωστό για ό,τι ξεκίνησε
 *   ο χρήστης από αυτή την οθόνη, λάθος για μια αυτόματη λήψη που απλώς έτυχε
 */
@Composable
fun FetchBusyOverlay(
    container: AppContainer,
    visible: Boolean,
    title: String,
    text: String,
    detail: String = "",
    cancellable: Boolean = true,
    onHide: (() -> Unit)? = null,
    /** Αντί για τη διακοπή της παρτίδας — π.χ. ο έλεγχος σταματά και στο WorkManager. */
    onCancel: (() -> Unit)? = null,
) {
    val state by container.fetch.state.collectAsState()
    val stop: () -> Unit = onCancel ?: { container.fetch.cancel() }
    if (visible && state.browserActive) {
        // Δεν μπλοκάρει: ο χρήστης πρέπει να μπορεί να πάει στη σελίδα.
        BusyOverlay(
            visible = true,
            title = "Η πύλη ζητά εσένα",
            text = "Η σελίδα ζητά κωδικό μιας χρήσης ή CAPTCHA. Συμπλήρωσέ το στην οθόνη " +
                "«Λήψη» — η εφαρμογή δεν τα παρακάμπτει.",
            onHide = { Launch.request(Destination.Fetch.route) },
            hideLabel = "Άνοιγμα σελίδας",
            onCancel = if (cancellable) stop else null,
        )
        return
    }
    val items = if (state.running || visible) state.items else emptyList()
    val steps = items.map { item ->
        BusyStep(
            label = buildString {
                append(item.configTitle)
                // Σε παρτίδα πολλών πελατών το όνομα λέει ποιος είναι τώρα.
                if (items.distinctBy { it.clientId }.size > 1) append(" — ").append(item.clientName)
            },
            state = when (item.status) {
                FetchController.Status.PENDING -> BusyStep.State.WAITING
                FetchController.Status.RUNNING -> BusyStep.State.RUNNING
                FetchController.Status.FAILED -> BusyStep.State.FAILED
                FetchController.Status.CANCELLED -> BusyStep.State.FAILED
                else -> BusyStep.State.DONE
            },
            note = if (item.status == FetchController.Status.RUNNING || item.status == FetchController.Status.FAILED) {
                item.detail
            } else {
                ""
            },
        )
    }
    BusyOverlay(
        visible = visible,
        title = title,
        text = text,
        detail = detail.ifBlank {
            "Οι συνδέσεις στις πύλες γίνονται μία-μία και κρατούν έως ένα λεπτό η καθεμία. " +
                "Μην ξαναπατήσεις το κουμπί."
        },
        progress = if (state.running && items.isNotEmpty()) state.done.toFloat() / items.size else null,
        steps = steps,
        onCancel = if (cancellable && (state.running || onCancel != null)) stop else null,
        onHide = onHide,
    )
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private const val SHOW_AFTER_MS = 250L
