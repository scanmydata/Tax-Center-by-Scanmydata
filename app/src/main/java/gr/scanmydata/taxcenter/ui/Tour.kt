package gr.scanmydata.taxcenter.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.R

/**
 * Η κατάσταση της ξενάγησης, έξω από τη σύνθεση.
 *
 * `object` και όχι state μέσα σε composable, για τον ίδιο λόγο με το `AppLock`:
 * η ξενάγηση **διασχίζει οθόνες**. Αν ζούσε μέσα σε μια οθόνη, θα χανόταν στην
 * πρώτη πλοήγηση — δηλαδή ακριβώς εκεί που αρχίζει να είναι χρήσιμη.
 */
object TourState {
    var active by mutableStateOf(false)
        private set

    var step by mutableIntStateOf(0)
        private set

    fun start() {
        step = 0
        active = true
    }

    fun next() {
        if (step < TOUR.lastIndex) step++ else finish()
    }

    fun back() {
        if (step > 0) step--
    }

    fun finish() {
        active = false
        step = 0
    }
}

/**
 * Ένα βήμα της ξενάγησης.
 *
 * @param destination πού πάει το κουμπί «Πάμε εκεί». `null` = κείμενο μόνο.
 * @param done πότε το βήμα θεωρείται ολοκληρωμένο από πραγματική ενέργεια του
 *   χρήστη — όχι από πάτημα «Επόμενο». Όταν αληθεύει, η κάρτα το λέει και
 *   προτείνει το επόμενο βήμα.
 */
data class TourStep(
    val title: String,
    val body: String,
    @DrawableRes val icon: Int,
    val destination: Destination? = null,
    val doneLabel: String = "",
    val done: (TourFacts) -> Boolean = { false },
)

/** Τα λίγα δεδομένα που κρίνουν αν ένα βήμα έγινε στ' αλήθεια. */
data class TourFacts(
    val googleConnected: Boolean = false,
    val clients: Int = 0,
    val documents: Int = 0,
    val currentRoute: String? = null,
)

val TOUR: List<TourStep> = listOf(

    TourStep(
        title = "Καλώς ήρθες",
        body = "Η εφαρμογή κατεβάζει τα φορολογικά και ασφαλιστικά έντυπα των πελατών σου " +
            "και τα στέλνει με το Gmail σου. Όλα γίνονται στη συσκευή — δεν υπάρχει " +
            "διακομιστής μας και κανένα δεδομένο πελάτη δεν φεύγει προς εμάς.\n\n" +
            "Θα σε πάω από τα τέσσερα βήματα που χρειάζονται για την πρώτη σου λήψη. " +
            "Μπορείς να συνεχίσεις να δουλεύεις κανονικά όσο τρέχει.",
        icon = R.drawable.ic_menu_help,
    ),

    TourStep(
        title = "1. Σύνδεσε τον λογαριασμό Google",
        body = "Χωρίς αυτόν δεν φεύγει κανένα email. Ζητούνται μόνο δικαίωμα **αποστολής** " +
            "και πρόσβαση στα αρχεία που δημιουργεί η ίδια η εφαρμογή — το " +
            "γραμματοκιβώτιό σου δεν διαβάζεται ποτέ.\n\n" +
            "Στην ίδια οθόνη βάλε και το όνομα του γραφείου σου: μπαίνει στην υπογραφή " +
            "κάθε μηνύματος.",
        icon = R.drawable.ic_menu_settings,
        destination = Destination.SettingsScreen,
        doneLabel = "Ο λογαριασμός συνδέθηκε.",
        done = { it.googleConnected },
    ),

    TourStep(
        title = "2. Καταχώρησε τον πρώτο πελάτη",
        body = "Βάλε **πρώτα** τους κωδικούς TAXISnet του πελάτη και πάτα «Άντληση " +
            "στοιχείων». Ονοματεπώνυμο, ΑΦΜ, ΔΟΥ, είδος υπόχρεου και email έρχονται " +
            "μόνα τους από το Μητρώο· αν είναι ιδιώτης ή ατομική, έρχεται και ο ΑΜΚΑ.\n\n" +
            "Έχεις πολλούς πελάτες; Η καρτέλα «Από Excel» τους εισάγει όλους μαζί — με " +
            "προεπισκόπηση πριν γραφτεί οτιδήποτε.",
        icon = R.drawable.ic_menu_client_new,
        destination = Destination.NewClient,
        doneLabel = "Έχεις πελάτες καταχωρημένους.",
        done = { it.clients > 0 },
    ),

    TourStep(
        title = "3. Κατέβασε έντυπα",
        body = "«Πρόσθεσε έντυπο» και διάλεξε — τα έντυπα είναι χωρισμένα σε ομάδες. " +
            "**Κάθε επιλογή κρατά δικά της έτη**: μπορείς να ζητήσεις Ε1 του 2025 και " +
            "Ε9 του 2027 μαζί.\n\n" +
            "Διάλεξε πελάτες και πάτα Έναρξη. Ο διακόπτης «Αποστολή με email μόλις " +
            "κατέβουν» κάνει λήψη και αποστολή σε ένα βήμα.",
        icon = R.drawable.ic_menu_fetch,
        destination = Destination.Fetch,
        doneLabel = "Έχεις κατεβάσει έντυπα.",
        done = { it.documents > 0 },
    ),

    TourStep(
        title = "4. Στείλε τα",
        body = "Στα «Έγγραφα» βλέπεις ό,τι κατέβηκε, ανά πελάτη. Πάτημα σε έντυπο το " +
            "ανοίγει, παρατεταμένο πάτημα το επιλέγει — και μετά στέλνεις ή σβήνεις " +
            "όσα διάλεξες.\n\n" +
            "Κάθε μαζική αποστολή περνά από οθόνη που δείχνει **ονομαστικά** τους " +
            "παραλήπτες. Πάντα ένα email ανά πελάτη· ποτέ κοινοποίηση.",
        icon = R.drawable.ic_menu_documents,
        destination = Destination.Documents,
    ),

    TourStep(
        title = "Ασφάλεια — τι να ξέρεις",
        body = "Η βάση είναι κρυπτογραφημένη ολόκληρη και κάθε κωδικός ξεχωριστά, με " +
            "κλειδί που δεν φεύγει από τη συσκευή. Τα στιγμιότυπα οθόνης είναι " +
            "μπλοκαρισμένα, και από την επόμενη εκκίνηση η εφαρμογή θα ζητά " +
            "βιομετρικά.\n\n" +
            "Κωδικοί μιας χρήσης και CAPTCHA **δεν παρακάμπτονται ποτέ**: όπου " +
            "ζητούνται, εμφανίζεται η σελίδα και τα συμπληρώνεις εσύ.",
        icon = R.drawable.ic_menu_security,
    ),

    TourStep(
        title = "Τελειώσαμε",
        body = "Ολόκληρο το εγχειρίδιο είναι στις «Οδηγίες χρήσης» και δουλεύει χωρίς " +
            "δίκτυο. Από εκεί μπορείς να ξαναρχίσεις αυτή την ξενάγηση όποτε θέλεις.",
        icon = R.drawable.ic_menu_help,
        destination = Destination.Help,
    ),
)

