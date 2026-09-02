package gr.scanmydata.taxcenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import gr.scanmydata.taxcenter.ui.AppContainer
import gr.scanmydata.taxcenter.ui.AppShell
import gr.scanmydata.taxcenter.ui.theme.TaxCenterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = AppContainer(applicationContext)
        setContent {
            TaxCenterTheme {
                AppShell(container)
            }
        }
    }
}
