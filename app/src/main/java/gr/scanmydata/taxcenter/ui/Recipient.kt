package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.Normalize
import gr.scanmydata.taxcenter.data.db.ClientEntity

/**
 * Ποιος παραλήπτης, για **αυτή** την αποστολή.
 *
 * Ένας πελάτης μπορεί να έχει δύο καταχωρημένες διευθύνσεις — αυτή του Μητρώου
 * ΑΑΔΕ και αυτή που έγραψε ο λογιστής — και η καρτέλα λέει ποια είναι η
 * προεπιλογή. Αυτό όμως αφορά τη *συνήθη* αποστολή. Στη μεμονωμένη άντληση ο
 * λογιστής συχνά ξέρει κάτι που η καρτέλα δεν ξέρει: «στείλ' το στον λογιστή
 * της εταιρείας», «η ΑΑΔΕ έχει την παλιά του δουλειάς».
 *
 * Η τρίτη επιλογή είναι **εφήμερη κατά σχεδίαση**. Δεν γράφεται στην καρτέλα,
 * δεν γίνεται προεπιλογή, δεν θυμάται η επόμενη αποστολή. Μια διεύθυνση που
 * δόθηκε για μία φορά και έμεινε αποθηκευμένη είναι ακριβώς ο τρόπος με τον
 * οποίο φορολογικά έντυπα καταλήγουν, μήνες μετά, σε λάθος άνθρωπο.
 *
 * Η διεύθυνση φτάνει στο [gr.scanmydata.taxcenter.mail.MailService.sendDocuments]
 * ως `overrideTo` και μένει μόνο στην εγγραφή αποστολής — εκεί πρέπει να
 * φαίνεται, γιατί το ημερολόγιο απαντά στο «πού στάλθηκε τελικά».
 */
/**
 * @param client `null` όταν δεν υπάρχει **ένας** πελάτης — π.χ. στη λήψη για
 *   πολλούς. Τότε δεν υπάρχουν καταχωρημένες διευθύνσεις να διαλέξεις και ο
 *   επιλογέας δεν δείχνεται καν· η ίδια η κλάση απλώς δεν σκάει.
 */
@Stable
class RecipientState(client: ClientEntity?) {

    enum class Mode { AADE, MANUAL, ONCE }

    val aade: String = Normalize.email(client?.emailAade)
    val manual: String = Normalize.email(client?.emailManual)

    /** Η επιλογή της καρτέλας, όταν υπάρχει· αλλιώς ό,τι είναι διαθέσιμο. */
    var mode: Mode by mutableStateOf(
        when {
            manual.isNotBlank() && Normalize.email(client?.emailPreferred) == manual -> Mode.MANUAL
            aade.isNotBlank() -> Mode.AADE
            manual.isNotBlank() -> Mode.MANUAL
            else -> Mode.ONCE
        },
    )

    /** Η εφήμερη διεύθυνση, όπως την πληκτρολογεί ο χρήστης. */
    var once: String by mutableStateOf("")

    val address: String
        get() = when (mode) {
            Mode.AADE -> aade
            Mode.MANUAL -> manual
            Mode.ONCE -> Normalize.email(once)
        }

    val valid: Boolean get() = Normalize.validEmail(address)

    /** Έχει ο πελάτης δεύτερη διεύθυνση, ώστε να αξίζει η επιλογή; */
    val hasChoice: Boolean get() = aade.isNotBlank() && manual.isNotBlank()
}

@Composable
fun rememberRecipient(client: ClientEntity?): RecipientState =
    remember(client?.id, client?.emailAade, client?.emailManual, client?.emailPreferred) {
        RecipientState(client)
    }

/**
 * Οι επιλογές παραλήπτη, όπως τις βλέπει ο λογιστής πριν πατήσει «Αποστολή».
 *
 * Οι καταχωρημένες διευθύνσεις εμφανίζονται **ολόκληρες**, όχι μασκαρισμένες:
 * το νόημα της οθόνης είναι να διαβαστεί η διεύθυνση και να πιαστεί το λάθος.
 */
@Composable
fun RecipientPicker(state: RecipientState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Παραλήπτης", style = MaterialTheme.typography.labelMedium)

        if (state.aade.isNotBlank()) {
            RecipientOption(
                selected = state.mode == RecipientState.Mode.AADE,
                onSelect = { state.mode = RecipientState.Mode.AADE },
                title = state.aade,
                subtitle = "Μητρώο Επικοινωνίας ΑΑΔΕ",
            )
        }
        if (state.manual.isNotBlank()) {
            RecipientOption(
                selected = state.mode == RecipientState.Mode.MANUAL,
                onSelect = { state.mode = RecipientState.Mode.MANUAL },
                title = state.manual,
                subtitle = "Καταχωρημένη στην καρτέλα",
            )
        }
        RecipientOption(
            selected = state.mode == RecipientState.Mode.ONCE,
            onSelect = { state.mode = RecipientState.Mode.ONCE },
            title = "Άλλη διεύθυνση",
            subtitle = "Μόνο γι' αυτή την αποστολή — δεν αποθηκεύεται",
        )

        if (state.mode == RecipientState.Mode.ONCE) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.once,
                onValueChange = { state.once = it },
                label = { Text("Διεύθυνση email") },
                singleLine = true,
                isError = state.once.isNotBlank() && !state.valid,
                supportingText = {
                    if (state.once.isNotBlank() && !state.valid) {
                        Text("Η διεύθυνση δεν φαίνεται σωστή.")
                    }
                },
                // Χωρίς κεφαλαίο αρχικό γράμμα: το Android το βάζει από μόνο
                // του και μετά ο χρήστης απορεί γιατί «δεν δέχεται» το email.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RecipientOption(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
