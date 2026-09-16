package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import gr.scanmydata.taxcenter.update.QrCode
import kotlin.math.floor

/**
 * Ζωγραφίζει έναν κώδικα QR.
 *
 * **Πάντα μαύρο σε λευκό, ακόμη και σε σκοτεινό θέμα.** Δεν είναι αβλεψία στη
 * θεματοποίηση: οι σαρωτές περιμένουν σκούρες μονάδες σε ανοιχτό φόντο, και ο
 * αντεστραμμένος κώδικας είτε δεν διαβάζεται είτε διαβάζεται μετά από πολλή
 * προσπάθεια σε συσκευή που δεν είναι η δική σου — δηλαδή ακριβώς εκεί που δεν
 * μπορείς να βοηθήσεις.
 *
 * Η **ήσυχη ζώνη** τεσσάρων μονάδων γύρω-γύρω είναι μέρος του προτύπου, όχι
 * περιθώριο σχεδίασης: χωρίς αυτήν ο σαρωτής δεν βρίσκει τα όρια του κώδικα.
 */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val symbol = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() }

    if (symbol == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Ο σύνδεσμος είναι πολύ μεγάλος για κώδικα QR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    Canvas(modifier.aspectRatio(1f)) {
        val quiet = 4
        val modules = symbol.size + quiet * 2

        // Ακέραιο μέγεθος μονάδας, και το πλέγμα κεντραρισμένο σε ό,τι περισσεύει.
        // Με κλασματικό μέγεθος οι διπλανές μονάδες πέφτουν σε μισά pixel και
        // ανάμεσά τους μένουν γκρίζες γραμμές που μπερδεύουν τον σαρωτή.
        val cell = floor(size.minDimension / modules).coerceAtLeast(1f)
        val side = cell * modules
        val originX = (size.width - side) / 2
        val originY = (size.height - side) / 2

        drawRect(Color.White, topLeft = Offset(originX, originY), size = Size(side, side))

        for (y in 0 until symbol.size) {
            for (x in 0 until symbol.size) {
                if (!symbol[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(originX + (x + quiet) * cell, originY + (y + quiet) * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}
