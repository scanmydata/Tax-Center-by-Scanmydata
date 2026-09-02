package gr.scanmydata.taxcenter.google

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Ό,τι χρειάζεται από το Drive v3, γραμμένο με OkHttp και `org.json`.
 *
 * **Χωρίς την επίσημη βιβλιοθήκη**, στο ίδιο πνεύμα με τον υπόλοιπο κώδικα: το
 * `google-api-services-drive` φέρνει δεκάδες MB και ένα ολόκληρο δέντρο
 * μεταβατικών εξαρτήσεων για πέντε κλήσεις HTTP.
 *
 * Το scope είναι `drive.file`. Πρακτικά αυτό σημαίνει ότι η εφαρμογή βλέπει
 * **μόνο τα αρχεία και τους φακέλους που δημιούργησε η ίδια** — δεν μπορεί να
 * διαβάσει, να απαριθμήσει ή να πειράξει τίποτα άλλο στον Drive του χρήστη. Έχει
 * και μια συνέπεια που πρέπει να ξέρει όποιος διαβάζει τον κώδικα: αν ο χρήστης
 * σβήσει τον φάκελο από το web interface, η εφαρμογή δεν τον «βλέπει» χαμένο —
 * απλώς δεν τον βρίσκει και τον ξαναφτιάχνει.
 */
class DriveClient(private val accessToken: String) {

    class DriveFailed(message: String) : Exception(message)

    data class Entry(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val modifiedTime: String,
    ) {
        val isFolder: Boolean get() = mimeType == FOLDER_MIME
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    // ------------------------------------------------------------- φάκελοι

    /**
     * Βρίσκει ή δημιουργεί φάκελο με το [name] μέσα στον [parentId].
     *
     * Η αναζήτηση γίνεται με `name = '…' and trashed = false`. Το `trashed`
     * είναι απαραίτητο: ένας φάκελος στον κάδο εξακολουθεί να επιστρέφεται και
     * τα ανεβάσματα σε αυτόν εξαφανίζονται σιωπηλά.
     */
    fun ensureFolder(name: String, parentId: String? = null): String {
        val query = buildString {
            append("mimeType = '").append(FOLDER_MIME).append("'")
            append(" and name = '").append(name.replace("'", "\\'")).append("'")
            append(" and trashed = false")
            if (parentId != null) append(" and '").append(parentId).append("' in parents")
        }
        find(query)?.let { return it.id }

        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .apply { if (parentId != null) put("parents", JSONArray().put(parentId)) }

        val request = Request.Builder()
            .url("$FILES?fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        return call(request).optString("id").ifBlank { throw DriveFailed("δεν δημιουργήθηκε φάκελος") }
    }

    /** Ο πρώτος φάκελος/αρχείο που ταιριάζει, ή `null`. */
    fun find(query: String): Entry? = list(query, pageSize = 1).firstOrNull()

    fun list(query: String, pageSize: Int = 100): List<Entry> {
        val url = "$FILES?q=" + URLEncoder.encode(query, "UTF-8") +
            "&pageSize=$pageSize&fields=files(id,name,mimeType,size,modifiedTime)"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val files = call(request).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            Entry(
                id = f.optString("id"),
                name = f.optString("name"),
                mimeType = f.optString("mimeType"),
                size = f.optString("size").toLongOrNull() ?: 0L,
                modifiedTime = f.optString("modifiedTime"),
            )
        }
    }

    // -------------------------------------------------------------- αρχεία

    /**
     * Ανεβάζει αρχείο. Όταν δοθεί [existingId], **αντικαθιστά** το περιεχόμενό
     * του αντί να δημιουργήσει δεύτερο.
     *
     * Ο Drive επιτρέπει δύο αρχεία με το ίδιο όνομα στον ίδιο φάκελο — γι' αυτό
     * το `existingId` δεν είναι βελτιστοποίηση αλλά προϋπόθεση ορθότητας: χωρίς
     * αυτό, κάθε επανασυγχρονισμός θα άφηνε διπλότυπα.
     */
    fun upload(
        file: File,
        parentId: String,
        name: String = file.name,
        mimeType: String = "application/octet-stream",
        existingId: String? = null,
    ): Entry {
        val metadata = JSONObject().put("name", name)
        if (existingId == null) metadata.put("parents", JSONArray().put(parentId))

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(file.asRequestBody(mimeType.toMediaType()))
            .build()

        val url = if (existingId == null) {
            "$UPLOAD?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime"
        } else {
            "$UPLOAD/$existingId?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime"
        }

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
        val request = if (existingId == null) builder.post(body) else builder.patch(body)

        val json = call(request.build())
        return Entry(
            id = json.optString("id"),
            name = json.optString("name", name),
            mimeType = json.optString("mimeType", mimeType),
            size = json.optString("size").toLongOrNull() ?: file.length(),
            modifiedTime = json.optString("modifiedTime"),
        )
    }

    fun download(fileId: String, target: File) {
        val request = Request.Builder()
            .url("$FILES/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DriveFailed(errorOf(response.body?.string().orEmpty(), response.code))
            }
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
            }
        }
    }

    /** Στον κάδο, όχι οριστικά: μια λάθος διαγραφή πρέπει να είναι ανακτήσιμη. */
    fun trash(fileId: String) {
        val request = Request.Builder()
            .url("$FILES/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .patch(JSONObject().put("trashed", true).toString().toRequestBody(JSON))
            .build()
        call(request)
    }

    // ------------------------------------------------------------ εσωτερικά

    private fun call(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw DriveFailed(errorOf(text, response.code))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun errorOf(body: String, code: Int): String = runCatching {
        JSONObject(body).getJSONObject("error").getString("message")
    }.getOrDefault("HTTP $code")

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
