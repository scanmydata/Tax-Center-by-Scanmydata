package gr.scanmydata.taxcenter.security

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Η κλειδαριά της εφαρμογής.
 *
 * Είναι σκόπιμα **object** και όχι κάτι που ζει στη σύνθεση: το κλείδωμα πρέπει
 * να επιβιώνει από αλλαγή προσανατολισμού και από ανακατασκευή της Activity,
 * αλλιώς μια περιστροφή θα ξεκλείδωνε την εφαρμογή.
 *
 * Το κλείδωμα **δεν** αντικαθιστά την κρυπτογράφηση της βάσης· είναι άλλο
 * επίπεδο. Η SQLCipher προστατεύει από κλεμμένη ή ξεκλειδωμένη συσκευή που
 * περνά από τα χέρια τρίτου· αυτό εδώ προστατεύει από το κινητό που μένει
 * ξεκλείδωτο πάνω στο γραφείο.
 */
object AppLock {

    /** Ξεκινά κλειδωμένη: η πρώτη οθόνη μετά την εκκίνηση ζητά ταυτοποίηση. */
    var locked by mutableStateOf(true)
        private set

    private var backgroundedAt = 0L

    fun unlock() {
        locked = false
        backgroundedAt = 0L
    }

    fun lockNow() {
        locked = true
    }

    /** Καλείται στο `onStop` της Activity. */
    fun onBackground() {
        if (!locked) backgroundedAt = System.currentTimeMillis()
    }

    /**
     * Καλείται στο `onStart`. Κλειδώνει αν πέρασε η περίοδος χάριτος.
     *
     * Όταν το κλείδωμα είναι σβηστό στις ρυθμίσεις, ξεκλειδώνει ρητά: αλλιώς η
     * εφαρμογή θα έμενε για πάντα στην αρχική κλειδωμένη κατάσταση χωρίς τρόπο
     * να ξεκλειδώσει.
     */
    fun onForeground(enabled: Boolean, graceSeconds: Int) {
        if (!enabled) {
            unlock()
            return
        }
        if (locked) return
        val elapsed = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt != 0L && elapsed > graceSeconds * 1000L) {
            locked = true
        }
    }
}
