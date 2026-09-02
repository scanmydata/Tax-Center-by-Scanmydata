package gr.scanmydata.taxcenter.google

import android.content.Context
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Αντίγραφο ασφαλείας στο Google Drive, κρυπτογραφημένο **πριν** ανέβει.
 *
 * Η βάση είναι ήδη SQLCipher, αλλά το κλειδί της ζει στο Keystore της συσκευής
 * και **δεν εξάγεται ποτέ**. Άρα ένα σκέτο αντίγραφο του αρχείου θα ήταν άχρηστο
 * σε καινούργιο κινητό: σωστό ως προς την ασφάλεια, άχρηστο ως αντίγραφο.
 *
 * Γι' αυτό το αντίγραφο ξανακρυπτογραφείται με κλειδί που παράγεται από
 * **passphrase του χρήστη** (PBKDF2-HMAC-SHA256, 210.000 επαναλήψεις, τυχαίο
 * salt) και AES-256-GCM. Η Google βλέπει μόνο κρυπτογράφημα.
 *
 * **Χαμένη passphrase = χαμένο αντίγραφο.** Δεν υπάρχει ανάκτηση, ούτε από εμάς
 * ούτε από την Google — αυτό είναι το νόημα.
 *
 * Το scope είναι `drive.file`: η εφαρμογή βλέπει μόνο ό,τι δημιούργησε η ίδια,
 * ποτέ τον υπόλοιπο Drive του χρήστη.
 */
class DriveBackup(
    private val context: Context,
    private val db: TaxCenterDatabase,
) {

    data class Entry(val id: String, val name: String, val createdTime: String, val size: Long)

    class BackupFailed(message: String) : Exception(message)

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    // ------------------------------------------------------------- ανέβασμα

    suspend fun upload(accessToken: String, passphrase: String): Entry {
        require(passphrase.length >= MIN_PASSPHRASE) {
            "Η passphrase πρέπει να έχει τουλάχιστον $MIN_PASSPHRASE χαρακτήρες."
        }
        val plain = snapshot()
        val payload = try {
            encrypt(plain.readBytes(), passphrase)
        } finally {
            plain.delete()
        }

        val name = "taxcenter-backup-${System.currentTimeMillis()}.smdbk"
        val metadata = JSONObject()
            .put("name", name)
            .put("description", "ScanMyData Tax Center — κρυπτογραφημένο αντίγραφο")
            .toString()

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(payload.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$UPLOAD?uploadType=multipart&fields=id,name,createdTime,size")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BackupFailed(errorOf(text, response.code))
            val json = JSONObject(text)
            return Entry(
                id = json.optString("id"),
                name = json.optString("name"),
                createdTime = json.optString("createdTime"),
                size = json.optString("size").toLongOrNull() ?: payload.size.toLong(),
            )
        }
    }

    // ------------------------------------------------------------- κατάλογος

    fun list(accessToken: String): List<Entry> {
        val url = "$FILES?q=" + java.net.URLEncoder.encode("name contains 'taxcenter-backup'", "UTF-8") +
            "&orderBy=createdTime desc&pageSize=25&fields=files(id,name,createdTime,size)"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BackupFailed(errorOf(text, response.code))
            val files = JSONObject(text).optJSONArray("files") ?: return emptyList()
            return (0 until files.length()).map { i ->
                val f = files.getJSONObject(i)
                Entry(
                    id = f.optString("id"),
                    name = f.optString("name"),
                    createdTime = f.optString("createdTime"),
                    size = f.optString("size").toLongOrNull() ?: 0L,
                )
            }
        }
    }

    // ------------------------------------------------------------ επαναφορά

    /**
     * Κατεβάζει και αποκρυπτογραφεί ένα αντίγραφο, και **αντικαθιστά** τη βάση.
     *
     * Η εφαρμογή πρέπει να κλείσει αμέσως μετά: η Room κρατά ανοιχτό handle στο
     * παλιό αρχείο, και οποιαδήποτε εγγραφή μετά την αντικατάσταση θα το
     * κατέστρεφε. Ο καλών είναι υπεύθυνος να τερματίσει.
     */
    fun restore(accessToken: String, entry: Entry, passphrase: String) {
        val request = Request.Builder()
            .url("$FILES/${entry.id}?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .build()
        val cipherBytes = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw BackupFailed(errorOf(response.body?.string().orEmpty(), response.code))
            }
            response.body?.bytes() ?: throw BackupFailed("κενή απάντηση")
        }

        val plain = decrypt(cipherBytes, passphrase)

        // Γράφεται πρώτα δίπλα και μετά μετακινείται: μια διακοπή στη μέση δεν
        // πρέπει να αφήσει μισή βάση.
        val target = TaxCenterDatabase.file(context)
        val staging = File(target.parentFile, target.name + ".restore")
        staging.writeBytes(plain)
        // Τα -wal/-shm του παλιού αρχείου δεν ταιριάζουν πια με το νέο.
        File(target.parentFile, target.name + "-wal").delete()
        File(target.parentFile, target.name + "-shm").delete()
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
    }

    // ------------------------------------------------------------ εσωτερικά

    /**
     * Στιγμιότυπο της βάσης σε προσωρινό αρχείο.
     *
     * Πριν από την αντιγραφή γίνεται `wal_checkpoint(FULL)`: με WAL, οι
     * τελευταίες εγγραφές ζουν στο `-wal` και όχι στο `.db`. Ένα σκέτο copy θα
     * έχανε ό,τι έγινε μετά το τελευταίο checkpoint — δηλαδή, τυπικά, τη
     * δουλειά της ημέρας.
     */
    private fun snapshot(): File {
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        }
        val source = TaxCenterDatabase.file(context)
        if (!source.exists()) throw BackupFailed("Η βάση δεν υπάρχει ακόμη.")
        val temp = File(context.cacheDir, "backup-staging.db")
        source.copyTo(temp, overwrite = true)
        return temp
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** `MAGIC | salt(16) | iv(12) | ciphertext+tag` */
    private fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain)
        return MAGIC + salt + iv + body
    }

    private fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        val header = MAGIC.size + 16 + 12
        if (blob.size < header || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupFailed("Το αρχείο δεν είναι αντίγραφο του Tax Center.")
        }
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = blob.copyOfRange(MAGIC.size + 16, header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(blob, header, blob.size - header)
        } catch (e: Exception) {
            // Το GCM αποτυγχάνει ταυτόχρονα για λάθος passphrase και για
            // αλλοιωμένο αρχείο — δεν μπορούμε να τα ξεχωρίσουμε, και δεν
            // πρέπει: και τα δύο σημαίνουν «μην το εμπιστευτείς».
            throw BackupFailed("Λάθος passphrase ή κατεστραμμένο αρχείο.")
        }
    }

    private fun errorOf(body: String, code: Int): String = runCatching {
        JSONObject(body).getJSONObject("error").getString("message")
    }.getOrDefault("HTTP $code")

    private companion object {
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val ITERATIONS = 210_000
        const val MIN_PASSPHRASE = 12
        val MAGIC = "SMDBK1  ".toByteArray(Charsets.US_ASCII)
    }
}