/**
 * Η μπάρα της ξενάγησης: κάθεται στο κάτω μέρος **πάνω από κάθε οθόνη** και δεν
 * εμποδίζει τη δουλειά.
 *
 * Δεν είναι επικάλυψη με βελάκια πάνω στα κουμπιά: τέτοιες ξεναγήσεις σπάνε σε
 * κάθε αλλαγή διάταξης, δεν διαβάζονται σε τηλέφωνο που κρατάς με το ένα χέρι,
 * και κλειδώνουν τον χρήστη σε μια σειρά που ίσως δεν του ταιριάζει. Εδώ ο
 * χρήστης πηγαίνει στην πραγματική οθόνη, κάνει την πραγματική ενέργεια, και η
 * μπάρα το **αναγνωρίζει** — γιατί ελέγχει τα δεδομένα, όχι τα πατήματα.
 */
@Composable
fun TourBar(
    facts: TourFacts,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!TourState.active) return
    val step = TOUR.getOrNull(TourState.step) ?: return
    val completed = step.done(facts)
    val alreadyThere = step.destination != null &&
        facts.currentRoute?.substringBefore('?') == step.destination.route

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(step.icon),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            step.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${TourState.step + 1}/${TOUR.size}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(step.body, style = MaterialTheme.typography.bodySmall)

                    if (completed && step.doneLabel.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "✓ ${step.doneLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (TourState.step + 1f) / TOUR.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (TourState.step > 0) {
                            TextButton(onClick = { TourState.back() }) { Text("Πίσω") }
                        }
                        TextButton(onClick = { TourState.finish() }) { Text("Κλείσιμο") }
                        Spacer(Modifier.weight(1f))

                        // Το κύριο κουμπί αλλάζει με την κατάσταση: πήγαινέ με,
                        // ή προχώρα. Δεν υπάρχουν δύο κουμπιά που μοιάζουν.
                        if (step.destination != null && !alreadyThere && !completed) {
                            Button(onClick = { onNavigate(step.destination) }) {
                                Text("Πάμε εκεί")
                            }
                        } else {
                            Button(onClick = { TourState.next() }) {
                                Text(if (TourState.step == TOUR.lastIndex) "Τέλος" else "Επόμενο")
                            }
                        }
                    }
                }
            }
        }
    }
}
