package gr.scanmydata.taxcenter.ui

import androidx.annotation.DrawableRes
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
     * Ένα αρχείο, δύο όψεις: οι **εκτελέσεις** των διαδικασιών και το
     * **αρχείο ενεργειών** του άρθρου 30. Ήταν χωριστές θέσεις· και οι δύο
     * απαντούν στην ίδια ερώτηση («τι έγινε και πότε») και ο χρήστης δεν ήξερε
     * ποια να ανοίξει.
     */
    Logs("logs", "Ιστορικό & αρχείο", R.drawable.ic_menu_logs),
    Help("help", "Οδηγίες χρήσης", R.drawable.ic_menu_help),
    SettingsScreen("settings", "Ρυθμίσεις", R.drawable.ic_menu_settings),
}
