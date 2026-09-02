package gr.scanmydata.taxcenter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Από το λογότυπο: ο μπλε φάκελος με τα έντυπα ΑΑΔΕ.
val BrandBlue = Color(0xFF2E7DE0)
val BrandBlueDark = Color(0xFF1B5FBF)
val BrandBlueLight = Color(0xFF7FB2F0)
val Ink = Color(0xFF0B1B2B)

private val Light = lightColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color.White,
    secondary = BrandBlue,
    onSecondary = Color.White,
    background = Color(0xFFF7F9FC),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

private val Dark = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Ink,
    secondary = BrandBlue,
    onSecondary = Color.White,
    background = Ink,
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF12263A),
    onSurface = Color(0xFFE6EDF5),
)

/**
 * Το dynamic color είναι σκόπιμα **κλειστό**: η εφαρμογή δείχνει φορολογικά
 * έγγραφα πελατών και το χρώμα μάρκας πρέπει να είναι σταθερό και αναγνωρίσιμο,
 * όχι να αλλάζει με την ταπετσαρία της συσκευής.
 */
@Composable
fun TaxCenterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        content = content,
    )
}
