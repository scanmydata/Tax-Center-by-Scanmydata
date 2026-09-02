package gr.scanmydata.taxcenter.engine

import gr.scanmydata.taxcenter.data.ColumnAliases.Field

/**
 * Ποια διαπιστευτήρια θέλει κάθε διαδικασία.
 *
 * Τα configs ζητούν πάντα `user`/`pass`, αλλά **δεν εννοούν το ίδιο πράγμα**:
 *
 *  * οι ΑΑΔΕ και οι μη-μισθωτοί του ΕΦΚΑ θέλουν **TAXISnet**,
 *  * η Οικονομική Καρτέλα Εργοδότη θέλει κωδικούς **ΙΚΑ εργοδότη** — άλλο ζεύγος,
 *    άλλη πύλη, χωρίς GSIS.
 *
 * Αν μπερδευτούν, η σύνδεση αποτυγχάνει με `InvalidCredentials` και μοιάζει με
 * λάθος κωδικό του πελάτη. Γι' αυτό η αντιστοίχιση είναι ρητή και ανά config,
 * όχι μαντεψιά από το όνομα της πύλης.
 *
 * Πηγή: `runner/MANUAL.md`, πίνακας «Πύλες & logins».
 */
object CredentialMap {

    /** Ποιο ζεύγος user/pass χρειάζεται. */
    enum class Login { TAXISNET, IKA_EMPLOYER }

    data class Requirement(
        val login: Login,
        /** Χρειάζεται ΑΦΜ ως ξεχωριστό input (πέρα από το login). */
        val needsAfm: Boolean = false,
        /** Χρειάζεται ΑΜΚΑ — τα logins του νέου ΟΠΣ e-ΕΦΚΑ το ζητούν στη φόρμα ρόλου. */
        val needsAmka: Boolean = false,
    )

    private val TAXIS = Requirement(Login.TAXISNET)
    private val TAXIS_AFM_AMKA = Requirement(Login.TAXISNET, needsAfm = true, needsAmka = true)
    private val IKA = Requirement(Login.IKA_EMPLOYER)

    private val BY_CONFIG: Map<String, Requirement> = mapOf(
        // ΑΑΔΕ — GSIS OAM, μόνο TAXISnet
        "aade-login-check" to TAXIS,
        "aade-income" to TAXIS,
        "aade-debts" to TAXIS,
        "aade-declarations" to TAXIS,
        "aade-general-forms" to TAXIS,
        "aade-fenp" to TAXIS,
        "aade-lease" to TAXIS,
        "aade-property" to TAXIS,
        "aade-registry" to TAXIS,
        "aade-tax-account" to TAXIS,
        "aade-traffic-fees" to TAXIS,
        "aade-enfia" to TAXIS,
        "aade-email" to TAXIS,

        // e-ΕΦΚΑ μη μισθωτοί / ΑΤΛΑΣ / ΚΕΑΟ — TAXISnet + ΑΦΜ + ΑΜΚΑ
        "efka-notices" to TAXIS_AFM_AMKA,
        "efka-teka-certificate" to TAXIS_AFM_AMKA,
        "keao-debts" to TAXIS_AFM_AMKA,
        "atlas-insurance-history" to TAXIS_AFM_AMKA,

        // MyAMKA — TAXISnet, χωρίς άλλα
        "amka-retrieve" to TAXIS,

        // ΕΦΚΑ εργοδότη — κωδικοί ΙΚΑ, ΟΧΙ TAXISnet
        "efka-employer-card" to IKA,
    )

    fun forConfig(configId: String): Requirement? = BY_CONFIG[configId]

    /** Τα πεδία που πρέπει να υπάρχουν αποθηκευμένα για να τρέξει η διαδικασία. */
    fun requiredFields(configId: String): List<Field> {
        val req = forConfig(configId) ?: return emptyList()
        val out = ArrayList<Field>()
        when (req.login) {
            Login.TAXISNET -> { out += Field.TAXIS_USER; out += Field.TAXIS_PASS }
            Login.IKA_EMPLOYER -> { out += Field.IKA_EMPLOYER_USER; out += Field.IKA_EMPLOYER_PASS }
        }
        if (req.needsAmka) out += Field.AMKA
        return out
    }

    /** Φιλική περιγραφή για μηνύματα σφάλματος. */
    fun describe(login: Login): String = when (login) {
        Login.TAXISNET -> "κωδικοί TAXISnet"
        Login.IKA_EMPLOYER -> "κωδικοί ΙΚΑ εργοδότη"
    }
}
