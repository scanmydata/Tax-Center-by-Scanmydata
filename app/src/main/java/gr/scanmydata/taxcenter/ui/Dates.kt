package gr.scanmydata.taxcenter.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ημερομηνίες σε ώρα Αθηνών.
 *
 * Η ζώνη είναι ρητή και όχι η ζώνη της συσκευής: το ημερολόγιο αποστολών και το
 * αρχείο ενεργειών πρέπει να δείχνουν την ώρα του γραφείου ακόμη κι αν το
 * κινητό ταξιδέψει.
 */
object AthensDates {

    val ZONE: ZoneId = ZoneId.of("Europe/Athens")
    private val GREEK = Locale("el", "GR")

    private val dayFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", GREEK)
    private val stampFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", GREEK)
    private val isoFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun day(millis: Long): String =
        dayFormat.format(Instant.ofEpochMilli(millis).atZone(ZONE))

    fun stamp(millis: Long): String =
        stampFormat.format(Instant.ofEpochMilli(millis).atZone(ZONE))

    /** Ταξινομήσιμη μορφή για εξαγωγές CSV — ό,τι ανοίγει σε λογιστικό φύλλο. */
    fun iso(millis: Long): String =
        isoFormat.format(Instant.ofEpochMilli(millis).atZone(ZONE))
}
