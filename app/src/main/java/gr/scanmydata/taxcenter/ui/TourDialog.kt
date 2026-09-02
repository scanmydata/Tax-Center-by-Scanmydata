package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import gr.scanmydata.taxcenter.R

/**
 * Η ξενάγηση της πρώτης εκκίνησης.
 *
 * Επτά κάρτες, όσες και οι θέσεις του μενού, με το εικονίδιο της καθεμιάς. Δεν
 * είναι επικάλυψη με βελάκια πάνω στη διεπαφή: αυτές οι ξεναγήσεις σπάνε σε
 * κάθε αλλαγή διάταξης, και σε συσκευή που ο χρήστης κρατά με το ένα χέρι δεν
 * διαβάζονται. Ένα σύντομο «τι είναι πού», που κλείνει με ένα πάτημα.
 *
 * Εμφανίζεται **μία φορά** και ξαναδιαβάζεται από τις Οδηγίες χρήσης.
 */
@Composable
fun TourDialog(onFinish: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val page = TOUR[step]

    AlertDialog(
        onDismissRequest = onFinish,
        icon = {
            if (step == 0) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Icon(painter = painterResource(page.icon), contentDescription = null)
            }
        },
        title = { Text(page.title) },
        text = {
            Column {
                Text(page.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { (step + 1f) / TOUR.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${step + 1} από ${TOUR.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (step == TOUR.lastIndex) onFinish() else step++ }) {
                Text(if (step == TOUR.lastIndex) "Ξεκινάμε" else "Επόμενο")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text("Πίσω") }
                }
                TextButton(onClick = onFinish, modifier = Modifier.padding(start = 4.dp)) {
                    Text("Παράλειψη")
                }
            }
        },
    )
}

private data class TourPage(
    val title: String,
    val body: String,
    @DrawableRes val icon: Int,
)

private val TOUR = listOf(
    TourPage(
        "Καλώς ήρθες",
        "Η εφαρμογή κατεβάζει τα φορολογικά και ασφαλιστικά έντυπα των πελατών σου " +
            "και τα στέλνει με το Gmail σου. Όλα γίνονται στη συσκευή — δεν υπάρχει " +
            "διακομιστής μας και κανένα δεδομένο πελάτη δεν φεύγει προς εμάς.\n\n" +
            "Τριάντα δευτερόλεπτα για να δεις τι είναι πού.",
        R.drawable.ic_menu_help,
    ),
    TourPage(
        "Νέος πελάτης",
        "Βάζεις τους κωδικούς TAXISnet του πελάτη και πατάς «Άντληση στοιχείων»: " +
            "ονοματεπώνυμο, ΑΦΜ, ΔΟΥ, είδος και ΑΜΚΑ έρχονται μόνα τους από το " +
            "Μητρώο.\n\nΣτην ίδια οθόνη, η καρτέλα «Από Excel» εισάγει όλους μαζί από " +
            "το αρχείο του λογιστικού προγράμματος — πάντα με προεπισκόπηση πριν " +
            "γραφτεί οτιδήποτε.",
        R.drawable.ic_menu_client_new,
    ),
    TourPage(
        "Πελάτες",
        "Η λίστα. Πάτα έναν πελάτη και διαλέγεις: άνοιγμα καρτέλας, αποστολή εντύπων, " +
            "ή αποστολή των στοιχείων του.\n\nΤο «Επιλογή» πάνω δεξιά ενεργοποιεί " +
            "μαζικές ενέργειες — διαγραφή εγγράφων ή οριστική διαγραφή πελατών.",
        R.drawable.ic_menu_clients,
    ),
    TourPage(
        "Λήψη εντύπων",
        "Προσθέτεις έντυπα ένα-ένα, και **κάθε ένα κρατά δικό του έτος**: Ε1 του 2025 " +
            "και Ε9 του 2027 στην ίδια εκτέλεση.\n\nΔιαλέγεις πελάτες, και προαιρετικά " +
            "«Αποστολή με email μόλις κατέβουν». Στην ίδια οθόνη γίνεται και η μαζική " +
            "αποστολή κωδικών στους πελάτες.",
        R.drawable.ic_menu_fetch,
    ),
    TourPage(
        "Έγγραφα και ημερολόγιο",
        "Στα «Έγγραφα» βλέπεις ό,τι έχει κατέβει, ομαδοποιημένο ανά πελάτη, και " +
            "στέλνεις επιλεκτικά.\n\nΤο «Ημερολόγιο αποστολών» δείχνει τι στάλθηκε και " +
            "πότε, με τις αποτυχίες σε κόκκινο και επανάληψη με ένα πάτημα.",
        R.drawable.ic_menu_calendar,
    ),
    TourPage(
        "Ασφάλεια",
        "Η βάση είναι κρυπτογραφημένη ολόκληρη και κάθε κωδικός ξεχωριστά, με κλειδί " +
            "που δεν φεύγει από τη συσκευή.\n\nΤα στιγμιότυπα οθόνης είναι " +
            "μπλοκαρισμένα, και από τη **δεύτερη** εκκίνηση η εφαρμογή ζητά " +
            "βιομετρικά ή τον κωδικό της συσκευής.\n\nΚωδικοί μιας χρήσης και CAPTCHA " +
            "δεν παρακάμπτονται ποτέ — όπου ζητούνται, τα συμπληρώνεις εσύ.",
        R.drawable.ic_menu_security,
    ),
    TourPage(
        "Πριν ξεκινήσεις",
        "Πήγαινε στις **Ρυθμίσεις** και σύνδεσε τον λογαριασμό Google — χωρίς αυτόν " +
            "δεν φεύγει κανένα email. Βάλε και το όνομα του γραφείου σου, μπαίνει " +
            "στην υπογραφή.\n\nΟλόκληρο το εγχειρίδιο είναι στις «Οδηγίες χρήσης», " +
            "και δουλεύει χωρίς δίκτυο.",
        R.drawable.ic_menu_settings,
    ),
)
