package gr.scanmydata.taxcenter.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNotesTest {

    @Test
    fun `old notes lose the build lines and the iOS paragraph`() {
        val notes = "Αυτόματο build από commit abc1234.\n\n" +
            "✅ Release-signed με το production keystore.\n\n" +
            "📱 iOS: το .ipa προστίθεται εδώ λίγα λεπτά μετά το APK. Είναι ανυπόγραφο.\n\n" +
            "Παράθυρο αναμονής πάνω από την οθόνη\n\nΛεπτομέρειες."
        assertEquals("Παράθυρο αναμονής πάνω από την οθόνη\n\nΛεπτομέρειες.", UpdateChecker.changesOf(notes))
    }

    @Test
    fun `new notes stop at the separator`() {
        val notes = "Λάθος κωδικοί: σαφές μήνυμα\r\n\r\nΣτοιχεία Μητρώου\n\n---\n✅ Release-signed · commit abc1234"
        assertEquals("Λάθος κωδικοί: σαφές μήνυμα\n\nΣτοιχεία Μητρώου", UpdateChecker.changesOf(notes))
    }
}
