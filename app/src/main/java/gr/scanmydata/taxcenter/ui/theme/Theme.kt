package gr.scanmydata.taxcenter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Από το λογότυπο: ο μπλε φάκελος με τα έντυπα ΑΑΔΕ.
val BrandBlue = Color(0xFF2E7DE0)
val BrandBlueDark = Color(0xFF1B5FBF)
val BrandBlueLight = Color(0xFF7FB2F0)
val Ink = Color(0xFF0B1B2B)

/**
 * Ποιο θέμα βλέπει ο χρήστης.
 *
 * Δύο επιλογές, **ίδιες λειτουργίες**: αλλάζει η χρωματική γλώσσα, τα σχήματα
 * και οι αποστάσεις — καμία οθόνη, κανένα κουμπί, καμία ροή.
 */
enum class ThemeVariant(val label: String, val description: String) {
    CLASSIC(
        "Κλασικό",
        "Τα χρώματα του λογότυπου: μπλε φάκελος σε λευκό φόντο, με κάρτες που " +
            "ξεχωρίζουν με σκιά.",
    ),
    CLEAN(
        "Καθαρό (Nord)",
        "Πιο ήπια, ψυχρά χρώματα και επίπεδες επιφάνειες με λεπτό περίγραμμα " +
            "αντί για σκιές. Λιγότερη αντίθεση, πιο ήσυχη οθόνη σε πολύωρη χρήση.",
    ),
}

// ------------------------------------------------------------------ κλασικό

private val ClassicLight = lightColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color.White,
    secondary = BrandBlue,
    onSecondary = Color.White,
    background = Color(0xFFF7F9FC),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

private val ClassicDark = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Ink,
    secondary = BrandBlue,
    onSecondary = Color.White,
    background = Ink,
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF12263A),
    onSurface = Color(0xFFE6EDF5),
)

// -------------------------------------------------------------------- Nord
//
// Παλέτα Nord του Arctic Ice Studio (Sven Greb), υπό άδεια MIT:
// https://github.com/nordtheme/nord — δεκαέξι σκόπιμα υποτονισμένα χρώματα,
// φτιαγμένα για ώρες μπροστά στην οθόνη.
//
// Επιλέχθηκε γιατί λύνει ακριβώς το πρόβλημα αυτής της εφαρμογής: ο λογιστής
// κοιτάζει λίστες και αριθμούς για ώρες, και το έντονο μπλε της μάρκας —
// σωστό για ταυτότητα — κουράζει όταν είναι παντού. Οι τόνοι Frost κρατούν
// το «μπλε» της εφαρμογής χωρίς την ένταση.

private val Nord0 = Color(0xFF2E3440)
private val Nord1 = Color(0xFF3B4252)
private val Nord2 = Color(0xFF434C5E)
private val Nord3 = Color(0xFF4C566A)
private val Nord4 = Color(0xFFD8DEE9)
private val Nord5 = Color(0xFFE5E9F0)
private val Nord6 = Color(0xFFECEFF4)
private val Nord7 = Color(0xFF8FBCBB)
private val Nord8 = Color(0xFF88C0D0)
private val Nord9 = Color(0xFF81A1C1)
private val Nord10 = Color(0xFF5E81AC)
private val Nord11 = Color(0xFFBF616A)
private val Nord13 = Color(0xFFEBCB8B)
private val Nord14 = Color(0xFFA3BE8C)

private val CleanLight = lightColorScheme(
    primary = Nord10,
    onPrimary = Nord6,
    primaryContainer = Color(0xFFDCE4EE),
    onPrimaryContainer = Nord0,
    secondary = Nord9,
    onSecondary = Nord6,
    secondaryContainer = Nord5,
    onSecondaryContainer = Nord1,
    tertiary = Nord7,
    onTertiary = Nord0,
    tertiaryContainer = Color(0xFFDFEAEA),
    onTertiaryContainer = Nord1,
    error = Nord11,
    onError = Nord6,
    errorContainer = Color(0xFFF2DDDF),
    onErrorContainer = Color(0xFF6B2B31),
    background = Nord6,
    onBackground = Nord0,
    // Η επιφάνεια είναι μόλις μια απόχρωση πιο ανοιχτή από το φόντο: έτσι οι
    // κάρτες διαβάζονται από το περίγραμμά τους και όχι από σκιά.
    surface = Color(0xFFF4F6F9),
    onSurface = Nord0,
    surfaceVariant = Nord5,
    onSurfaceVariant = Nord2,
    outline = Color(0xFFB6BFCE),
    outlineVariant = Nord4,
)

