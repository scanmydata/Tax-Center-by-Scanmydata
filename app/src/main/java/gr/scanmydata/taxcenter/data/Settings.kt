package gr.scanmydata.taxcenter.data

import android.content.Context

/**
 * Ρυθμίσεις της εφαρμογής.
 *
 * Απλό `SharedPreferences` — τίποτα εδώ δεν είναι μυστικό. Οι κωδικοί ζουν στη
 * βάση (SQLCipher + AES ανά τιμή) και τα κλειδιά στο Keystore.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("taxcenter", Context.MODE_PRIVATE)

    /**
     * Κρατά τα dumps σελίδων και το `run.log` του runner στον δίσκο.
     *
     * **Κλειστό εξ ορισμού.** Τα dumps είναι ολόκληρες σελίδες ΑΑΔΕ με προσωπικά
     * δεδομένα σε καθαρό κείμενο· δεν έχουν λόγο να μένουν στη συσκευή. Οι
     * γραμμές του log καταγράφονται πάντα στο `run_logs`, καθαρισμένες.
     */
    var diagnostics: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTICS, false)
        set(v) = prefs.edit().putBoolean(KEY_DIAGNOSTICS, v).apply()

    /**
     * Να συμπεριλαμβάνονται **κωδικοί** στο email που στέλνει στον πελάτη τα
     * στοιχεία του.
     *
     * **Κλειστό εξ ορισμού, και σκόπιμα.** Το email δεν είναι ασφαλές κανάλι:
     * περνά από servers τρίτων, μένει σε γραμματοκιβώτια για χρόνια, και συχνά
     * συγχρονίζεται σε συσκευές που ο πελάτης δεν ελέγχει. ΑΦΜ και ΑΜΚΑ είναι
     * στοιχεία ταυτοποίησης· ο κωδικός TAXISnet δίνει πλήρη πρόσβαση στη
     * φορολογική εικόνα του ανθρώπου.
     *
     * Όταν ανοίξει, η οθόνη αποστολής το δηλώνει ρητά σε κάθε αποστολή.
     */
    var includePasswordsInClientEmail: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_PASSWORDS, false)
        set(v) = prefs.edit().putBoolean(KEY_INCLUDE_PASSWORDS, v).apply()

    /** Ο λογαριασμός Google που στέλνει. Γεμίζει μετά τη σύνδεση. */
    var senderEmail: String
        get() = prefs.getString(KEY_SENDER_EMAIL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SENDER_EMAIL, v).apply()

    var googleConnected: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_CONNECTED, false)
        set(v) = prefs.edit().putBoolean(KEY_GOOGLE_CONNECTED, v).apply()

    /** Υπογραφή που μπαίνει στο τέλος κάθε email. */
    var signature: String
        get() = prefs.getString(KEY_SIGNATURE, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SIGNATURE, v).apply()

    /** Το γραφείο, όπως εμφανίζεται στα email. */
    var officeName: String
        get() = prefs.getString(KEY_OFFICE_NAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_OFFICE_NAME, v).apply()

    /**
     * Ξεκλείδωμα με βιομετρικά ή τον κωδικό της συσκευής.
     *
     * **Ανοιχτό εξ ορισμού.** Η βάση είναι κρυπτογραφημένη at rest, αλλά αυτό
     * προστατεύει από κλεμμένη συσκευή — όχι από ξεκλείδωτη συσκευή στο γραφείο.
     */
    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_LOCK, v).apply()

    /**
     * Πόσα δευτερόλεπτα στο παρασκήνιο πριν κλειδώσει.
     *
     * Δεν είναι μηδέν επίτηδες: η ροή «άνοιξε το Gmail, δες κάτι, γύρνα πίσω»
     * είναι συνεχής, και ένα κλείδωμα σε κάθε εναλλαγή θα οδηγούσε τον χρήστη να
     * σβήσει τη ρύθμιση — που είναι χειρότερο από ένα μικρό παράθυρο χάριτος.
     */
    var lockGraceSeconds: Int
        get() = prefs.getInt(KEY_LOCK_GRACE, 60)
        set(v) = prefs.edit().putInt(KEY_LOCK_GRACE, v).apply()

    /**
     * Αποκλεισμός στιγμιότυπων οθόνης και καταγραφής (`FLAG_SECURE`).
     *
     * Ανοιχτό εξ ορισμού: κάθε οθόνη εδώ δείχνει είτε κωδικούς είτε φορολογικά
     * στοιχεία τρίτων.
     */
    var blockScreenshots: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, true)
        set(v) = prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, v).apply()

    /**
     * Μήνες διατήρησης των ληφθέντων PDF· 0 = χωρίς αυτόματη διαγραφή.
     *
     * Προεπιλογή 24 μήνες: αρκετά για τον έλεγχο μιας χρήσης, χωρίς να μένουν
     * φορολογικά έντυπα τρίτων στη συσκευή για πάντα (αρχή του περιορισμού της
     * περιόδου αποθήκευσης, άρθρο 5 παρ. 1 στοιχ. ε).
     */
    var retentionMonths: Int
        get() = prefs.getInt(KEY_RETENTION_MONTHS, 24)
        set(v) = prefs.edit().putInt(KEY_RETENTION_MONTHS, v).apply()

    private companion object {
        const val KEY_LOCK = "lock_enabled"
        const val KEY_LOCK_GRACE = "lock_grace_seconds"
        const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        const val KEY_RETENTION_MONTHS = "retention_months"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_INCLUDE_PASSWORDS = "include_passwords_in_client_email"
        const val KEY_SENDER_EMAIL = "sender_email"
        const val KEY_GOOGLE_CONNECTED = "google_connected"
        const val KEY_SIGNATURE = "signature"
        const val KEY_OFFICE_NAME = "office_name"
    }
}
