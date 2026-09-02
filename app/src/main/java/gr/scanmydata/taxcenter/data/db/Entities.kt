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
    /** Φυσικό Πρόσωπο / Ατομική Επιχείρηση / Νομικό Πρόσωπο — καθορίζει ποια έντυπα ισχύουν. */
    val kind: String = "",
    /** Χρειάζεται για τα logins ΕΦΚΑ/ΑΤΛΑΣ/ΚΕΑΟ. Κρυπτογραφημένο. */
    val amkaEnc: String = "",
    val doy: String = "",
    val active: Boolean = true,

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
