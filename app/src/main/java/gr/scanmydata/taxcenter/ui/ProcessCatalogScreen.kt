package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.scanmydata.taxcenter.engine.ConfigInfo

/**
 * Οι διαθέσιμες διαδικασίες λήψης.
 *
 * Προσωρινά μόνο κατάλογος: η επιλογή πελατών και η ουρά εκτέλεσης είναι το
 * επόμενο βήμα (βλ. TODO.md, Φάση 6).
 */
@Composable
fun ProcessCatalogScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val configs: List<ConfigInfo> = remember { container.assets.catalog() }

    LazyColumn(modifier.padding(16.dp)) {
        items(configs, key = { it.id }) { cfg ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
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
