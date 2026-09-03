package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.data.ClientKind
import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import gr.scanmydata.taxcenter.data.Normalize
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.ConsentEntity
import gr.scanmydata.taxcenter.gdpr.Exports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Η χειροκίνητη καρτέλα πελάτη.
 *
 * Ίδιοι κανόνες με την εισαγωγή από Excel, και για τον ίδιο λόγο:
 *
 *  * **Το ΑΦΜ κλειδώνει στην επεξεργασία.** Είναι το κλειδί ταυτότητας· αλλαγή
 *    του δεν είναι διόρθωση αλλά «άλλος πελάτης», και θα άφηνε τα έγγραφα και
 *    το ιστορικό κολλημένα σε λάθος άνθρωπο.
 *  * **Κενό πεδίο κωδικού δεν σβήνει αποθηκευμένο.** Η φόρμα δείχνει τους
 *    αποθηκευμένους κωδικούς μασκαρισμένους· αν δεν τους αγγίξεις, μένουν.
 *  * Ο έλεγχος mod-11 του ΑΦΜ είναι **συμβουλευτικός**: υπάρχουν πραγματικά ΑΦΜ
 *    που δεν τον περνούν, και μια απόρριψη εδώ θα ήταν χειρότερη από μια
 *    προειδοποίηση.
 *
 * Η φόρμα ξεκινά από τους κωδικούς TAXISnet και όχι από τα στοιχεία, επειδή
 * αυτή είναι η σειρά της δουλειάς: με τους κωδικούς στο χέρι, τα υπόλοιπα
 * έρχονται από το Μητρώο με ένα πάτημα.
 */
