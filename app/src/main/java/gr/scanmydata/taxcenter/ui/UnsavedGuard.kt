package gr.scanmydata.taxcenter.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * «Δεν το αποθήκευσες» — ο φύλακας των συμπληρωμένων αλλά αναποθήκευτων φορμών.
 *
 * Το πρόβλημα που λύνει είναι συγκεκριμένο: ο λογιστής ανοίγει νέα καρτέλα,
 * βάζει κωδικούς, πατά «Άντληση στοιχείων», η φόρμα γεμίζει με ονοματεπώνυμο,
 * ΔΟΥ, email, κινητό, ΑΜΚΑ — και μετά πατά κάτι στο μενού. Μέχρι τώρα όλα αυτά
 * χάνονταν αθόρυβα, μαζί με τη σύνδεση στο TAXIS που τα έφερε. Το ότι η επόμενη
 * προσπάθεια στοιχίζει άλλη μια σύνδεση στο GSIS (που κλειδώνει σε αλλεπάλληλες
 * αποτυχίες) κάνει τη σιωπηλή απώλεια ακόμη χειρότερη.
 *
 * `object` και όχι κατάσταση οθόνης, για τον ίδιο λόγο με το [TourState]: αυτός
 * που **φεύγει** από τη φόρμα είναι το κέλυφος, όχι η φόρμα. Η φόρμα δηλώνει
 * «έχω αναποθήκευτα και να πώς σώζονται»· το κέλυφος ρωτά πριν την πετάξει.
 *
 * Ο διάλογος ζει στο [AppShell] ώστε να επιβιώνει της πλοήγησης που τον
 * προκάλεσε — αν ζούσε στη φόρμα, θα εξαφανιζόταν μαζί της.
 */
object UnsavedGuard {

    private var owner: Any? = null
    private var probe: (() -> Boolean)? = null
    private var commit: (suspend () -> String)? = null

    /** Η ενέργεια που περιμένει απάντηση. `null` = δεν ρωτάμε τίποτα. */
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set

    /**
     * Δηλώνει ότι η τρέχουσα οθόνη έχει φόρμα με πιθανές αναποθήκευτες αλλαγές.
     *
     * Το [owner] είναι ταυτότητα, όχι διακοσμητικό: όταν το Compose συνθέτει τη
     * νέα οθόνη **πριν** αποδομήσει την παλιά, το `onDispose` της παλιάς θα
     * έσβηνε τη δήλωση της καινούργιας. Με ταυτότητα, σβήνει μόνο τη δική του.
     *
     * @param dirty διαβάζεται τη στιγμή που ο χρήστης πάει να φύγει.
     * @param save επιστρέφει κενό σε επιτυχία, αλλιώς το μήνυμα σφάλματος.
     */
    fun arm(owner: Any, dirty: () -> Boolean, save: suspend () -> String) {
        this.owner = owner
        probe = dirty
        commit = save
    }

    fun disarm(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        probe = null
        commit = null
        pending = null
    }

    /**
     * Εκτελεί την [action] — αφού ρωτήσει, αν υπάρχει κάτι να χαθεί.
     *
     * Κάθε πλοήγηση που φεύγει από την τρέχουσα οθόνη περνά από εδώ. Όταν δεν
     * υπάρχουν αναποθήκευτα, η [action] τρέχει αμέσως και ο χρήστης δεν βλέπει
     * τίποτα — ο φύλακας δεν πρέπει να γίνει τελετουργικό.
     */
    fun guard(action: () -> Unit) {
        if (probe?.invoke() == true) pending = action else action()
    }

    /** «Άκυρο» — μένουμε εκεί που είμαστε. */
    fun cancel() {
        pending = null
    }

    /** Προχωρά χωρίς αποθήκευση· ό,τι είχε η φόρμα χάνεται. */
    fun proceed() {
        val action = pending ?: return
        pending = null
        action()
    }

    /** Αποθηκεύει και μετά προχωρά. Κενό = πέτυχε. */
    suspend fun save(): String = commit?.invoke() ?: ""
}

/**
 * Δηλώνει τη φόρμα της οθόνης στον [UnsavedGuard] όσο αυτή είναι στη σύνθεση.
 *
 * Τα [dirty]/[save] διαβάζονται τη στιγμή της χρήσης, όχι τώρα: οι τιμές της
 * φόρμας αλλάζουν συνέχεια, και ένα «στιγμιότυπο» θα ρωτούσε για περασμένη
 * κατάσταση.
 */
@Composable
fun GuardUnsaved(dirty: () -> Boolean, save: suspend () -> String) {
    val token = remember { Any() }
    DisposableEffect(token) {
        UnsavedGuard.arm(token, dirty, save)
        onDispose { UnsavedGuard.disarm(token) }
    }
}

/**
 * Ο διάλογος. Μπαίνει **μία φορά**, στο κέλυφος.
 *
 * Τρεις επιλογές και όχι δύο: «Αποθήκευση», «Απόρριψη», «Άκυρο». Η «Άκυρο»
 * είναι η σημαντική — ο χρήστης μπορεί να πάτησε κατά λάθος το μενού, και ένας
 * διάλογος που τον αναγκάζει να διαλέξει ανάμεσα στο να αποθηκεύσει κάτι
 * μισοτελειωμένο και στο να το πετάξει είναι χειρότερος από καθόλου διάλογος.
 */
@Composable
fun UnsavedChangesDialog() {
    if (UnsavedGuard.pending == null) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) UnsavedGuard.cancel() },
        title = { Text("Δεν έχεις αποθηκεύσει") },
        text = {
            Column {
                Text(
                    "Η καρτέλα έχει αλλαγές που δεν έχουν γραφτεί. Αν φύγεις τώρα, " +
                        "χάνονται — μαζί με ό,τι ήρθε από την άντληση στοιχείων.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        val problem = UnsavedGuard.save()
                        busy = false
                        if (problem.isBlank()) UnsavedGuard.proceed() else error = problem
                    }
                },
            ) { Text(if (busy) "Αποθήκευση…" else "Αποθήκευση") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = { UnsavedGuard.proceed() },
                ) { Text("Απόρριψη") }
                TextButton(
                    enabled = !busy,
                    onClick = { UnsavedGuard.cancel() },
                ) { Text("Άκυρο") }
            }
        },
    )
}
