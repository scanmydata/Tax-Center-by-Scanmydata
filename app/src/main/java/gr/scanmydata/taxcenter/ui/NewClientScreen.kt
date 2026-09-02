package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import gr.scanmydata.taxcenter.R

/**
 * Καταχώρηση πελατών — μία οθόνη, δύο αφετηρίες.
 *
 * Η χειροκίνητη καρτέλα και η εισαγωγή από Excel ήταν χωριστές θέσεις μενού.
 * Είναι όμως η ίδια δουλειά: «βάλε πελάτες στο σύστημα». Ο διαχωρισμός ανάγκαζε
 * τον χρήστη να διαλέξει διαδρομή πριν καταλάβει ότι καταλήγουν στο ίδιο σημείο,
 * και έκρυβε από όποιον ξεκινούσε χειροκίνητα ότι υπάρχει μαζική εισαγωγή.
 */
@Composable
fun NewClientScreen(
    container: AppContainer,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var showGuide by remember { mutableStateOf(false) }

    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Χειροκίνητα") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Από Excel")
                        IconButton(onClick = { showGuide = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info_outline),
                                contentDescription = "Πώς εξάγω το αρχείο",
                            )
                        }
                    }
                },
            )
        }

        when (tab) {
            0 -> ClientEditScreen(container = container, clientId = 0L, onDone = onDone)
            else -> ImportScreen(container)
        }
    }

    if (showGuide) {
        ExcelGuideDialog(onDismiss = { showGuide = false })
    }
}

/**
 * Τι αρχείο χρειάζεται η εισαγωγή και τι γίνεται με αυτό.
 *
 * Ο οδηγός επιμένει στην ασφάλεια περισσότερο απ' όσο σε βήματα μενού, και όχι
 * από υπερβολή: το εξαγόμενο αρχείο περιέχει **συνθηματικά TAXISnet και
 * κλειδάριθμους σε καθαρό κείμενο**. Είναι, με διαφορά, το πιο επικίνδυνο
 * αρχείο σε ολόκληρη τη ροή του γραφείου.
 */
@Composable
private fun ExcelGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Το αρχείο «Κωδικοί Υπόχρεων»") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Step(
                    "1. Εξαγωγή από το λογιστικό πρόγραμμα",
                    "Στο Epsilon TaxSystem (και στο Hyper/Extra) η κατάσταση με τα " +
                        "στοιχεία και τους κωδικούς των υπόχρεων εξάγεται σε Excel από " +
                        "τις εκτυπώσεις/εξαγωγές του μητρώου πελατών. Το αρχείο που " +
                        "χρειάζεται εδώ είναι αυτό που έχει στήλες ΑΦΜ, Επωνυμία, ΔΟΥ " +
                        "και κωδικούς TAXISnet — τυπικά ονομάζεται " +
                        "«Κωδικοί_Υπόχρεων.xlsx».",
                )
                Step(
                    "2. Μεταφορά στη συσκευή",
                    "Αντίγραψέ το στο κινητό με καλώδιο ή μέσω του δικού σου cloud. " +
                        "Μην το στείλεις με email και μην το ανεβάσεις σε υπηρεσία " +
                        "κοινής χρήσης: περιέχει κωδικούς σε καθαρό κείμενο.",
                )
                Step(
                    "3. Επιλογή και προεπισκόπηση",
                    "Πάτα «Επιλογή αρχείου». Η εφαρμογή δείχνει **πρώτα** τι θα κάνει: " +
                        "ποιοι πελάτες είναι νέοι, ποιοι θα ενημερωθούν και ποιοι " +
                        "μένουν ίδιοι — με τους κωδικούς μασκαρισμένους. Τίποτα δεν " +
                        "γράφεται πριν το εγκρίνεις.",
                )
                Step(
                    "4. Διαγραφή του αρχείου",
                    "Μόλις τελειώσει η εισαγωγή, σβήσε το αρχείο από τη συσκευή. Η " +
                        "εφαρμογή θα σου το θυμίσει. Μέσα στην εφαρμογή οι κωδικοί " +
                        "ζουν κρυπτογραφημένοι· στο Excel όχι.",
                )

                Spacer(Modifier.height(12.dp))
                Text("Τι κρατάμε από το αρχείο", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Από τις ~83 στήλες του export διαβάζονται μόνο ΑΦΜ, επωνυμία, " +
                        "όνομα, είδος, ΑΜΚΑ, ΔΟΥ, κατάσταση και οι κωδικοί TAXISnet " +
                        "και ΙΚΑ εργοδότη. Οι υπόλοιπες δεν φτάνουν καν στη βάση — " +
                        "ελαχιστοποίηση δεδομένων, άρθρο 5 παρ. 1 στοιχείο γ ΓΚΠΔ.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Κενό κελί δεν σβήνει ποτέ αποθηκευμένη τιμή: ένα μερικό export " +
                        "δεν μπορεί να αδειάσει τους κωδικούς όλων.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Κατάλαβα") } },
    )
}

@Composable
private fun Step(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(2.dp))
    Text(body, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
}
