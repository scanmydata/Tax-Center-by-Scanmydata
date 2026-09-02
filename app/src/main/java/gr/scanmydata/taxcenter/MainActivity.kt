package gr.scanmydata.taxcenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import gr.scanmydata.taxcenter.engine.ConfigInfo
import gr.scanmydata.taxcenter.engine.EngineAssets
import gr.scanmydata.taxcenter.ui.theme.TaxCenterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TaxCenterTheme {
                ProcessCatalogScreen()
            }
        }
    }
}

/**
 * Προσωρινή αρχική οθόνη: ο κατάλογος των διαδικασιών που ξέρει ο engine.
 *
 * Χρησιμεύει ως έλεγχος ότι τα assets του engine φορτώνονται σωστά στη
 * συσκευή. Αντικαθίσταται από τη λίστα πελατών μόλις μπει η βάση.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessCatalogScreen() {
    val context = LocalContext.current
    val configs: List<ConfigInfo> = remember { EngineAssets(context).catalog() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResourceAppName()) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding() + 12.dp,
                bottom = inner.calculateBottomPadding() + 12.dp,
                start = 12.dp,
                end = 12.dp,
            ),
        ) {
            items(configs, key = { it.id }) { cfg ->
                Card(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(cfg.title.ifBlank { cfg.id }, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                append(cfg.portal)
                                if (cfg.needsBrowser) append("  ·  χρειάζεται browser")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stringResourceAppName(): String =
    androidx.compose.ui.res.stringResource(R.string.app_name_long)
