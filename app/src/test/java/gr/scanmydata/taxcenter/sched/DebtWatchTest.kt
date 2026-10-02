package gr.scanmydata.taxcenter.sched

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Το πρόγραμμα της αυτόματης ενημέρωσης οφειλών — ό,τι του κρίνεται χωρίς
 * συσκευή: πότε είναι η πρώτη εκτέλεση, και τι λέει ο απολογισμός.
 */
class DebtWatchTest {

    private val athens = ZoneId.of("Europe/Athens")
    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, 2, hour, minute, 0, 0, athens)

    @Test
    fun `η πρώτη εκτέλεση είναι στην επόμενη εμφάνιση της ώρας`() {
        assertEquals(Duration.ofMinutes(90), DebtWatch.initialDelay(at(6, 30), 8))
        // Η ώρα πέρασε σήμερα: αύριο.
        assertEquals(Duration.ofHours(23), DebtWatch.initialDelay(at(9, 0), 8))
    }

    /**
     * Αλλαγή ρύθμισης **ακριβώς** πάνω στην ώρα δεν ξεκινά λήψη «μόλις τώρα»:
     * ο χρήστης που πειράζει τις ρυθμίσεις στις 08:00 δεν ζήτησε εκτέλεση.
     */
    @Test
    fun `ακριβώς πάνω στην ώρα πάει στην επόμενη μέρα`() {
        assertEquals(Duration.ofHours(24), DebtWatch.initialDelay(at(8, 0), 8))
    }

    @Test
    fun `ώρα εκτός ορίων δεν δίνει αρνητική ή παράλογη αναμονή`() {
        val late = DebtWatch.initialDelay(at(12, 0), 99)
        assertEquals(Duration.ofHours(11), late)
        val early = DebtWatch.initialDelay(at(12, 0), -5)
        assertEquals(Duration.ofHours(12), early)
    }

    @Test
    fun `άγνωστη συχνότητα σημαίνει κλειστό πρόγραμμα`() {
        assertEquals(DebtWatch.Frequency.OFF, DebtWatch.Frequency.of(""))
        assertEquals(DebtWatch.Frequency.OFF, DebtWatch.Frequency.of("HOURLY"))
        assertEquals(DebtWatch.Frequency.WEEKLY, DebtWatch.Frequency.of("WEEKLY"))
    }

    @Test
    fun `ο απολογισμός επιβιώνει από το ένα κομμάτι στο επόμενο`() {
        val tally = DebtWatch.Tally(updated = 7, changed = 2, failed = 1, dropped = 1, skipped = 3)
        assertEquals(tally, DebtWatch.Tally.of(tally.toArray()))
        assertEquals(12, tally.total)
        // Χαλασμένα ή ελλιπή δεδομένα: καθαρός απολογισμός, όχι σφάλμα.
        assertEquals(DebtWatch.Tally(), DebtWatch.Tally.of(null))
        assertEquals(DebtWatch.Tally(), DebtWatch.Tally.of(intArrayOf(1, 2)))
    }

    /**
     * Η σύνοψη καταλήγει σε ειδοποίηση. Μόνο πλήθη: ονόματα πελατών δίπλα στη
     * λέξη «οφειλές» δεν έχουν θέση σε οθόνη κλειδώματος.
     */
    @Test
    fun `η σύνοψη λέει πλήθη και μόνο ό,τι συνέβη`() {
        assertEquals("Ενημερώθηκαν 5 καρτέλες", DebtWatch.Tally(updated = 5).summary())
        assertEquals("Ενημερώθηκε 1 καρτέλα · αλλαγές σε 1", DebtWatch.Tally(updated = 1, changed = 1).summary())
        val full = DebtWatch.Tally(updated = 3, changed = 1, failed = 2, dropped = 1, skipped = 4).summary()
        assertTrue(full, full.contains("2 απέτυχαν"))
        assertTrue(full.contains("1 βγήκαν από το πρόγραμμα (λάθος κωδικοί)"))
        assertTrue(full.contains("4 χωρίς κωδικούς"))
        assertFalse(DebtWatch.Tally(updated = 5).summary().contains("απέτυχαν"))
        assertEquals("Καμία καρτέλα δεν ενημερώθηκε", DebtWatch.Tally().summary())
    }
}
