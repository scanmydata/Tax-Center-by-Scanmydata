package gr.scanmydata.taxcenter

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import gr.scanmydata.taxcenter.security.AppLock
import gr.scanmydata.taxcenter.ui.AppContainer
import gr.scanmydata.taxcenter.ui.AppShell
import gr.scanmydata.taxcenter.ui.LockScreen
import gr.scanmydata.taxcenter.ui.SplashScreen
import gr.scanmydata.taxcenter.ui.theme.TaxCenterTheme
import gr.scanmydata.taxcenter.ui.theme.ThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * `FragmentActivity` και όχι `ComponentActivity`: το `BiometricPrompt` του
 * AndroidX το απαιτεί, γιατί εμφανίζεται ως fragment. Δεν φέρνει AppCompat και
 * δεν αλλάζει τίποτα στο Compose.
 */
class MainActivity : FragmentActivity() {

    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyScreenshotPolicy()

        // Στο πρώτο άνοιγμα δεν ζητάμε ταυτοποίηση: δεν υπάρχει ακόμη ούτε
        // πελάτης ούτε κωδικός να προστατευτεί, και ένα prompt δακτυλικού
        // αποτυπώματος πριν καν δει ο χρήστης τι είναι η εφαρμογή είναι εμπόδιο
        // χωρίς αντίκρισμα. Από τη δεύτερη εκκίνηση ισχύει κανονικά.
        if (!container.settings.firstRunCompleted) {
            AppLock.unlock()
            container.settings.firstRunCompleted = true
        }

        // Το θέμα διαβάζεται **πριν** από το πρώτο σχέδιο, αλλιώς η εφαρμογή
        // ξεκινά στο κλασικό και αλλάζει μπροστά στα μάτια του χρήστη.
        ThemeState.set(container.settings.themeVariant)

        setContent {
            TaxCenterTheme {
                var ready by remember { mutableStateOf(false) }

                // Το πρώτο άνοιγμα της SQLCipher παράγει το κλειδί και τρέχει τα
                // PBKDF2 περάσματα — αισθητός χρόνος σε μεσαία συσκευή, και όχι
                // δουλειά για το κύριο νήμα.
                LaunchedEffect(Unit) {
                    val started = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        runCatching { container.db.openHelper.readableDatabase }
                    }
                    // Κατώφλι εμφάνισης: όταν η βάση ανοίγει σε 80ms, μια οθόνη
                    // που εμφανίζεται και σβήνει διαβάζεται ως τρεμόπαιγμα, όχι
                    // ως υποδοχή.
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed < SPLASH_FLOOR_MS) delay(SPLASH_FLOOR_MS - elapsed)
                    ready = true
                }

                when {
                    !ready -> SplashScreen()
                    AppLock.locked -> LockScreen(onUnlocked = { })
                    else -> AppShell(container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(
            enabled = container.settings.lockEnabled,
            graceSeconds = container.settings.lockGraceSeconds,
        )
        // Η ρύθμιση μπορεί να άλλαξε όσο λείπαμε.
        applyScreenshotPolicy()
    }

    override fun onStop() {
        AppLock.onBackground()
        super.onStop()
    }

    /**
     * `FLAG_SECURE` σε ολόκληρη την εφαρμογή, όχι μόνο στις οθόνες με κωδικούς.
     *
     * Και η λίστα πελατών είναι ευαίσθητη: ΑΦΜ, ονόματα και διευθύνσεις τρίτων.
     * Παράπλευρο όφελος, που μετράει περισσότερο από τα στιγμιότυπα: η μικρογραφία
     * στα «πρόσφατα» δεν δείχνει περιεχόμενο.
     */
    private fun applyScreenshotPolicy() {
        if (container.settings.blockScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private companion object {
        const val SPLASH_FLOOR_MS = 700L
    }
}
