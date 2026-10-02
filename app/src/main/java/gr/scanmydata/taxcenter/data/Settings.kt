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

    /** Υπογραφή που μπαίνει στο τέλος κάθε email, όταν δεν υπάρχει ειδική. */
    var signature: String
        get() = prefs.getString(KEY_SIGNATURE, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SIGNATURE, v).apply()

    /**
     * Υπογραφή μόνο για το email με τα **στοιχεία του πελάτη**.
     *
     * Τα δύο μηνύματα δεν κλείνουν το ίδιο. Στο email των εντύπων ταιριάζει
     * «είμαστε στη διάθεσή σας για διευκρινίσεις»· σε αυτό που κουβαλά κωδικούς
     * χρειάζεται κάτι άλλο — πού να απευθυνθεί αν κάτι δεν δουλεύει, και ρητή
     * σύσταση να μη γίνει προώθηση του μηνύματος.
     *
     * Κενό = χρησιμοποιείται η κοινή [signature].
     */
    var signatureCredentials: String
        get() = prefs.getString(KEY_SIGNATURE_CREDENTIALS, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SIGNATURE_CREDENTIALS, v).apply()

    /** Υπογραφή μόνο για το email με τα φορολογικά έντυπα. Κενό = η κοινή. */
    var signatureDocuments: String
        get() = prefs.getString(KEY_SIGNATURE_DOCUMENTS, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SIGNATURE_DOCUMENTS, v).apply()

    /**
     * Η υπογραφή που ισχύει για ένα είδος αποστολής.
     *
     * Το [kind] είναι `SendEntity.KIND_CREDENTIALS` ή `KIND_DOCUMENTS`. Άγνωστο
     * είδος παίρνει την κοινή υπογραφή — μια νέα κατηγορία email δεν πρέπει να
     * φύγει ανυπόγραφη επειδή κανείς δεν θυμήθηκε να προσθέσει ρύθμιση.
     */
    fun signatureFor(kind: String): String = when (kind) {
        "CREDENTIALS" -> signatureCredentials.ifBlank { signature }
        "DOCUMENTS" -> signatureDocuments.ifBlank { signature }
        else -> signature
    }

    /** Το γραφείο, όπως εμφανίζεται στα email. */
    var officeName: String
        get() = prefs.getString(KEY_OFFICE_NAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_OFFICE_NAME, v).apply()

    /**
     * Τι κάνει η εφαρμογή με το Google Drive.
     *
     * Αποθηκεύεται ως όνομα και όχι ως αριθμός: μια μελλοντική τιμή στη μέση της
     * enum δεν πρέπει να αλλάξει σιωπηλά τη ρύθμιση κάποιου. Άγνωστη τιμή
     * γυρίζει σε `BACKUP`, την πιο συντηρητική που κάνει κάτι χρήσιμο.
     */
    var driveMode: gr.scanmydata.taxcenter.google.DriveSync.Mode
        get() = runCatching {
            gr.scanmydata.taxcenter.google.DriveSync.Mode.valueOf(
                prefs.getString(KEY_DRIVE_MODE, null)
                    ?: gr.scanmydata.taxcenter.google.DriveSync.Mode.BACKUP.name,
            )
        }.getOrDefault(gr.scanmydata.taxcenter.google.DriveSync.Mode.BACKUP)
        set(v) = prefs.edit().putString(KEY_DRIVE_MODE, v.name).apply()

    /**
     * Πού ζουν τα αρχεία μέσα στο Drive — διαδρομή με `/`, π.χ.
     * `Γραφείο/Πελάτες/TaxCenter`.
     *
     * Ο χρήστης τη δηλώνει στο στήσιμο και η εφαρμογή **φτιάχνει** ό,τι λείπει.
     * Δεν υπάρχει επιλογή από δέντρο φακέλων, και δεν είναι παράλειψη: το scope
     * είναι `drive.file`, δηλαδή η εφαρμογή βλέπει **μόνο ό,τι έφτιαξε η ίδια**
     * και δεν μπορεί καν να απαριθμήσει τον υπόλοιπο Drive. Αυτός ακριβώς είναι
     * ο λόγος που δεν ζητήθηκε ευρύτερο δικαίωμα: ένας λογαριασμός με
     * φορολογικά τρίτων δεν ανοίγει ολόκληρος σε μια εφαρμογή για να δείξει
     * έναν επιλογέα φακέλων.
     *
     * Κενή τιμή σημαίνει την προεπιλογή — ποτέ τη ρίζα του Drive.
     */
    var driveFolderPath: String
        get() = prefs.getString(KEY_DRIVE_FOLDER, null)?.takeIf { it.isNotBlank() }
            ?: gr.scanmydata.taxcenter.google.DriveSync.ROOT
        set(v) = prefs.edit()
            .putString(KEY_DRIVE_FOLDER, gr.scanmydata.taxcenter.google.DriveSync.normalisePath(v))
            .apply()

    /** Έχει απαντήσει ο χρήστης πού θέλει τον φάκελο; Ρωτιέται μία φορά. */
    var driveFolderChosen: Boolean
        get() = prefs.getBoolean(KEY_DRIVE_FOLDER_CHOSEN, false)
        set(v) = prefs.edit().putBoolean(KEY_DRIVE_FOLDER_CHOSEN, v).apply()

    /**
     * Έχει ολοκληρωθεί η πρώτη εκκίνηση;
     *
     * Στο πρώτο άνοιγμα δεν υπάρχει τίποτα να προστατευτεί — ούτε πελάτης, ούτε
     * κωδικός, ούτε έγγραφο. Ένα prompt δακτυλικού αποτυπώματος πριν καν δει ο
     * χρήστης τι είναι η εφαρμογή δεν προσθέτει ασφάλεια, μόνο εμπόδιο. Το
     * κλείδωμα ενεργοποιείται από τη δεύτερη εκκίνηση.
     */
    var firstRunCompleted: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, v).apply()

    /** Έχει δει ο χρήστης τη σύντομη ξενάγηση; */
    var tourSeen: Boolean
        get() = prefs.getBoolean(KEY_TOUR_SEEN, false)
        set(v) = prefs.edit().putBoolean(KEY_TOUR_SEEN, v).apply()

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

    /**
     * Η οθόνη προόδου δείχνει μία κάρτα ανά **πελάτη** αντί για μία ανά έντυπο.
     *
     * Σε παρτίδα «5 έντυπα × 40 πελάτες» οι 200 κάρτες δεν διαβάζονται σε
     * τηλέφωνο. Ομαδοποιημένη, η ίδια παρτίδα είναι 40 γραμμές που λένε «3
     * έτοιμα, 1 χωρίς έντυπο, 1 απέτυχε».
     *
     * Ρύθμιση και όχι σταθερή συμπεριφορά: με έναν-δυο πελάτες η αναλυτική
     * λίστα είναι πιο χρήσιμη, γιατί δείχνει κατευθείαν ποιο έντυπο έσκασε.
     */
    var groupFetchByClient: Boolean
        get() = prefs.getBoolean(KEY_GROUP_FETCH, false)
        set(v) = prefs.edit().putBoolean(KEY_GROUP_FETCH, v).apply()

    /**
     * Ποιο θέμα βλέπει ο χρήστης. Αποθηκεύεται με το όνομα της τιμής, ώστε μια
     * μελλοντική προσθήκη ή αφαίρεση να μη μετατοπίζει τις υπόλοιπες.
     */
    var themeVariant: gr.scanmydata.taxcenter.ui.theme.ThemeVariant
        get() = runCatching {
            gr.scanmydata.taxcenter.ui.theme.ThemeVariant.valueOf(
                prefs.getString(KEY_THEME, null)
                    ?: gr.scanmydata.taxcenter.ui.theme.ThemeVariant.CLASSIC.name,
            )
        }.getOrDefault(gr.scanmydata.taxcenter.ui.theme.ThemeVariant.CLASSIC)
        set(v) = prefs.edit().putString(KEY_THEME, v.name).apply()

    /**
     * Τα **αγαπημένα** έντυπα του καταλόγου λήψης, ως `id` του
     * [gr.scanmydata.taxcenter.engine.DocumentCatalog].
     *
     * Μόνο αναγνωριστικά εντύπων — κανένα στοιχείο πελάτη — γι' αυτό ζουν στις
     * απλές προτιμήσεις και όχι στην κρυπτογραφημένη βάση. Η σειρά εμφάνισης
     * είναι του καταλόγου, όχι της προσθήκης: έτσι το Ε1 είναι πάντα πριν το Ε9.
     */
    var favoriteDocuments: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITE_DOCUMENTS, emptySet())?.toSet().orEmpty()
        // Αντίγραφο και όχι το ίδιο set: το SharedPreferences δεν εγγυάται τίποτα
        // για set που τροποποιείται αφού του δοθεί.
        set(v) = prefs.edit().putStringSet(KEY_FAVORITE_DOCUMENTS, HashSet(v)).apply()

    // ------------------------------------------- αυτόματη ενημέρωση οφειλών

    /**
     * Κάθε πότε ενημερώνονται μόνες τους οι καρτέλες οφειλών: `OFF`, `DAILY`,
     * `WEEKLY` ή `MONTHLY` — βλ. `DebtWatch.Frequency`. **Κλειστό εξ ορισμού**:
     * κάθε εκτέλεση είναι πραγματική σύνδεση στο TAXISnet με τους κωδικούς του
     * πελάτη, και αυτό δεν ξεκινά χωρίς να το ζητήσει κάποιος.
     */
    var debtWatchFrequency: String
        get() = prefs.getString(KEY_DEBT_WATCH_FREQUENCY, "OFF").orEmpty()
        set(v) = prefs.edit().putString(KEY_DEBT_WATCH_FREQUENCY, v).apply()

    /** Η ώρα (0-23, ώρα Αθηνών) γύρω από την οποία ξεκινά η ενημέρωση. */
    var debtWatchHour: Int
        get() = prefs.getInt(KEY_DEBT_WATCH_HOUR, 8).coerceIn(0, 23)
        set(v) = prefs.edit().putInt(KEY_DEBT_WATCH_HOUR, v.coerceIn(0, 23)).apply()

    /**
     * Ποιοι πελάτες παρακολουθούνται, ως `id` της βάσης.
     *
     * Μόνο αριθμοί γραμμών — ούτε ΑΦΜ ούτε ονόματα — γι' αυτό ζουν εδώ και όχι
     * στην κρυπτογραφημένη βάση. Ένα `id` πελάτη που διαγράφηκε απλώς αγνοείται.
     */
    var debtWatchClients: Set<Long>
        get() = prefs.getStringSet(KEY_DEBT_WATCH_CLIENTS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit()
            .putStringSet(KEY_DEBT_WATCH_CLIENTS, v.map { it.toString() }.toHashSet())
            .apply()

    /** Πότε τελείωσε η τελευταία αυτόματη ενημέρωση. `0` = ποτέ. */
    var debtWatchLastRun: Long
        get() = prefs.getLong(KEY_DEBT_WATCH_LAST_RUN, 0L)
        set(v) = prefs.edit().putLong(KEY_DEBT_WATCH_LAST_RUN, v).apply()

    /** Μία γραμμή για το πώς πήγε — πλήθη μόνο, ποτέ ονόματα πελατών. */
    var debtWatchLastSummary: String
        get() = prefs.getString(KEY_DEBT_WATCH_LAST_SUMMARY, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_DEBT_WATCH_LAST_SUMMARY, v).apply()

    // ------------------------------------- παρακολούθηση αιτημάτων ΑΑΔΕ

    /**
     * Καθημερινός έλεγχος για απαντήσεις στα «Αιτήματά μου» της ΑΑΔΕ.
     * **Κλειστό εξ ορισμού**, για τον ίδιο λόγο με τις οφειλές: είναι σύνδεση
     * στο TAXISnet με κωδικούς πελάτη, χωρίς άνθρωπο μπροστά.
     */
    var requestWatchDaily: Boolean
        get() = prefs.getBoolean(KEY_REQUEST_WATCH_DAILY, false)
        set(v) = prefs.edit().putBoolean(KEY_REQUEST_WATCH_DAILY, v).apply()

    /**
     * Ώρα Αθηνών. Μία ώρα μετά τις οφειλές εξ ορισμού: οι δύο εργασίες
     * μοιράζονται την ίδια ουρά, και όποια βρει την άλλη να τρέχει περιμένει.
     */
    var requestWatchHour: Int
        get() = prefs.getInt(KEY_REQUEST_WATCH_HOUR, 9).coerceIn(0, 23)
        set(v) = prefs.edit().putInt(KEY_REQUEST_WATCH_HOUR, v.coerceIn(0, 23)).apply()

    /** Ποιοι πελάτες παρακολουθούνται, ως `id` της βάσης — βλ. [debtWatchClients]. */
    var requestWatchClients: Set<Long>
        get() = longs(KEY_REQUEST_WATCH_CLIENTS)
        set(v) = putLongs(KEY_REQUEST_WATCH_CLIENTS, v)

    /**
     * Πελάτες με απάντηση που δεν έχει ανοίξει ακόμη κανείς.
     *
     * Από εδώ ξέρει η λίστα πελατών ποιους να δείξει μετά την ειδοποίηση. Και
     * εδώ μόνο `id`: η ίδια η ειδοποίηση του Android δεν γράφει ονόματα.
     */
    var requestWatchFresh: Set<Long>
        get() = longs(KEY_REQUEST_WATCH_FRESH)
        set(v) = putLongs(KEY_REQUEST_WATCH_FRESH, v)

    var requestWatchLastRun: Long
        get() = prefs.getLong(KEY_REQUEST_WATCH_LAST_RUN, 0L)
        set(v) = prefs.edit().putLong(KEY_REQUEST_WATCH_LAST_RUN, v).apply()

    /** Πλήθη μόνο, ποτέ ονόματα πελατών. */
    var requestWatchLastSummary: String
        get() = prefs.getString(KEY_REQUEST_WATCH_LAST_SUMMARY, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_REQUEST_WATCH_LAST_SUMMARY, v).apply()

    private fun longs(key: String): Set<Long> =
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    private fun putLongs(key: String, value: Set<Long>) =
        prefs.edit().putStringSet(key, value.map { it.toString() }.toHashSet()).apply()

    private companion object {
        const val KEY_REQUEST_WATCH_DAILY = "request_watch_daily"
        const val KEY_REQUEST_WATCH_HOUR = "request_watch_hour"
        const val KEY_REQUEST_WATCH_CLIENTS = "request_watch_clients"
        const val KEY_REQUEST_WATCH_FRESH = "request_watch_fresh"
        const val KEY_REQUEST_WATCH_LAST_RUN = "request_watch_last_run"
        const val KEY_REQUEST_WATCH_LAST_SUMMARY = "request_watch_last_summary"
        const val KEY_DEBT_WATCH_FREQUENCY = "debt_watch_frequency"
        const val KEY_DEBT_WATCH_HOUR = "debt_watch_hour"
        const val KEY_DEBT_WATCH_CLIENTS = "debt_watch_clients"
        const val KEY_DEBT_WATCH_LAST_RUN = "debt_watch_last_run"
        const val KEY_DEBT_WATCH_LAST_SUMMARY = "debt_watch_last_summary"
        const val KEY_FAVORITE_DOCUMENTS = "favorite_documents"
        const val KEY_THEME = "theme_variant"
        const val KEY_GROUP_FETCH = "group_fetch_by_client"
        const val KEY_DRIVE_MODE = "drive_mode"
        const val KEY_FIRST_RUN_DONE = "first_run_completed"
        const val KEY_TOUR_SEEN = "tour_seen"
        const val KEY_LOCK = "lock_enabled"
        const val KEY_LOCK_GRACE = "lock_grace_seconds"
        const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        const val KEY_RETENTION_MONTHS = "retention_months"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_INCLUDE_PASSWORDS = "include_passwords_in_client_email"
        const val KEY_SENDER_EMAIL = "sender_email"
        const val KEY_GOOGLE_CONNECTED = "google_connected"
        const val KEY_SIGNATURE = "signature"
        const val KEY_SIGNATURE_CREDENTIALS = "signature_credentials"
        const val KEY_SIGNATURE_DOCUMENTS = "signature_documents"
        const val KEY_OFFICE_NAME = "office_name"
        const val KEY_DRIVE_FOLDER = "drive_folder_path"
        const val KEY_DRIVE_FOLDER_CHOSEN = "drive_folder_chosen"
    }
}
