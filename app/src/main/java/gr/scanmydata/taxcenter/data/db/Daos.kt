package gr.scanmydata.taxcenter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientDao {

    @Query("SELECT * FROM clients WHERE deleted = 0 ORDER BY name, firstName")
    fun observeAll(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE deleted = 0 ORDER BY name, firstName")
    suspend fun all(): List<ClientEntity>

    @Query("SELECT * FROM clients WHERE afm = :afm LIMIT 1")
    suspend fun byAfm(afm: String): ClientEntity?

    @Query("SELECT * FROM clients WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ClientEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(client: ClientEntity): Long

    @Update
    suspend fun update(client: ClientEntity)

    @Query("UPDATE clients SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    /**
     * Οι διευθύνσεις γράφονται αυτούσιες — και κενές.
     *
     * Είναι η μία εξαίρεση στον κανόνα «κενό δεν σβήνει»: όταν ο λογιστής
     * αδειάζει το πεδίο στην καρτέλα, εννοεί ότι η διεύθυνση είναι λάθος και
     * δεν πρέπει να ξανασταλεί τίποτα εκεί.
     */
    @Query(
        "UPDATE clients SET emailAade = :aade, emailManual = :manual, " +
            "emailPreferred = :preferred, updatedAt = :now WHERE id = :id",
    )
    suspend fun setEmails(id: Long, aade: String, manual: String, preferred: String, now: Long)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun hardDelete(id: Long)

    /**
     * Εισαγωγή ή ενημέρωση **χωρίς να σβήνει τίποτα**.
     *
     * Ο κανόνας είναι απόλυτος: μια κενή τιμή στο αρχείο δεν αντικαθιστά ποτέ
     * αποθηκευμένη τιμή. Ένα μερικό export — π.χ. ένα φύλλο με ΑΦΜ αλλά χωρίς
     * στήλες κωδικών — αλλιώς θα έσβηνε σιωπηλά τα διαπιστευτήρια όλων.
     */
    @Transaction
    suspend fun upsertPreservingBlanks(incoming: ClientEntity, now: Long): Long {
        val existing = byAfm(incoming.afm)
        if (existing == null) {
            return insert(incoming.copy(importedAt = now, updatedAt = now))
        }
        val merged = existing.copy(
            name = incoming.name.ifBlank { existing.name },
            firstName = incoming.firstName.ifBlank { existing.firstName },
            kind = incoming.kind.ifBlank { existing.kind },
            amkaEnc = incoming.amkaEnc.ifBlank { existing.amkaEnc },
            doy = incoming.doy.ifBlank { existing.doy },
            // Το «ανενεργός» είναι πληροφορία, όχι κενό — περνά όπως έρχεται.
            active = incoming.active,
            sourceFile = incoming.sourceFile.ifBlank { existing.sourceFile },
            deleted = false,
            updatedAt = now,
        )
        update(merged)
        return existing.id
    }
}

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials WHERE clientId = :clientId")
    suspend fun forClient(clientId: Long): List<CredentialEntity>

    @Query("SELECT valueEnc FROM credentials WHERE clientId = :clientId AND field = :field LIMIT 1")
    suspend fun value(clientId: Long, field: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(credential: CredentialEntity)

    @Query("DELETE FROM credentials WHERE clientId = :clientId AND field = :field")
    suspend fun remove(clientId: Long, field: String)

    /** Ίδιος κανόνας: κενή τιμή δεν σβήνει αποθηκευμένη. */
    @Transaction
    suspend fun putIfNotBlank(clientId: Long, field: String, valueEnc: String, now: Long) {
        if (valueEnc.isBlank()) return
        put(CredentialEntity(clientId, field, valueEnc, now))
    }
}

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE clientId = :clientId ORDER BY createdAt DESC")
    fun observeForClient(clientId: Long): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE clientId = :clientId ORDER BY createdAt DESC")
    suspend fun forClient(clientId: Long): List<DocumentEntity>

    @Query("SELECT * FROM documents ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 1000): Flow<List<DocumentEntity>>

    /**
     * Για την επανάληψη αποτυχημένης αποστολής: το `sends` κρατά ονόματα
     * αρχείων, όχι ids — επίτηδες, γιατί ένα έγγραφο μπορεί να διαγραφεί από την
     * πολιτική διατήρησης ενώ η εγγραφή της αποστολής πρέπει να μείνει.
     */
    @Query("SELECT * FROM documents WHERE clientId = :clientId AND fileName IN (:names)")
    suspend fun byClientAndNames(clientId: Long, names: List<String>): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE createdAt < :before")
    suspend fun olderThan(before: Long): List<DocumentEntity>

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM documents WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(document: DocumentEntity): Long

    @Query("UPDATE documents SET sentAt = :now WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>, now: Long)

    @Query("DELETE FROM documents WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface AuditDao {

    @Insert
    suspend fun log(entry: AuditEntity)

    @Query("SELECT * FROM audit_log ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<AuditEntity>

    @Query("SELECT * FROM audit_log ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit_log ORDER BY ts")
    suspend fun all(): List<AuditEntity>

    /**
     * Καθαρίζει το αρχείο ενεργειών.
     *
     * Υπάρχει επειδή το ζήτησε ο υπεύθυνος επεξεργασίας, όχι επειδή είναι
     * αθώο: το αρχείο του άρθρου 30 είναι ακριβώς αυτό που αποδεικνύει σε
     * έλεγχο τι έγινε και πότε. Η οθόνη το λέει ρητά πριν το εκτελέσει, και η
     * ίδια η διαγραφή αφήνει μια εγγραφή πίσω της.
     */
    @Query("DELETE FROM audit_log WHERE ts < :before")
    suspend fun wipeBefore(before: Long): Int
}

@Dao
interface ConsentDao {

    @Query("SELECT * FROM consents WHERE clientId = :clientId LIMIT 1")
    suspend fun forClient(clientId: Long): ConsentEntity?

    @Query("SELECT clientId FROM consents")
    suspend fun clientsWithConsent(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(consent: ConsentEntity)
}

@Dao
interface SendDao {

    @Insert
    suspend fun log(send: SendEntity): Long

    /** Όλες οι αποστολές σε ένα διάστημα — η τροφοδοσία του ημερολογίου. */
    @Query("SELECT * FROM sends WHERE sentAt >= :from AND sentAt < :to ORDER BY sentAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<SendEntity>>

    @Query("SELECT * FROM sends WHERE sentAt >= :from AND sentAt < :to ORDER BY sentAt DESC")
    suspend fun between(from: Long, to: Long): List<SendEntity>

    @Query("SELECT * FROM sends WHERE clientId = :clientId ORDER BY sentAt DESC LIMIT :limit")
    suspend fun forClient(clientId: Long, limit: Int = 100): List<SendEntity>

    @Query("SELECT * FROM sends WHERE clientId = :clientId ORDER BY sentAt DESC LIMIT :limit")
    fun observeForClient(clientId: Long, limit: Int = 200): Flow<List<SendEntity>>

    @Query("SELECT * FROM sends ORDER BY sentAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SendEntity>>
}

@Dao
interface RunLogDao {

    @Insert
    suspend fun log(entry: RunLogEntity): Long

    @Query("SELECT * FROM run_logs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<RunLogEntity>>

    @Query("SELECT * FROM run_logs WHERE afm = :afm ORDER BY startedAt DESC LIMIT :limit")
    suspend fun forAfm(afm: String, limit: Int = 50): List<RunLogEntity>

    @Query("DELETE FROM run_logs WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM run_logs")
    suspend fun wipe(): Int
}
