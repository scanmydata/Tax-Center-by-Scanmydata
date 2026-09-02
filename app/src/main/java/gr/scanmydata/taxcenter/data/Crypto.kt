package gr.scanmydata.taxcenter.data

import android.util.Base64
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

    /**
     * Κρυπτογραφεί. Το κενό μένει κενό — δεν έχει νόημα να κρύψουμε το τίποτα.
     *
     * **Το IV δεν το δίνουμε εμείς.** Ένα κλειδί του Android Keystore
     * δημιουργείται εξ ορισμού με `setRandomizedEncryptionRequired(true)`, και
     * τότε το `init(ENCRYPT_MODE, key, GCMParameterSpec(…))` πετά
     * `InvalidAlgorithmParameterException: caller-provided IV not permitted`.
     *
     * Και έχει δίκιο: στο GCM, δύο κρυπτογραφήσεις με το ίδιο ζεύγος κλειδιού
     * και IV δεν αποκαλύπτουν απλώς ομοιότητες — επιτρέπουν την ανάκτηση του
     * κλειδιού αυθεντικοποίησης. Το λειτουργικό δεν εμπιστεύεται τον καλούντα
     * γι' αυτό· παράγει το ίδιο το IV και το διαβάζουμε από το `cipher.iv`.
     */
    fun enc(plain: String?): String {
        if (plain.isNullOrEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider())
        }
        val iv = cipher.iv
        // Το AndroidKeyStore δίνει πάντα 12 bytes για GCM. Ο έλεγχος υπάρχει
        // επειδή η αποκρυπτογράφηση κόβει το IV σε σταθερό μήκος: αν κάποτε
        // αλλάξει, θέλουμε να το μάθουμε τώρα και όχι όταν δεν θα διαβάζονται
        // πια οι κωδικοί.
        check(iv.size == IV_BYTES) { "Απρόσμενο μήκος IV: ${iv.size}" }
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
