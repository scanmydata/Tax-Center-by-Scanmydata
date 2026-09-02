package gr.scanmydata.taxcenter.mail

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.activation.DataHandler
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * Αποστολή μέσω Gmail REST API.
 *
 * Χτίζουμε ολόκληρο το MIME με JavaMail και το στέλνουμε base64url στο
 * `users/me/messages/send`. Δεν χρησιμοποιείται SMTP: το `gmail.send` scope
 * αρκεί, δεν θέλει κωδικό εφαρμογής, και τα μηνύματα εμφανίζονται κανονικά στα
 * Απεσταλμένα του λογιστή.
 *
 * **Καμία κεφαλίδα `From:`**: το `gmail.send` δεν δίνει δικαίωμα ανάγνωσης της
 * διεύθυνσης του λογαριασμού. Ο Gmail τη συμπληρώνει μόνος του.
 */
class GmailSender(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    data class Attachment(val name: String, val bytes: ByteArray, val mimeType: String = "application/pdf") {
        // ByteArray σε data class: equals/hashCode γράφονται ρητά, αλλιώς
        // συγκρίνουν αναφορές και το Kotlin βγάζει προειδοποίηση.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Attachment && name == other.name && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()

        companion object {
            fun of(file: File, mimeType: String = "application/pdf") =
                Attachment(file.name, file.readBytes(), mimeType)
        }
    }

    class SendFailed(message: String) : Exception(message)

    /**
     * Στέλνει ένα μήνυμα. Πετάει [SendFailed] με το μήνυμα του Gmail.
     *
     * Το [bodyHtml] είναι προαιρετικό· όταν δίνεται, το μήνυμα φεύγει ως
     * `multipart/alternative` ώστε να διαβάζεται και σε clients χωρίς HTML.
     */
    fun send(
        accessToken: String,
        to: String,
        subject: String,
        bodyText: String,
        bodyHtml: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): String {
        val raw = buildMime(to, subject, bodyText, bodyHtml, attachments)
        val payload = JSONObject().put("raw", raw).toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrDefault("HTTP ${response.code}")
                throw SendFailed(message)
            }
            return runCatching { JSONObject(body).optString("id") }.getOrDefault("")
        }
    }

    private fun buildMime(
        to: String,
        subject: String,
        bodyText: String,
        bodyHtml: String?,
        attachments: List<Attachment>,
    ): String {
        val session = Session.getInstance(Properties())
        val message = MimeMessage(session)
        message.setRecipient(MimeMessage.RecipientType.TO, InternetAddress(to))
        // Το UTF-8 είναι ρητό: τα θέματα είναι ελληνικά και αλλιώς φτάνουν σπασμένα.
        message.setSubject(subject, "UTF-8")

        val textPart = MimeBodyPart().apply { setText(bodyText, "UTF-8") }

        val contentPart: MimeBodyPart = if (bodyHtml == null) {
            textPart
        } else {
            val alternative = MimeMultipart("alternative")
            alternative.addBodyPart(textPart)
            alternative.addBodyPart(MimeBodyPart().apply { setContent(bodyHtml, "text/html; charset=UTF-8") })
            MimeBodyPart().apply { setContent(alternative) }
        }

        if (attachments.isEmpty()) {
            message.setContent(contentPart.content, contentPart.contentType)
        } else {
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(contentPart)
            for (a in attachments) {
                mixed.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(a.bytes, a.mimeType))
                        fileName = a.name
                    },
                )
            }
            message.setContent(mixed)
        }
        message.saveChanges()

        val buffer = ByteArrayOutputStream()
        message.writeTo(buffer)
        return Base64.encodeToString(
            buffer.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private companion object {
        const val ENDPOINT = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
    }
}
