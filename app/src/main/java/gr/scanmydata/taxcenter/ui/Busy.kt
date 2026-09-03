package gr.scanmydata.taxcenter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Το «περίμενε» της εφαρμογής.
 *
 * Υπάρχει επειδή οι μακριές ενέργειες εδώ δεν είναι στιγμιαίες και δεν είναι
 * ακίνδυνες: μια σύνδεση στο GSIS κρατά δεκάδες δευτερόλεπτα, και ένας χρήστης
 * που δεν βλέπει τίποτα να συμβαίνει ξαναπατά το κουμπί — δηλαδή ανοίγει
 * δεύτερη συνεδρία, δηλαδή κλείδωμα `OAM-6`. Η επικάλυψη **μπλοκάρει τα
 * πατήματα** ακριβώς γι' αυτό.
 *
 * Δεν έχει κουμπί ακύρωσης εκτός αν δοθεί [onCancel]: μια μισοτελειωμένη
 * αποστολή ή αποθήκευση είναι χειρότερη από μια που περίμενες.
 */
@Composable
fun BusyOverlay(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    detail: String = "",
    onCancel: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                // Καταπίνει κάθε πάτημα όσο είναι ορατή. Χωρίς αυτό, η
                // επικάλυψη θα ήταν διακοσμητική.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.widthIn(max = 320.dp).padding(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    if (onCancel != null) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onCancel) { Text("Διακοπή") }
                    }
                }
            }
        }
    }
}
