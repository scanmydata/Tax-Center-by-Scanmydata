package gr.scanmydata.taxcenter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import gr.scanmydata.taxcenter.data.KeyStoreKeys
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Η βάση, κρυπτογραφημένη ολόκληρη με SQLCipher.
 *
 * Το συνθηματικό είναι 32 τυχαία bytes από το [KeyStoreKeys], φυλαγμένα σε
 * `EncryptedSharedPreferences`. Ένα αντίγραφο του `taxcenter.db` χωρίς αυτό
 * είναι θόρυβος.
 *
 * **Καμία destructive migration.** Προτιμούμε να σκάσει το build σε ανάπτυξη
 * παρά να σβηστούν κωδικοί πελατών σε παραγωγή.
 */
@Database(
    entities = [
        ClientEntity::class,
        CredentialEntity::class,
        DocumentEntity::class,
        AuditEntity::class,
        ConsentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TaxCenterDatabase : RoomDatabase() {

    abstract fun clients(): ClientDao
    abstract fun credentials(): CredentialDao
    abstract fun documents(): DocumentDao
    abstract fun audit(): AuditDao
    abstract fun consents(): ConsentDao

    companion object {
        private const val NAME = "taxcenter.db"

        @Volatile
        private var instance: TaxCenterDatabase? = null

        fun get(context: Context): TaxCenterDatabase =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): TaxCenterDatabase {
            System.loadLibrary("sqlcipher")
            val app = context.applicationContext
            val factory = SupportOpenHelperFactory(KeyStoreKeys.databasePassphrase(app))
            return Room.databaseBuilder(app, TaxCenterDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .build()
        }

        /** Η διαδρομή του αρχείου — για το κρυπτογραφημένο αντίγραφο στο Drive. */
        fun file(context: Context) = context.applicationContext.getDatabasePath(NAME)
    }
}
