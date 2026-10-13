package gr.scanmydata.taxcenter.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gr.scanmydata.taxcenter.R

/**
 * Το μενού της εφαρμογής.
 *
 * Η σειρά ακολουθεί τη ροή της δουλειάς και όχι τη σειρά που γράφτηκαν οι
 * οθόνες: **καταχώρηση → λίστα → λήψη → έγγραφα → αποστολές → αρχείο**. Ο νέος
 * πελάτης είναι πρώτος γιατί είναι η μόνη ενέργεια που γίνεται με τον πελάτη
 * στο τηλέφωνο και δεν πρέπει να ψάχνεται.
 *
 * Τα εικονίδια είναι δικά μας vector drawables (24dp grid, stroke 1.8, round
 * caps), σχεδιασμένα με το γλωσσάρι σχημάτων του φύλλου εικονιδίων που δόθηκε.
 *
 * Γιατί όχι το ίδιο το φύλλο: τα glyphs του είναι ~44px και είναι JPEG. Για 24dp
 * σε xxxhdpi χρειάζονται 96px — η μεγέθυνση θα έβγαζε θολά εικονίδια, και τα
 * artifacts του JPEG γύρω από τις λεπτές γραμμές θα φαίνονταν. Το φύλλο μένει
 * στο `branding/` ως αναφορά σχεδίασης.
 *
 * Ως vector: ένα αρχείο για κάθε πυκνότητα οθόνης, και ο χρωματισμός γίνεται από
 * το θέμα — άρα δουλεύουν σωστά σε ανοιχτό και σκοτεινό.
 */
/**
 * Οθόνη που ζητήθηκε **απ' έξω** — σήμερα μόνο από την ειδοποίηση των αιτημάτων.
 *
 * `object` για τον ίδιο λόγο με το `TourState`: το αίτημα φτάνει στο
 * `MainActivity` πριν υπάρξει σύνθεση, και συχνά πριν ξεκλειδώσει η εφαρμογή.
 * Μένει εδώ ώσπου το [AppShell] να μπορεί να πλοηγηθεί, και καταναλώνεται μία φορά.
 */
object Launch {
    var route by mutableStateOf<String?>(null)
        private set

    fun request(target: String) {
        route = target
    }

    fun consume() {
        route = null
    }
}

enum class Destination(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int,
) {
    /**
     * Καταχώρηση πελατών — χειροκίνητα **ή** από Excel, στην ίδια οθόνη.
     *
     * Ήταν δύο θέσεις μενού. Είναι όμως μία δουλειά με δύο αφετηρίες, και ο
     * διαχωρισμός έκανε τον χρήστη να διαλέγει διαδρομή πριν καταλάβει ότι
     * καταλήγουν στο ίδιο σημείο.
     */
    NewClient("client-new", "Νέος πελάτης", R.drawable.ic_menu_client_new),
    Clients("clients", "Πελάτες", R.drawable.ic_menu_clients),
    Fetch("fetch", "Λήψη εντύπων", R.drawable.ic_menu_fetch),
    Documents("documents", "Έγγραφα", R.drawable.ic_menu_documents),
    Calendar("calendar", "Ημερολόγιο αποστολών", R.drawable.ic_menu_calendar),

    /**
     * Τα αιτήματα **όλων** των πελατών προς την ΑΑΔΕ, σε μία λίστα. Η καρτέλα
     * του πελάτη δείχνει τα δικά του· εδώ φαίνεται τι περιμένει το γραφείο.
     */
    Requests("requests", "Αιτήματα ΑΑΔΕ", R.drawable.ic_menu_requests),

    Help("help", "Οδηγίες χρήσης", R.drawable.ic_menu_help),
    SettingsScreen("settings", "Ρυθμίσεις", R.drawable.ic_menu_settings),
}
