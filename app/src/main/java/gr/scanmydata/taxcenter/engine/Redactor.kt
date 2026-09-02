package gr.scanmydata.taxcenter.engine

/**
 * Καθαρίζει μυστικά από κάθε κείμενο που πάει σε log.
 *
 * Ο engine λογάρει URL, ονόματα πεδίων και ενίοτε αποσπάσματα σελίδων. Ένας
 * κωδικός TAXISnet που ξέφυγε σε log **ξέφυγε για πάντα** — τα logs
 * αντιγράφονται, στέλνονται σε support, μπαίνουν σε backup.
 *
 * Τι κόβεται πάντα: κωδικοί, κλειδάριθμοι, API keys, tokens, ΑΜΚΑ.
 *
 * Τι **δεν** κόβεται: το ΑΦΜ. Είναι προσωπικό δεδομένο, αλλά χωρίς αυτό τα logs
 * γίνονται άχρηστα για διάγνωση («ποιος πελάτης απέτυχε;»), και τα διαγνωστικά
 * είναι εξ ορισμού απενεργοποιημένα και αποθηκεύονται app-private. Το ισοζύγιο
 * είναι συνειδητό και τεκμηριώνεται στο docs/privacy-policy.md.
 */
object Redactor {

    private const val MASK = "«κρυφό»"

    /** Ονόματα πεδίων/παραμέτρων που δεν επιτρέπεται ποτέ να φανούν. */
    private val SECRET_KEYS = listOf(
        "password", "passwd", "pwd", "pass",
        "j_password",
        "secret", "token", "access_token", "refresh_token", "id_token",
        "apikey", "api_key", "api-key",
        "subscription_key", "subscription-key", "ocp-apim-subscription-key",
        "aade-user-id",
        "klidarithmos", "kleidarithmos",
        "συνθηματικό", "συνθηματικο", "κωδικός", "κωδικος", "κλειδάριθμος", "κλειδαριθμος",
    )

    /**
     * `password=x`, `password: x`, `"password":"x"`.
     *
     * Τα όρια λέξης γράφονται ρητά ως `[\p{L}\p{N}_]` αντί για `\b`: στη Java το
     * `\b` βασίζεται στο `\w` = `[a-zA-Z0-9_]`, οπότε τα ελληνικά κλειδιά
     * («Συνθηματικό», «Κλειδάριθμος») δεν θα ταίριαζαν **ποτέ**.
     */
    // (?iu): case-insensitive ΚΑΙ Unicode-aware. Το σκέτο CASE_INSENSITIVE της
    // Java είναι ASCII-only — χωρίς το `u`, το «Συνθηματικό» με κεφαλαίο Σ δεν
    // θα ταίριαζε με το πεζό κλειδί του καταλόγου.
    private val KEY_VALUE = Regex(
        """(?iu)(?<![\p{L}\p{N}_])(""" +
            SECRET_KEYS.joinToString("|") { Regex.escape(it) } +
            """)(?![\p{L}\p{N}_])\s*["']?\s*[:=]\s*["']?([^\s&"',;}<]{1,200})""",
    )

    /** `Authorization: Bearer …`, `Authorization: ctaf2 …` (MyAMKA) */
    private val AUTH_HEADER = Regex("""(?i)(authorization\s*:\s*)(\S+\s+)?(\S{8,})""")

    /** myDATA subscription keys: ακριβώς 32 hex. */
    private val HEX32 = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])""")

    /** ΑΜΚΑ: 11 ψηφία. Κρατάμε τα 2 πρώτα για συσχέτιση. */
    private val AMKA = Regex("""(?<!\d)(\d{2})\d{9}(?!\d)""")

    /**
     * Κλειδάριθμος TAXISnet και παρόμοια: γράμματα, μετά ψηφία, μετά αλφαριθμητικά
     * (π.χ. `ab123456c789`). Δεν πιάνει URL τμήματα όπως `year2025-income`, γιατί
     * απαιτεί τουλάχιστον 4 αλφαριθμητικά μετά τα ψηφία χωρίς διαχωριστικό.
     */
    private val LONG_TOKEN = Regex("""(?<![\p{L}\p{N}_-])[a-z]{2,}\d{4,}[a-z\d]{4,}(?![\p{L}\p{N}_-])""")

    fun scrub(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var s: String = text
        s = KEY_VALUE.replace(s) { m -> "${m.groupValues[1]}=$MASK" }
        s = AUTH_HEADER.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}$MASK" }
        s = HEX32.replace(s, MASK)
        s = AMKA.replace(s) { m -> "${m.groupValues[1]}*********" }
        s = LONG_TOKEN.replace(s, MASK)
        return s
    }

    /**
     * Μασκάρισμα για εμφάνιση στην οθόνη (preview εισαγωγής, καρτέλα πελάτη).
     * Δείχνει τους 2 πρώτους χαρακτήρες, ώστε ο λογιστής να αναγνωρίζει την τιμή
     * χωρίς να την αποκαλύπτει σε όποιον κοιτά την οθόνη.
     */
    fun mask(secret: String?): String {
        if (secret.isNullOrEmpty()) return ""
        if (secret.length <= 2) return "*".repeat(secret.length)
        return secret.take(2) + "*".repeat(minOf(secret.length - 2, 8))
    }
}