private val CleanDark = darkColorScheme(
    primary = Nord8,
    onPrimary = Nord0,
    primaryContainer = Nord2,
    onPrimaryContainer = Nord6,
    secondary = Nord9,
    onSecondary = Nord0,
    secondaryContainer = Nord1,
    onSecondaryContainer = Nord5,
    tertiary = Nord7,
    onTertiary = Nord0,
    tertiaryContainer = Nord2,
    onTertiaryContainer = Nord6,
    error = Nord11,
    onError = Nord6,
    errorContainer = Color(0xFF4A2B30),
    onErrorContainer = Color(0xFFF2DDDF),
    background = Nord0,
    onBackground = Nord4,
    surface = Nord1,
    onSurface = Nord4,
    surfaceVariant = Nord2,
    onSurfaceVariant = Nord4,
    outline = Nord3,
    outlineVariant = Nord2,
)

/** Χρώματα κατάστασης που δεν ανήκουν στο σχήμα του Material. */
val WarnAmber = Nord13
val OkGreen = Nord14

// ------------------------------------------------------ σχήματα & γραμματοσειρά

private val ClassicShapes = Shapes()

/**
 * Πιο μεγάλες καμπύλες και πιο ήρεμη ιεραρχία.
 *
 * Δεν είναι διακόσμηση: με επίπεδες επιφάνειες, η καμπύλη είναι αυτό που
 * ξεχωρίζει ένα κουτί από το φόντο του.
 */
private val CleanShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/**
 * Τυπογραφία με λίγο περισσότερο αέρα.
 *
 * Οι τίτλοι χάνουν βάρος και κερδίζουν απόσταση γραμμάτων, οι μικρές γραμμές
 * κερδίζουν ύψος γραμμής. Σε οθόνες γεμάτες ΑΦΜ και ονόματα, ο αέρας κάνει
 * περισσότερα από το μέγεθος.
 */
private val CleanTypography: Typography
    get() {
        val base = Typography()
        return base.copy(
            titleLarge = base.titleLarge.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
            ),
            titleMedium = base.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.15.sp,
            ),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
            bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
            bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
            labelMedium = base.labelMedium.copy(letterSpacing = 0.6.sp),
        )
    }

/**
 * Η επιλογή θέματος, έξω από τη σύνθεση.
 *
 * `object` για τον ίδιο λόγο με το [gr.scanmydata.taxcenter.security.AppLock]:
 * το θέμα τυλίγει **ολόκληρη** την εφαρμογή, πάνω από τον NavHost, οπότε δεν
 * μπορεί να ζει μέσα σε μια οθόνη. Το SharedPreferences δεν ειδοποιεί το
 * Compose από μόνο του — εδώ είναι η μοναδική πηγή αλήθειας όσο τρέχει η
 * εφαρμογή, και οι Ρυθμίσεις γράφουν και στα δύο.
 */
object ThemeState {
    var variant by mutableStateOf(ThemeVariant.CLASSIC)
        private set

    fun set(value: ThemeVariant) {
        variant = value
    }
}

/**
 * Το dynamic color είναι σκόπιμα **κλειστό**: η εφαρμογή δείχνει φορολογικά
 * έγγραφα πελατών και το χρώμα μάρκας πρέπει να είναι σταθερό και αναγνωρίσιμο,
 * όχι να αλλάζει με την ταπετσαρία της συσκευής.
 */
@Composable
fun TaxCenterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    variant: ThemeVariant = ThemeState.variant,
    content: @Composable () -> Unit,
) {
    val clean = variant == ThemeVariant.CLEAN
    MaterialTheme(
        colorScheme = when {
            clean && darkTheme -> CleanDark
            clean -> CleanLight
            darkTheme -> ClassicDark
            else -> ClassicLight
        },
        shapes = if (clean) CleanShapes else ClassicShapes,
        typography = if (clean) CleanTypography else Typography(),
        content = content,
    )
}
