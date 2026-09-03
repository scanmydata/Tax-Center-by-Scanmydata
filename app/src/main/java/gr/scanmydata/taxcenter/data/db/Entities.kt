package gr.scanmydata.taxcenter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ένας πελάτης του γραφείου. Κλειδί ταυτότητας το ΑΦΜ.
 *
 * Δεν κρατάμε τίποτα από τις 83 στήλες του Excel πέρα από όσα χρειάζονται οι
 * διαδικασίες και η επικοινωνία (GDPR άρθρο 5 παρ. 1 στοιχείο γ). Ειδικά: καμία
 * διεύθυνση, κανένα ΑΜΚΑ συζύγου, καμία κατηγορία βιβλίων.
 */
@Entity(
    tableName = "clients",
    indices = [Index(value = ["afm"], unique = true)],
)
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val afm: String,
    val name: String = "",
    val firstName: String = "",
    /** Ιδιώτης / Ατομική Επιχείρηση / Νομικό Πρόσωπο — βλ. `ClientKind`. */
    val kind: String = "",
    /**
     * Χρειάζεται για τα logins ΕΦΚΑ/ΑΤΛΑΣ/ΚΕΑΟ. Κρυπτογραφημένο.
     *
     * Υπάρχει μόνο για ιδιώτη και ατομική επιχείρηση: σε νομικό πρόσωπο δεν
     * είναι απλώς κενό, δεν υφίσταται.
     */
    val amkaEnc: String = "",
    val doy: String = "",
    val active: Boolean = true,

    /**
     * Οικογενειακή κατάσταση όπως τη λέει το Μητρώο («ΕΓΓΑΜΟΣ-Η», «ΑΓΑΜΟΣ-Η»…).
     *
     * Δεν είναι διακοσμητικό: κρίνει αν έχει νόημα το εκκαθαριστικό συζύγου,
     * που τυπώνεται από την κοινή δήλωση με τους κωδικούς του ίδιου του
     * υπόχρεου.
     */
    val maritalStatus: String = "",

    /**
     * Ο ΑΦΜ του/της συζύγου, **όταν τον ξέρουμε**.
     *
     * Το Μητρώο λέει ότι κάποιος είναι έγγαμος αλλά δεν δίνει τον ΑΦΜ. Τον
     * δίνει το ETAK, και μόνο όταν ο σύζυγος εμφανίζεται στο Ε9. Γι' αυτό είναι
     * προαιρετικό και συμπληρώνεται είτε αυτόματα είτε με το χέρι.
     *
     * Κρατάμε ΑΦΜ και όχι `id`: η καρτέλα του συζύγου μπορεί να μην υπάρχει
     * ακόμη, και ο ΑΦΜ είναι το σταθερό αναγνωριστικό ούτως ή άλλως.
     */
    val spouseAfm: String = "",

    /** Από το Μητρώο Επικοινωνίας ΑΑΔΕ (`getLdapInfo`). */
    val emailAade: String = "",
    /** Δεύτερη διεύθυνση που καταχωρεί ο λογιστής. */
    val emailManual: String = "",
    /** Ποια από τις δύο χρησιμοποιείται στην αποστολή· κενό = η ΑΑΔΕ. */
    val emailPreferred: String = "",

    val sourceFile: String = "",
    val importedAt: Long = 0,
    val updatedAt: Long = 0,
    /** Ήπια διαγραφή, ώστε να μείνει ίχνος στο audit· η σκληρή είναι ρητή ενέργεια. */
    val deleted: Boolean = false,
) {
    /** Η διεύθυνση που θα χρησιμοποιηθεί για αποστολή, ή κενό αν δεν υπάρχει. */
    val effectiveEmail: String
        get() = when {
            emailPreferred.isNotBlank() -> emailPreferred
            emailAade.isNotBlank() -> emailAade
            else -> emailManual
        }

    /** Τι δείχνουμε στη λίστα. */
    val displayName: String
        get() = listOf(name, firstName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { afm }
}

/**
 * Ένα διαπιστευτήριο, κρυπτογραφημένο.
 *
 * Χωριστός πίνακας αντί για στήλες στον πελάτη: οι πύλες πληθαίνουν (ΓΕΜΗ,
 * ΟΠΕΚΕΠΕ, ΕΡΓΑΝΗ…) και δεν θέλουμε migration για κάθε καινούργια. Επιπλέον,
 * ένα `SELECT * FROM clients` δεν φέρνει ποτέ μυστικά.
 */
@Entity(
    tableName = "credentials",
    primaryKeys = ["clientId", "field"],
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CredentialEntity(
    val clientId: Long,
    /** Όνομα πεδίου, π.χ. `TAXIS_USER`. Βλ. ColumnAliases.Field. */
    val field: String,
    /** Πάντα με πρόθεμα `enc:1:` — βλ. Crypto. */
    val valueEnc: String,
    val updatedAt: Long = 0,
)

/** Ένα ληφθέν έντυπο. */
@Entity(
    tableName = "documents",
    indices = [Index(value = ["clientId"]), Index(value = ["clientId", "fileName"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    /** Το config που το κατέβασε, π.χ. `aade-income`. */
    val configId: String,
    val fileName: String,
    val relativePath: String,
    val year: String = "",
    val bytes: Long = 0,
    val createdAt: Long = 0,
    /** Πότε στάλθηκε με email· 0 = δεν έχει σταλεί. */
    val sentAt: Long = 0,
)

/**
 * Αρχείο δραστηριοτήτων επεξεργασίας (GDPR άρθρο 30).
 *
 * Καταγράφει **τι** έγινε και **σε ποιον**, ποτέ τιμές. Εξάγεται σε CSV.
 */
@Entity(tableName = "audit_log", indices = [Index(value = ["ts"]), Index(value = ["afm"])])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** IMPORT, FETCH, SEND, EXPORT, DELETE, EMAIL_LOOKUP, UNLOCK… */
    val action: String,
    val afm: String = "",
    val detail: String = "",
)

/**
 * Η εντολή/εξουσιοδότηση του πελάτη προς το γραφείο.
 *
 * Ο λογιστής είναι εκτελών την επεξεργασία· η μαζική λήψη προειδοποιεί για
 * πελάτες χωρίς καταγεγραμμένη εντολή.
 */
@Entity(
    tableName = "consents",
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConsentEntity(
    @PrimaryKey val clientId: Long,
    val grantedAt: Long,
    val note: String = "",
)

/**
 * Μία αποστολή email — η βάση του ημερολογίου αποστολών.
 *
 * Κρατιέται **ξεχωριστά** από το `audit_log`: το audit είναι νομικό αρχείο
 * (άρθρο 30) που δεν διαγράφεται και δεν φιλτράρεται, ενώ αυτό είναι εργαλείο
 * δουλειάς — «τι έστειλα τον Μάρτιο και σε ποιον». Διαφορετικός σκοπός,
 * διαφορετικός κύκλος ζωής.
 *
 * Καταγράφονται και οι **αποτυχημένες** αποστολές: μια αποστολή που δεν έφτασε
 * είναι ακριβώς αυτό που θέλει να δει ο λογιστής στο ημερολόγιο.
 */
@Entity(
    tableName = "sends",
    indices = [Index(value = ["sentAt"]), Index(value = ["clientId"]), Index(value = ["afm"])],
)
data class SendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    /** Αντιγράφονται τη στιγμή της αποστολής: ο πελάτης μπορεί να διαγραφεί αργότερα. */
    val afm: String,
    val clientName: String,
    val toEmail: String,
    val subject: String,
    /** DOCUMENTS = φορολογικά έντυπα · CREDENTIALS = οι κωδικοί του ίδιου του πελάτη. */
    val kind: String,
    /** Ονόματα συνημμένων ή περιγραφή περιεχομένου, ένα ανά γραμμή. */
    val items: String = "",
    val itemCount: Int = 0,
    val sentAt: Long,
    /** SENT ή FAILED. */
    val status: String = STATUS_SENT,
    val error: String = "",
) {
    val failed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val KIND_DOCUMENTS = "DOCUMENTS"
        const val KIND_CREDENTIALS = "CREDENTIALS"
    }
}

/**
 * Η αντιστοίχιση ενός τοπικού αρχείου με το αντίγραφό του στο Drive.
 *
 * Είναι **cache, όχι αλήθεια**: η αλήθεια είναι ο Drive. Υπάρχει για δύο λόγους,
 * και οι δύο πρακτικοί.
 *
 *  * **Ταχύτητα.** Χωρίς αυτόν, κάθε συγχρονισμός θα ρωτούσε το Drive «υπάρχει
 *    αρχείο με αυτό το όνομα σε αυτόν τον φάκελο;» — μία κλήση δικτύου ανά
 *    αρχείο, πριν καν αρχίσει το ανέβασμα.
 *  * **Ορθότητα.** Ο Drive επιτρέπει δύο αρχεία με το ίδιο όνομα στον ίδιο
 *    φάκελο. Χωρίς το αποθηκευμένο id, κάθε επανασυγχρονισμός θα άφηνε
 *    διπλότυπα αντί να ενημερώσει το υπάρχον.
 *
 * Το κλειδί είναι η **τοπική σχετική διαδρομή**, η ίδια που κρατά και το
 * documents.relativePath — έτσι η αντιστοίχιση επιβιώνει από μετονομασία
 * πελάτη ή αλλαγή δομής φακέλων στο Drive.
 */
@Entity(tableName = "drive_files")
data class DriveFileEntity(
    @PrimaryKey val relativePath: String,
    val driveId: String,
    val remoteName: String,
    val parentId: String,
    val bytes: Long,
    val syncedAt: Long,
)

/**
 * Το ημερολόγιο μιας εκτέλεσης διαδικασίας.
 *
 * Ο runner γράφει `run.log` και δεκάδες dumps σελίδων δίπλα στα PDF. Στο κινητό
 * αυτά δεν έχουν θέση ανάμεσα στα έγγραφα του πελάτη — οι γραμμές του log
 * καταλήγουν εδώ (καθαρισμένες από τον Redactor) και τα dumps δεν γράφονται
 * καθόλου όταν τα διαγνωστικά είναι κλειστά.
 */
@Entity(tableName = "run_logs", indices = [Index(value = ["startedAt"]), Index(value = ["afm"])])
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val afm: String,
    val configId: String,
    val startedAt: Long,
    val durationMs: Long,
    val ok: Boolean,
    val reason: String = "",
    val fileCount: Int = 0,
    /** Οι γραμμές του log, μία ανά γραμμή. Ποτέ με μυστικά. */
    val lines: String = "",
)
