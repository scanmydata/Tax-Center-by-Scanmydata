package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    private var names: (() -> List<String>)? = null

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
     * @param changed ονόματα πεδίων που έχουν αλλάξει. Ο διάλογος τα δείχνει
     *   αντί για ένα αόριστο «έχεις αλλαγές»: ο χρήστης που μόλις πάτησε
     *   «Άντληση στοιχείων» θέλει να ξέρει αν αυτό που θα χαθεί είναι το
     *   ονοματεπώνυμο και το email ή ένα γράμμα που πάτησε κατά λάθος.
     */
    fun arm(
        owner: Any,
        dirty: () -> Boolean,
        save: suspend () -> String,
        changed: () -> List<String> = { emptyList() },
    ) {
        this.owner = owner
        probe = dirty
        commit = save
        names = changed
    }

    fun disarm(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        probe = null
        commit = null
        names = null
        pending = null
    }

    /** Τι θα χαθεί, με τα ονόματα που βλέπει ο χρήστης στη φόρμα. */
    fun changed(): List<String> = names?.invoke().orEmpty()

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
fun GuardUnsaved(
    dirty: () -> Boolean,
    save: suspend () -> String,
    changed: () -> List<String> = { emptyList() },
) {
    val token = remember { Any() }
    DisposableEffect(token) {
        UnsavedGuard.arm(token, dirty, save, changed)
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
 *
 * ## Γιατί δεν είναι `AlertDialog`
 *
 * Το `AlertDialog` του Material δέχεται **δύο** κουμπιά. Με τρία, το τρίτο
 * στριμώχνεται δίπλα στο δεύτερο και σε στενή οθόνη κόβεται· χειρότερα, η
 * «Απόρριψη» κάθεται δίπλα στην «Αποθήκευση» με το ίδιο ακριβώς βάρος, τη
 * στιγμή που η μία γράφει και η άλλη πετάει.
 *
 * Εδώ τα κουμπιά είναι το ένα κάτω από το άλλο, με τη σειρά που τα θέλει ο
 * χρήστης: πρώτο και γεμάτο αυτό που κρατά τη δουλειά του, τελευταίο και
 * διακριτικό αυτό που τη σβήνει.
 */
@Composable
fun UnsavedChangesDialog() {
    if (UnsavedGuard.pending == null) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // Διαβάζεται μία φορά, όταν ανοίγει ο διάλογος: η φόρμα από κάτω μπορεί να
    // έχει ήδη αποδομηθεί όσο αυτός είναι ανοιχτός.
    val changed = remember { UnsavedGuard.changed() }

    Dialog(onDismissRequest = { if (!busy) UnsavedGuard.cancel() }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Να αποθηκευτεί η καρτέλα;",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Η καρτέλα έχει αλλαγές που δεν έχουν γραφτεί. Αν φύγεις τώρα " +
                        "χάνονται — μαζί με ό,τι ήρθε από την άντληση στοιχείων, που " +
                        "στοίχισε μια σύνδεση στο TAXISnet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (changed.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                "ΤΙ ΘΑ ΧΑΘΕΙ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                summarise(changed),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
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
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (busy) "Αποθήκευση…" else "Αποθήκευση και συνέχεια")
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { UnsavedGuard.cancel() },
                ) { Text("Επιστροφή στην καρτέλα") }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { UnsavedGuard.proceed() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Απόρριψη αλλαγών") }
            }
        }
    }
}

/**
 * «Επωνυμία · ΔΟΥ · Email» — και, όταν είναι πολλά, «…και 4 ακόμη».
 *
 * Το όριο δεν είναι αισθητικό: μετά την άντληση αλλάζουν σχεδόν όλα τα πεδία,
 * και μια λίστα δώδεκα ονομάτων σε διάλογο δεν διαβάζεται — μετριέται.
 */
private fun summarise(changed: List<String>): String {
    val shown = changed.take(5)
    val rest = changed.size - shown.size
    return shown.joinToString(" · ") + if (rest > 0) " · και $rest ακόμη" else ""
}
