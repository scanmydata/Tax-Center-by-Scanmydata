package gr.scanmydata.taxcenter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Τα δύο κλειδιά της εφαρμογής, και τα δύο δεμένα στο υλικό όπου υπάρχει.
 *
 *  * **Κλειδί τιμών** ([dataKey]) — AES-256-GCM στο Android Keystore. Δεν
 *    εξάγεται ποτέ: το λειτουργικό κάνει την κρυπτογράφηση, η εφαρμογή βλέπει
 *    μόνο μια αναφορά. Ακόμη και root δεν το διαβάζει από συσκευή με StrongBox.
 *  * **Συνθηματικό βάσης** ([databasePassphrase]) — 32 τυχαία bytes για το
 *    SQLCipher, φυλαγμένα σε `EncryptedSharedPreferences` (που με τη σειρά τους
 *    προστατεύονται από δικό τους Keystore master key).
 *
 * Και τα δύο παράγονται στην πρώτη εκκίνηση και δεν αλλάζουν. Αν χαθούν —
 * διαγραφή εφαρμογής, επαναφορά εργοστασιακών — **τα δεδομένα δεν ανακτώνται**·
 * γι' αυτό υπάρχει το προαιρετικό, κρυπτογραφημένο αντίγραφο στο Drive.
 */
object KeyStoreKeys {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DATA_KEY_ALIAS = "taxcenter_data_key_v1"
    private const val PREFS_NAME = "taxcenter_secure"
    private const val DB_PASSPHRASE_KEY = "db_passphrase_b64"

    /** Το κλειδί που χρησιμοποιεί ο [Crypto] για τιμές. Δημιουργείται μία φορά. */
    @Synchronized
    fun dataKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(DATA_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                DATA_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Σκόπιμα ΔΕΝ απαιτούμε βιομετρική ανά χρήση: οι μαζικές λήψεις
                // τρέχουν σε WorkManager και θα σταματούσαν σε κάθε πελάτη. Το
                // ξεκλείδωμα γίνεται σε επίπεδο εφαρμογής (AppLock).
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** Το συνθηματικό του SQLCipher, ως bytes. Παράγεται στην πρώτη κλήση. */
    @Synchronized
    fun databasePassphrase(context: Context): ByteArray {
        val prefs = EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        prefs.getString(DB_PASSPHRASE_KEY, null)?.let {
            return Base64.decode(it, Base64.NO_WRAP)
        }

        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(DB_PASSPHRASE_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
            .apply()
        return fresh
    }
}