@Composable
fun ClientEditScreen(
    container: AppContainer,
    clientId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isNew = clientId == 0L

    var loaded by remember { mutableStateOf(isNew) }
    var existing by remember { mutableStateOf<ClientEntity?>(null) }

    var afm by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var amka by remember { mutableStateOf("") }
    var doy by remember { mutableStateOf("") }
    var maritalStatus by remember { mutableStateOf("") }
    var spouseAfm by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }

    var emailAade by remember { mutableStateOf("") }
    var emailManual by remember { mutableStateOf("") }
    var preferManual by remember { mutableStateOf(false) }

    val credentials = remember { mutableStateMapOf<Field, String>() }
    var revealSecrets by remember { mutableStateOf(false) }

    var consentAt by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(clientId) {
        if (isNew) return@LaunchedEffect
        val client = withContext(Dispatchers.IO) { container.db.clients().byId(clientId) }
        if (client == null) {
            status = "Ο πελάτης δεν βρέθηκε."
            loaded = true
            return@LaunchedEffect
        }
        existing = client
        afm = client.afm
        name = client.name
        firstName = client.firstName
        kind = ClientKind.normalise(client.kind)
        amka = withContext(Dispatchers.IO) { container.repository.amka(client) }
        doy = client.doy
        maritalStatus = client.maritalStatus
        spouseAfm = client.spouseAfm
        active = client.active
        emailAade = client.emailAade
        emailManual = client.emailManual
        preferManual = client.emailPreferred.isNotBlank() &&
            client.emailPreferred == client.emailManual
        withContext(Dispatchers.IO) { container.repository.credentials(client.id) }
            .forEach { (field, value) -> credentials[field] = value }
        consentAt = withContext(Dispatchers.IO) {
            container.db.consents().forClient(client.id)?.grantedAt ?: 0L
        }
        loaded = true
    }

    if (!loaded) {
        Column(modifier.padding(24.dp)) { Text("Φόρτωση…") }
        return
    }

    val afmClean = Normalize.afm(afm)
    val afmWarning = when {
        afmClean.isBlank() -> "Θα συμπληρωθεί από την άντληση, ή γράψ' το εδώ."
        afmClean.length != 9 -> "Το ΑΦΜ πρέπει να έχει 9 ψηφία."
        !Normalize.validAfm(afmClean) -> "Ο έλεγχος mod-11 δεν περνά — έλεγξέ το, αλλά μπορεί να είναι σωστό."
        else -> ""
    }
    val hasAmka = kind.isBlank() || ClientKind.hasAmka(kind)
    val canBeEmployer = ClientKind.normalise(kind) != ClientKind.PRIVATE
    val taxisUser = credentials[Field.TAXIS_USER].orEmpty()
    val taxisPass = credentials[Field.TAXIS_PASS].orEmpty()

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {

        Text(if (isNew) "Νέος πελάτης" else "Καρτέλα πελάτη", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------ άντληση από TAXIS

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Κωδικοί TAXISnet", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Με αυτούς γίνεται η άντληση στοιχείων και όλες οι λήψεις εντύπων.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                SecretField("Όνομα χρήστη", credentials, Field.TAXIS_USER, reveal = true)
                SecretField("Συνθηματικό", credentials, Field.TAXIS_PASS, revealSecrets)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { revealSecrets = !revealSecrets }) {
                        Text(if (revealSecrets) "Απόκρυψη" else "Εμφάνιση")
                    }
                    Spacer(Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Button(
                        enabled = !busy && taxisUser.isNotBlank() && taxisPass.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "Σύνδεση στο TAXIS και ανάγνωση Μητρώου…"
                                val profile = container.fetch.lookupProfile(
                                    user = taxisUser,
                                    pass = taxisPass,
                                    // Σε υπάρχοντα πελάτη ρωτάμε ρητά για το ΑΦΜ
                                    // του· σε νέο, ο λογαριασμός λέει ποιος είναι.
                                    afm = if (isNew) "" else afmClean,
                                )
                                busy = false
                                status = if (!profile.ok) {
                                    profile.error
                                } else {
                                    if (isNew && profile.afm.isNotBlank()) afm = profile.afm
                                    if (profile.name.isNotBlank()) name = profile.name
                                    if (profile.firstName.isNotBlank()) firstName = profile.firstName
                                    if (profile.kind.isNotBlank()) kind = profile.kind
                                    if (profile.doy.isNotBlank()) doy = profile.doy
                                    if (profile.email.isNotBlank()) emailAade = profile.email
                                    // Ιδιώτης ή ατομική: ο ΑΜΚΑ έρχεται μαζί με
                                    // τα υπόλοιπα. Ερχόταν και πριν — απλώς δεν
                                    // τον έγραφε κανείς στο πεδίο, οπότε η
                                    // «αυτόματη άντληση ΑΜΚΑ» φαινόταν νεκρή ενώ
                                    // η δεύτερη σύνδεση στο MyAMKA είχε ήδη γίνει.
                                    if (profile.amka.isNotBlank()) amka = profile.amka
                                    if (profile.maritalStatus.isNotBlank()) {
                                        maritalStatus = profile.maritalStatus
                                    }
                                    // Ο ΑΦΜ συζύγου έρχεται από τις σχέσεις
                                    // μητρώου, στην ίδια σύνδεση.
                                    if (profile.spouseAfm.isNotBlank()) {
                                        spouseAfm = profile.spouseAfm
                                    }
                                    active = profile.active
                                    if (!isNew && profile.afm.isNotBlank() && profile.afm != afmClean) {
                                        "⚠ Ο λογαριασμός ανήκει σε άλλο ΑΦΜ (${profile.afm}) — " +
                                            "έλεγξε τους κωδικούς. Τα στοιχεία δεν εφαρμόστηκαν στο ΑΦΜ."
                                    } else if (profile.amkaNote.isNotBlank()) {
                                        // Ο ΑΜΚΑ είναι δεύτερη σύνδεση, σε άλλη
                                        // πύλη. Η αποτυχία του δεν ακυρώνει την
                                        // άντληση — αλλά πρέπει να λέγεται.
                                        "Συμπληρώθηκαν από το Μητρώο. Ο ΑΜΚΑ δεν ήρθε: " +
                                            profile.amkaNote
                                    } else if (profile.spouseName.isNotBlank()) {
                                        "Συμπληρώθηκαν από το Μητρώο. Βρέθηκε και " +
                                            "σύζυγος: " + profile.spouseName + "."
                                    } else {
                                        "Συμπληρώθηκαν από το Μητρώο. Έλεγξέ τα και αποθήκευσε."
                                    }
                                }
                            }
                        },
                    ) { Text("Άντληση στοιχείων") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------------- ταυτότητα

        OutlinedTextField(
            value = afm,
            onValueChange = { afm = it.filter(Char::isDigit).take(9) },
            label = { Text("ΑΦΜ") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = isNew,
            isError = afmClean.isNotBlank() && afmClean.length != 9,
            supportingText = { if (afmWarning.isNotBlank()) Text(afmWarning) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FormField("Επωνυμία / Επώνυμο", name) { name = it }
        FormField("Όνομα", firstName) { firstName = it }

        KindDropdown(kind) { kind = it }

        if (hasAmka) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = amka,
                    onValueChange = { amka = it.filter(Char::isDigit).take(11) },
                    label = { Text("ΑΜΚΑ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !busy && taxisUser.isNotBlank() && taxisPass.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Σύνδεση στο MyAMKA…"
                            val result = container.fetch.lookupAmka(taxisUser, taxisPass)
                            busy = false
                            status = if (result.startsWith("!")) {
                                result.removePrefix("!")
                            } else {
                                amka = result
                                "Βρέθηκε ΑΜΚΑ."
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Άντληση") }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                "Το νομικό πρόσωπο δεν έχει ΑΜΚΑ — γι' αυτό δεν εμφανίζεται το " +
                    "πεδίο, και γι' αυτό δεν ισχύουν οι διαδικασίες ΕΦΚΑ, ΑΤΛΑΣ και ΚΕΑΟ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        FormField("ΔΟΥ", doy) { doy = it }

        // Οικογενειακή κατάσταση και σύζυγος — μόνο όπου υπάρχει φυσικό
        // πρόσωπο από πίσω. Σε νομικό πρόσωπο δεν είναι κενά πεδία, δεν
        // υφίστανται, όπως και ο ΑΜΚΑ.
        if (hasAmka) {
            FormField("Οικογενειακή κατάσταση (από το Μητρώο)", maritalStatus) {
                maritalStatus = it
            }
            OutlinedTextField(
                value = spouseAfm,
                onValueChange = { spouseAfm = it.filter(Char::isDigit).take(9) },
                label = { Text("ΑΦΜ συζύγου") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = {
                    Text(
                        when {
                            spouseAfm.isNotBlank() ->
                                "Η σχέση γράφεται και στις δύο καρτέλες, αν υπάρχει η άλλη."
                            maritalStatus.contains("ΕΓΓΑΜ", ignoreCase = true) ->
                                "Ο πελάτης δηλώνεται έγγαμος. Το Μητρώο δεν δίνει τον ΑΦΜ " +
                                    "του συζύγου — τον δίνει το ETAK μόνο όταν εμφανίζεται " +
                                    "στο Ε9, αλλιώς συμπλήρωσέ τον εδώ."
                            else ->
                                "Χρειάζεται για τη σύνδεση των δύο καρτελών. Το εκκαθαριστικό " +
                                    "συζύγου δεν το χρειάζεται — βγαίνει από την κοινή δήλωση."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = active, onCheckedChange = { active = it })
            Text("  Ενεργός", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------------------------------------------------------- email

        Text("Διευθύνσεις email", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = emailAade,
            onValueChange = { emailAade = it },
            label = { Text("Από το Μητρώο Επικοινωνίας ΑΑΔΕ") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            enabled = !busy && !isNew && existing != null,
            onClick = {
                val client = existing ?: return@OutlinedButton
                scope.launch {
                    busy = true
                    status = "Αναζήτηση στο Μητρώο Επικοινωνίας…"
                    val result = container.fetch.lookupEmail(client)
                    busy = false
                    if (result.startsWith("!")) {
                        status = result.removePrefix("!")
                    } else {
                        emailAade = result
                        status = "Βρέθηκε: $result"
                    }
                }
            },
        ) { Text("Ενημέρωση από ΑΑΔΕ") }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = emailManual,
            onValueChange = { emailManual = it },
            label = { Text("Δεύτερη διεύθυνση (δική σου καταχώρηση)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("Ποια χρησιμοποιείται στην αποστολή:", style = MaterialTheme.typography.bodySmall)
        Row {
            FilterChip(
                selected = !preferManual,
                onClick = { preferManual = false },
                label = { Text("ΑΑΔΕ") },
            )
            FilterChip(
                selected = preferManual,
                onClick = { preferManual = true },
                label = { Text("Δεύτερη") },
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ------------------------------------------- υπόλοιπα διαπιστευτήρια

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Άλλα διαπιστευτήρια",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { revealSecrets = !revealSecrets }) {
                Text(if (revealSecrets) "Απόκρυψη" else "Εμφάνιση")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Αποθηκεύονται κρυπτογραφημένα με AES-256-GCM. Κενό πεδίο δεν σβήνει " +
                "αποθηκευμένη τιμή.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))

        SecretField("Κλειδάριθμος", credentials, Field.TAXIS_KLIDARITHMOS, revealSecrets)

        // Ο ιδιώτης δεν είναι εργοδότης — δεν έχει ΑΜΕ, δεν έχει καρτέλα
        // εργοδότη, δεν υπάρχουν κωδικοί ΙΚΑ εργοδότη να καταχωρήσει. Τα πεδία
        // δεν είναι απλώς άχρηστα εκεί: γεμίζουν τη φόρμα και κάνουν κάποιον να
        // αναρωτηθεί τι ξέχασε να συμπληρώσει.
        if (canBeEmployer) {
            SecretField("Όνομα χρήστη ΙΚΑ εργοδότη", credentials, Field.IKA_EMPLOYER_USER, reveal = true)
            SecretField("Συνθηματικό ΙΚΑ εργοδότη", credentials, Field.IKA_EMPLOYER_PASS, revealSecrets)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------------- εντολή

        Text("Εντολή πελάτη", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (consentAt == 0L) {
                "Δεν έχει καταγραφεί εξουσιοδότηση. Ο λογιστής ενεργεί κατ' εντολή " +
                    "του πελάτη· η καταγραφή είναι αυτό που το αποδεικνύει."
            } else {
                "Καταγράφηκε ${AthensDates.day(consentAt)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (consentAt == 0L) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (consentAt == 0L && !isNew) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                val client = existing ?: return@OutlinedButton
                scope.launch {
                    val now = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        container.db.consents().put(ConsentEntity(client.id, now))
                    }
                    consentAt = now
                }
            }) { Text("Καταγραφή εντολής σήμερα") }
        }

        Spacer(Modifier.height(20.dp))
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        Row {
            Button(
                enabled = !busy && afmClean.length == 9,
                onClick = {
                    scope.launch {
                        status = "Αποθήκευση…"
                        status = try {
                            val base = existing
                            val normalisedKind = ClientKind.normalise(kind)
                            val entity = (base ?: ClientEntity(afm = afmClean)).copy(
                                afm = afmClean,
                                name = name.trim(),
                                firstName = firstName.trim(),
                                kind = normalisedKind,
                                // Νομικό πρόσωπο δεν έχει ΑΜΚΑ: ό,τι κι αν
                                // έμεινε στο πεδίο από προηγούμενη επιλογή, δεν
                                // αποθηκεύεται.
                                amkaEnc = if (ClientKind.hasAmka(normalisedKind)) {
                                    container.crypto.enc(Normalize.amka(amka))
                                } else {
                                    ""
                                },
                                doy = doy.trim(),
                                active = active,
                                emailAade = emailAade.trim(),
                                emailManual = emailManual.trim(),
                                emailPreferred = if (preferManual) emailManual.trim() else "",
                                maritalStatus = maritalStatus.trim(),
                                // Η αμοιβαία σύνδεση γίνεται μετά την
                                // αποθήκευση, από το repository: εδώ γράφεται
                                // μόνο η δική μας πλευρά.
                                spouseAfm = Normalize.afm(spouseAfm),
                            )
                            withContext(Dispatchers.IO) {
                                val savedId =
                                    container.repository.saveClient(entity, credentials.toMap())
                                // Η σχέση γράφεται και στην καρτέλα του
                                // συζύγου. Μονόπλευρη σύνδεση σημαίνει ότι από
                                // την άλλη πλευρά δεν φαίνεται τίποτα, και
                                // κάποια στιγμή δηλώνεται ξανά ανάποδα.
                                if (spouseAfm.isNotBlank()) {
                                    container.repository.linkSpouse(
                                        clientId = if (savedId != 0L) savedId else clientId,
                                        spouseAfm = spouseAfm,
                                    )
                                }
                            }
                            onDone()
                            ""
                        } catch (e: Exception) {
                            "Απέτυχε: ${e.message}"
                        }
                    }
                },
            ) { Text("Αποθήκευση") }

            if (!isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.padding(start = 12.dp),
                ) { Text("Διαγραφή") }
            }
        }

        if (!isNew) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                val client = existing ?: return@OutlinedButton
                scope.launch {
                    status = "Δημιουργία αρχείου…"
                    status = try {
                        val file = withContext(Dispatchers.IO) {
                            Exports.clientZip(context, container.db, container.repository, client)
                        }
                        Exports.share(context, file, "application/zip", "Δεδομένα πελάτη")
                        "Έτοιμο (${file.length() / 1024} KB)."
                    } catch (e: Exception) {
                        "Απέτυχε: ${e.message}"
                    }
                }
            }) { Text("Εξαγωγή δεδομένων (ZIP)") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Φορητότητα κατά το άρθρο 20: στοιχεία, έγγραφα και ιστορικό " +
                    "αποστολών. Οι κωδικοί **δεν** μπαίνουν — το αρχείο φεύγει από " +
                    "τη συσκευή σε κανάλι που δεν ελέγχεις.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        val client = existing
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Οριστική διαγραφή") },
            text = {
                Text(
                    "Θα διαγραφούν τα διαπιστευτήρια, τα έγγραφα και τα αρχεία του " +
                        "πελάτη ${client?.displayName.orEmpty()} (${client?.afm.orEmpty()}).\n\n" +
                        "Το αρχείο ενεργειών μένει: είναι αυτό που αποδεικνύει ότι η " +
                        "διαγραφή έγινε, και δεν περιέχει προσωπικά δεδομένα πέρα από " +
                        "το ΑΦΜ.\n\nΗ ενέργεια δεν αναιρείται.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        if (client != null) {
                            withContext(Dispatchers.IO) { container.repository.deleteClient(client) }
                        }
                        onDone()
                    }
                }) { Text("Διαγραφή") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Άκυρο") } },
        )
    }
}

/**
 * Το είδος υπόχρεου είναι κλειστή λίστα, όχι ελεύθερο κείμενο: από αυτό
 * κρίνεται αν υπάρχει ΑΜΚΑ και ποιες διαδικασίες ισχύουν. Ένα «ΦΠ»
 * πληκτρολογημένο στο χέρι θα έκρυβε πεδία χωρίς λόγο.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindDropdown(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text("Είδος υπόχρεου") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ClientKind.ALL.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun SecretField(
    label: String,
    values: MutableMap<Field, String>,
    field: Field,
    reveal: Boolean,
) {
    OutlinedTextField(
        value = values[field].orEmpty(),
        onValueChange = { values[field] = it },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}
