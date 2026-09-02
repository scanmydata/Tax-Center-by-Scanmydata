package gr.scanmydata.taxcenter.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Το μενού της εφαρμογής.
 *
 * Τα εικονίδια είναι προσωρινά από το Material: το φύλλο εικονιδίων που δόθηκε
 * έχει glyphs ~44px, ενώ για 24dp σε xxxhdpi χρειάζονται 96px — η μεγέθυνση θα
 * έβγαζε θολά. Θα αντικατασταθούν από vector drawables με τα ίδια σχήματα
 * (βλ. TODO.md).
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Clients("clients", "Πελάτες", Icons.Filled.Groups),
    Import("import", "Εισαγωγή από Excel", Icons.Filled.UploadFile),
    Fetch("fetch", "Λήψη εντύπων", Icons.Filled.CloudDownload),
    Documents("documents", "Έγγραφα", Icons.Filled.Description),
    Calendar("calendar", "Ημερολόγιο αποστολών", Icons.Filled.CalendarMonth),
    Logs("logs", "Ιστορικό εκτελέσεων", Icons.Filled.History),
    SettingsScreen("settings", "Ρυθμίσεις", Icons.Filled.Settings),
}
