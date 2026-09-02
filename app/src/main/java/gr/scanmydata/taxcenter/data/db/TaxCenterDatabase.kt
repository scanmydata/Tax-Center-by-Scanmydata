package gr.scanmydata.taxcenter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * παρά να σβηστούν κωδικοί πελατών σε παραγωγή — γι' αυτό κάθε αλλαγή σχήματος
 * γράφεται ρητά, ακόμη κι όταν «δεν έχει προλάβει να μπει σε συσκευή».
 */
@Database(
    entities = [
        ClientEntity::class,
        CredentialEntity::class,
        DocumentEntity::class,
        AuditEntity::class,
        ConsentEntity::class,
        SendEntity::class,
        RunLogEntity::class,
        DriveFileEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class TaxCenterDatabase : RoomDatabase() {

    abstract fun clients(): ClientDao
    abstract fun credentials(): CredentialDao
    abstract fun documents(): DocumentDao
    abstract fun audit(): AuditDao
    abstract fun consents(): ConsentDao
    abstract fun sends(): SendDao
    abstract fun runLogs(): RunLogDao
    abstract fun driveFiles(): DriveFileDao

    companion object {
        private const val NAME = "taxcenter.db"

        /** v2: ημερολόγιο αποστολών + ημερολόγια εκτελέσεων. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sends` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `clientId` INTEGER NOT NULL,
                        `afm` TEXT NOT NULL,
                        `clientName` TEXT NOT NULL,
                        `toEmail` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `items` TEXT NOT NULL,
                        `itemCount` INTEGER NOT NULL,
                        `sentAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `error` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sends_sentAt` ON `sends` (`sentAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sends_clientId` ON `sends` (`clientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sends_afm` ON `sends` (`afm`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `run_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `afm` TEXT NOT NULL,
                        `configId` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `ok` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `fileCount` INTEGER NOT NULL,
                        `lines` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_logs_startedAt` ON `run_logs` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_logs_afm` ON `run_logs` (`afm`)")
            }
        }

        /** v3: cache αντιστοίχισης τοπικών αρχείων με τα αντίγραφά τους στο Drive. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `drive_files` (
                        `relativePath` TEXT PRIMARY KEY NOT NULL,
                        `driveId` TEXT NOT NULL,
                        `remoteName` TEXT NOT NULL,
                        `parentId` TEXT NOT NULL,
                        `bytes` INTEGER NOT NULL,
                        `syncedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        /** Η διαδρομή του αρχείου — για το κρυπτογραφημένο αντίγραφο στο Drive. */
        fun file(context: Context) = context.applicationContext.getDatabasePath(NAME)
    }
}
