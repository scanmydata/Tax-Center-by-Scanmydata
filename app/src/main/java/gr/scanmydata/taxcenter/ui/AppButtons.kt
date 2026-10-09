package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/*
 * Τα κουμπιά της εφαρμογής.
 *
 * ## Γιατί έχουν τα ίδια ονόματα με του Material
 *
 * `Button`, `OutlinedButton`, `TextButton`, `IconButton` — επίτηδες. Οι οθόνες
 * ζουν όλες σε αυτό το πακέτο, και η Kotlin προτιμά μια δήλωση του ίδιου
 * πακέτου από οτιδήποτε έρχεται με `import …*`. Έτσι μια οθόνη που γράφει
 * `Button(…)` **χωρίς** import παίρνει το κουμπί της εφαρμογής, και δεν υπάρχει
 * δεύτερο όνομα που κάποιος πρέπει να θυμηθεί να χρησιμοποιήσει.
 *
 * Το αντίτιμο: ένα ρητό `import androidx.compose.material3.Button` σε οθόνη
 * φέρνει πίσω το κουμπί του Material, χωρίς πλαίσιο. Αν ένα κουμπί δείχνει
 * αλλιώς από τα διπλανά του, εκεί είναι ο λόγος.
 *
 * ## Η όψη
 *
 * Κάθε κουμπί έχει **πλαίσιο**, στα χρώματα του θέματος, και καμία σκιά. Τρεις
 * βαθμίδες, όπως και πριν — αλλάζει το πόσο «γεμάτο» είναι το καθένα:
 *
 *  * [Button] — η κύρια ενέργεια της οθόνης: πλαίσιο και αχνό γέμισμα,
 *  * [OutlinedButton] — δευτερεύουσα: πλαίσιο, χωρίς γέμισμα,
 *  * [TextButton] — οι επιλογές ενός διαλόγου: λεπτό, ουδέτερο πλαίσιο.
 *
 * Το **χρώμα** λέει τι κάνει το κουμπί — βλ. [Tone].
 */

/**
 * Τι κάνει ένα κουμπί στα δεδομένα.
 *
 * Το κόκκινο δεν είναι διακόσμηση: σημαδεύει ό,τι **δεν αναιρείται**. Γι' αυτό
 * μπαίνει και στο κουμπί που ανοίγει μια διαγραφή και σε αυτό που την
 * επιβεβαιώνει — ο χρήστης πρέπει να βλέπει το ίδιο χρώμα και τις δύο φορές.
 */
enum class Tone {
    /** Οτιδήποτε άλλο: το χρώμα του θέματος. */
    NEUTRAL,

    /** Προσθέτει ή δημιουργεί κάτι — πελάτη, έντυπο στη λίστα, καρτέλα. */
    ADD,

    /** Σβήνει ή αφαιρεί κάτι. */
    DELETE,
}

/** Το χρώμα ενός ρόλου, μέσα στο τρέχον θέμα. */
@Composable
@ReadOnlyComposable
internal fun Tone.accent(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.primary
    Tone.ADD -> addGreen()
    Tone.DELETE -> MaterialTheme.colorScheme.error
}

/**
 * Το πράσινο της προσθήκης.
 *
 * Το Material δεν έχει «πράσινο» στο σχήμα του, οπότε ορίζεται εδώ — σκούρο σε
 * ανοιχτό φόντο και ανοιχτό σε σκούρο, ώστε το κείμενο του κουμπιού να
 * διαβάζεται και στα δύο. Κρίνεται από το φόντο και όχι από τη ρύθμιση του
 * συστήματος: έτσι ακολουθεί όποιο θέμα ισχύει πραγματικά.
 */
@Composable
@ReadOnlyComposable
private fun addGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFA3BE8C) else Color(0xFF3A7D34)

@Composable
@ReadOnlyComposable
private fun disabledContent(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

@Composable
@ReadOnlyComposable
private fun disabledBorder(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

/** Η κύρια ενέργεια: πλαίσιο στο χρώμα του ρόλου και αχνό γέμισμα από το ίδιο. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone = Tone.NEUTRAL,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = tone.accent()
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = accent.copy(alpha = 0.12f),
            contentColor = accent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledContent(),
        ),
        border = BorderStroke(1.dp, if (enabled) accent else disabledBorder()),
        contentPadding = contentPadding,
        content = content,
    )
}

/** Δευτερεύουσα ενέργεια: το ίδιο πλαίσιο, χωρίς γέμισμα. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone = Tone.NEUTRAL,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = tone.accent()
    // Στο ουδέτερο, το πλαίσιο είναι το «outline» του θέματος και όχι το κύριο
    // χρώμα: δύο κουμπιά δίπλα-δίπλα πρέπει να ξεχωρίζουν ποιο είναι το κύριο.
    val border = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.outline else accent
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = disabledContent(),
        ),
        border = BorderStroke(1.dp, if (enabled) border else disabledBorder()),
        contentPadding = contentPadding,
        content = content,
    )
}

/** Οι επιλογές ενός διαλόγου, και κάθε μικρή ενέργεια μέσα σε κείμενο. */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone = Tone.NEUTRAL,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = tone.accent()
    val border = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.outlineVariant else accent.copy(alpha = 0.6f)
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = disabledContent(),
        ),
        border = BorderStroke(1.dp, if (enabled) border else disabledBorder()),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Κουμπί με εικονίδιο. Το εικονίδιο παίρνει το χρώμα του ρόλου, εκτός αν η
 * οθόνη του δώσει ρητά άλλο `tint`.
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone = Tone.NEUTRAL,
    content: @Composable () -> Unit,
) {
    // Το ουδέτερο εικονίδιο παίρνει το χρώμα **του χώρου όπου βρίσκεται**, και
    // το πλαίσιό του το ίδιο, αχνότερο. Το μενού ζει πάνω στη χρωματιστή μπάρα
    // τίτλου· με το χρώμα του κειμένου θα γινόταν σκούρο πάνω σε μπλε.
    val tint = if (tone == Tone.NEUTRAL) LocalContentColor.current else tone.accent()
    val border = tint.copy(alpha = if (tone == Tone.NEUTRAL) 0.3f else 0.6f)
    androidx.compose.material3.OutlinedIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = IconButtonDefaults.outlinedIconButtonColors(
            contentColor = tint,
            disabledContentColor = disabledContent(),
        ),
        border = BorderStroke(1.dp, if (enabled) border else disabledBorder()),
        content = content,
    )
}
