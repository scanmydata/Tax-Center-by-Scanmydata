package gr.scanmydata.taxcenter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ο engine λογάρει URL και ονόματα πεδίων. Ένας κωδικός TAXISnet που ξέφυγε σε
 * log ξέφυγε για πάντα — τα logs αντιγράφονται, στέλνονται σε support, μπαίνουν
 * σε backup. Αυτά τα tests τρέχουν στο CI **πριν** χτιστεί το APK.
 */
class RedactorTest {

    private fun assertHidden(secret: String, line: String) {
        val scrubbed = Redactor.scrub(line)
        assertFalse("διέρρευσε «$secret» στο: $scrubbed", scrubbed.contains(secret))
    }

    @Test
    fun `κωδικός σε form-encoded σώμα δεν διαρρέει`() {
        assertHidden("Mischris1!", "POST body: username=MISKCHRIS2&password=Mischris1!&request_id=RQ-42")
    }

    @Test
    fun `κωδικός σε query string δεν διαρρέει`() {
        assertHidden("s3cr3tpass", "GET https://login.gsis.gr/x?username=user0000000000&password=s3cr3tpass -> 302")
    }

    @Test
    fun `το j_password του GSIS OAuth2 δεν διαρρέει`() {
        assertHidden("hunter2", "j_username=user0000000000&j_password=hunter2")
    }

    @Test
    fun `ελληνικά ονόματα πεδίων καλύπτονται`() {
        assertHidden("μυστικό42", "Συνθηματικό: μυστικό42")
        assertHidden("ab123456c789", "Κλειδάριθμος=ab123456c789")
    }

    @Test
    fun `το myDATA subscription key κόβεται`() {
        val key = "0123456789abcdef0123456789abcdef"
        assertHidden(key, "Ocp-Apim-Subscription-Key: $key")
        assertHidden(key, "χρήση κλειδιού $key για τον πελάτη")
    }

    @Test
    fun `bearer token κόβεται`() {
        assertHidden("eyJhbGciOiJIUzI1NiJ9.abc.def", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def")
    }

    @Test
    fun `ΑΜΚΑ μασκάρεται αλλά κρατά τα δύο πρώτα ψηφία`() {
        val out = Redactor.scrub("ΑΜΚΑ: 31050101943")
        assertFalse("διέρρευσε ολόκληρο το ΑΜΚΑ: $out", out.contains("31050101943"))
        assertTrue("χάθηκε το πρόθεμα: $out", out.contains("31*********"))
    }

    @Test
    fun `το ΑΦΜ παραμένει — χωρίς αυτό τα διαγνωστικά είναι άχρηστα`() {
        // Συνειδητή απόφαση, τεκμηριωμένη στο docs/privacy-policy.md.
        val out = Redactor.scrub("[aade-income] λήψη Ε1 για ΑΦΜ 999999999")
        assertTrue("το ΑΦΜ έπρεπε να μείνει: $out", out.contains("999999999"))
    }

    @Test
    fun `ωφέλιμο κείμενο δεν καταστρέφεται`() {
        val line = "[aade-income] GET https://www1.aade.gr/webtax/incomefp/year2025-income-menu.do -> 200"
        assertEquals(line, Redactor.scrub(line))
    }

    @Test
    fun `κενό και null είναι ασφαλή`() {
        assertEquals("", Redactor.scrub(null))
        assertEquals("", Redactor.scrub(""))
    }

    @Test
    fun `mask δείχνει μόνο δύο χαρακτήρες`() {
        assertEquals("Mi********", Redactor.mask("Mischris1!"))
        assertEquals("**", Redactor.mask("ab"))
        assertEquals("", Redactor.mask(null))
    }
}
