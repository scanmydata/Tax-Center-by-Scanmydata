package gr.scanmydata.taxcenter

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import gr.scanmydata.taxcenter.security.AppLock
import gr.scanmydata.taxcenter.ui.AppContainer
import gr.scanmydata.taxcenter.ui.AppShell
import gr.scanmydata.taxcenter.ui.LockScreen
import gr.scanmydata.taxcenter.ui.theme.TaxCenterTheme

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

        setContent {
            TaxCenterTheme {
                if (AppLock.locked) {
                    LockScreen(onUnlocked = { })
                } else {
                    AppShell(container)
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
}
