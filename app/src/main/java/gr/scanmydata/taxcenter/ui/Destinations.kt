package gr.scanmydata.taxcenter.ui

import androidx.annotation.DrawableRes
import gr.scanmydata.taxcenter.R

/**
 * Το μενού της εφαρμογής.
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
    Clients("clients", "Πελάτες", R.drawable.ic_menu_clients),

    /**
     * Χωριστή θέση μενού από τη λίστα, όχι κουμπί μέσα της.
     *
     * Είναι δύο διαφορετικές δουλειές: «βρες τον Παπαδόπουλο» και «καταχώρησε
     * καινούργιο». Η δεύτερη γίνεται με τον πελάτη στο τηλέφωνο και τους
     * κωδικούς στο χέρι, και δεν πρέπει να περνά μέσα από μια λίστα 400
     * ονομάτων για να βρεθεί ένα κουμπί στη γωνία.
     */
    NewClient("client-new", "Νέος πελάτης", R.drawable.ic_menu_client_new),
    Import("import", "Εισαγωγή από Excel", R.drawable.ic_menu_import),
    Fetch("fetch", "Λήψη εντύπων", R.drawable.ic_menu_fetch),
    Documents("documents", "Έγγραφα", R.drawable.ic_menu_documents),
    Calendar("calendar", "Ημερολόγιο αποστολών", R.drawable.ic_menu_calendar),
    Logs("logs", "Ιστορικό εκτελέσεων", R.drawable.ic_menu_logs),
    SettingsScreen("settings", "Ρυθμίσεις", R.drawable.ic_menu_settings),
}
