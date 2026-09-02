package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.scanmydata.taxcenter.R
import gr.scanmydata.taxcenter.ui.theme.BrandBlue

/**
 * Η οθόνη υποδοχής, όσο ανοίγει η βάση.
 *
 * Δεν είναι διακοσμητική καθυστέρηση: το πρώτο άνοιγμα της SQLCipher παράγει το
 * κλειδί και κάνει τα PBKDF2 περάσματα, και σε μεσαία συσκευή αυτό διαρκεί
 * αισθητά. Χωρίς οθόνη, ο χρήστης έβλεπε λευκό.
 *
 * **Δεν κρύβει το ξεκλείδωμα**: εμφανίζεται *πριν* από την οθόνη κλειδώματος
 * και φεύγει μόνη της. Το λογότυπο διαλέγεται από το σύστημα ανάλογα με το
 * θέμα (`drawable-nodpi` / `drawable-night-nodpi`).
 *
 * Το κείμενο στο κάτω μέρος δεν είναι διακοσμητικό ούτε αυτό: η εφαρμογή
 * μοιάζει με κρατική επειδή δείχνει κρατικά έντυπα, και η δήλωση ότι **δεν**
 * σχετίζεται με την ΑΑΔΕ ή τον e-ΕΦΚΑ πρέπει να είναι το πρώτο που διαβάζεται.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(160.dp),
            )

            Spacer(Modifier.height(28.dp))
            Wordmark()

            Spacer(Modifier.height(6.dp))
            Text(
                "— TAXCENTER —",
                style = MaterialTheme.typography.titleSmall,
                color = BrandBlue,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "Έξυπνη διαχείριση και ανάκτηση\nτων φορολογικών και ασφαλιστικών σας εγγράφων",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(36.dp))
            LinearProgressIndicator(Modifier.width(220.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                "Φόρτωση…",
                style = MaterialTheme.typography.bodySmall,
                color = BrandBlue,
            )

            Spacer(Modifier.height(48.dp))
            Text(
                "Ανεξάρτητη εφαρμογή.\nΔεν σχετίζεται με την ΑΑΔΕ ή τον e-ΕΦΚΑ.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * «SCANMYDATA» με το MY στο χρώμα της μάρκας.
 *
 * Γράμματα και όχι εικόνα: το λεκτικό μένει ευκρινές σε κάθε πυκνότητα, παίρνει
 * το χρώμα κειμένου του θέματος, και το διαβάζει ο αναγνώστης οθόνης.
 */
@Composable
private fun Wordmark() {
    val ink = MaterialTheme.colorScheme.onBackground
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Bold)) { append("SCAN") }
            withStyle(SpanStyle(color = BrandBlue, fontWeight = FontWeight.Bold)) { append("MY") }
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Bold)) { append("DATA") }
        },
        fontSize = 32.sp,
        letterSpacing = 2.sp,
    )
}
