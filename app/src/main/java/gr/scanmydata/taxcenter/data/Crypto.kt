package gr.scanmydata.taxcenter.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Κρυπτογράφηση τιμής-προς-τιμή, με εκδοχή στο πρόθεμα.
 *
 * Η βάση είναι ήδη κρυπτογραφημένη ολόκληρη (SQLCipher). Αυτό είναι το **δεύτερο
 * στρώμα**: ακόμη κι αν κάποιος πάρει ξεκλείδωτη τη βάση — από backup, από
 * σφάλμα εξαγωγής, από ένα `SELECT` σε debug build — οι κωδικοί TAXISnet
 * παραμένουν κρυπτογραφημένοι με κλειδί που ζει μόνο στο Android Keystore και
 * δεν εξάγεται ποτέ.
 *
 * Μορφή: `enc:1:` + base64( IV(12) ‖ ciphertext ‖ tag(16) ).
 *
 * Το πρόθεμα κουβαλά εκδοχή ώστε να μπορεί να αλλάξει ο αλγόριθμος χωρίς να
 * χαθούν τα παλιά δεδομένα. Τιμές **χωρίς** πρόθεμα επιστρέφονται ως έχουν:
 * ανέχεται δεδομένα από παλιότερο εργαλείο που τα κρατούσε σε καθαρό κείμενο.
 */
class Crypto(private val keyProvider: () -> SecretKey) {

    /** Κρυπτογραφεί. Το κενό μένει κενό — δεν έχει νόημα να κρύψουμε το τίποτα. */
    fun enc(plain: String?): String {
        if (plain.isNullOrEmpty()) return ""
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    /**
     * Αποκρυπτογραφεί. Επιστρέφει κενό αν το κλειδί δεν ταιριάζει — δεν πετάει,
     * γιατί μια χαλασμένη τιμή δεν πρέπει να ρίξει ολόκληρη τη λίστα πελατών.
     */
    fun dec(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    keyProvider(),
                    GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
                )
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Λάθος ή χαμένο κλειδί. Δεν λογάρουμε την τιμή, προφανώς.
            ""
        }
    }

    /** Είναι ήδη κρυπτογραφημένο; Χρήσιμο σε migrations και σε ελέγχους. */
    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    companion object {
        const val PREFIX = "enc:1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
