package gr.scanmydata.taxcenter.requests

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **Ένα** αίτημα προς την ΑΑΔΕ, ολόκληρο: το κείμενο, η απάντηση, και τα
 * συνημμένα — όσα υπέβαλε ο πελάτης και όσα έστειλε η υπηρεσία.
 *
 * Η λίστα των αιτημάτων ([AadeRequests]) αρκεί για να φανεί **ότι** απαντήθηκε
 * κάτι. Δεν αρκεί για να γίνει η δουλειά: η απάντηση της ΑΑΔΕ είναι πολύ συχνά
 * ένα έγγραφο — πράξη, βεβαίωση, απόφαση — και το κείμενο λέει μόνο «σας
 * επισυνάπτουμε». Αυτό το έγγραφο δεν υπάρχει στη λίστα.
 *
 * ## Το σχήμα
 *
 * Το `aade-request` γράφει `AITIMA_<id>.json` με:
 *
 *  * `detail` — η απάντηση του `eticketaade/api/amsmsg/fetch`, αυτούσια,
 *  * `comm` — η μετέπειτα αλληλογραφία (`getMessageComm`), αυτούσια,
 *  * `files[]` — κάθε συνημμένο με την προέλευσή του και το όνομα με το οποίο
 *    σώθηκε στον ίδιο φάκελο (`savedFile`), ή το σφάλμα του.
 *
 * Επαληθεύτηκε ζωντανά στις 9 Οκτωβρίου 2026. Η προέλευση (`fileOrigin`) είναι
 * `attachedFile` για ό,τι υπέβαλε ο πολίτης, `certificateFile` για το έγγραφο
 * εκπροσώπησης και `internalFile` για τα έγγραφα της υπηρεσίας — έτσι τα
 * χωρίζει και η ίδια η πύλη στην οθόνη της.
 *
 * **Η αλληλογραφία δεν έχει φανεί με περιεχόμενο**: σε όλα τα αιτήματα που
 * είδαμε ήταν κενή. Γι' αυτό διαβάζεται εδώ χωρίς να υποθέτει ονόματα πεδίων —
 * το πρώτο κείμενο και η πρώτη ημερομηνία κάθε εγγραφής — και ό,τι δεν
 * αναγνωριστεί απλώς δεν εμφανίζεται, αντί να εμφανιστεί λάθος.
 *
 * Καθαρή Kotlin, χωρίς Android: ελέγχεται με τεστ.
 */
object RequestDetail {

    const val CONFIG = "aade-request"

    /** Το input του config: ο αριθμός του αιτήματος (`messageId`). */
    const val INPUT_ID = "id"

    enum class Origin(val label: String) {
        SUBMITTED("υποβλήθηκε με το αίτημα"),
        CERTIFICATE("έγγραφο εκπροσώπησης"),
        REPLY("έγγραφο της απάντησης"),
        THREAD("από τη μετέπειτα αλληλογραφία"),
        OTHER("συνημμένο");

        companion object {
            fun of(raw: String): Origin = when (raw.trim()) {
                "attachedFile" -> SUBMITTED
                "certificateFile" -> CERTIFICATE
                "internalFile" -> REPLY
                "commFile" -> THREAD
                else -> OTHER
            }
        }
    }

    data class Attachment(
        val id: String,
        /** Το όνομα όπως το έχει η πύλη — αυτό βλέπει ο χρήστης. */
        val name: String,
        val origin: Origin,
        /** Bytes, όπως τα δηλώνει η πύλη. `0` όταν δεν τα δίνει. */
        val size: Long = 0L,
        /** Το όνομα στον δίσκο. Κενό όταν το αρχείο **δεν** κατέβηκε. */
        val saved: String = "",
        val error: String = "",
    ) {
        val downloaded: Boolean get() = saved.isNotBlank()
    }

    /** Μία εγγραφή της μετέπειτα αλληλογραφίας. */
    data class Note(val date: String, val text: String)

    data class Detail(
        val id: String,
        val caseNumber: String = "",
        val topic: String = "",
        val process: String = "",
        val service: String = "",
        val text: String = "",
        val answer: String = "",
        /** Τα δικαιολογητικά που ορίζει η διαδικασία, όπως τα γράφει η πύλη. */
        val supporting: String = "",
        val reviewReason: String = "",
        val rejectReason: String = "",
        /** Αριθμός άλλου αιτήματος με το οποίο σχετίζεται. */
        val reference: String = "",
        val archived: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
        val thread: List<Note> = emptyList(),
        /** Πότε διαβάστηκε από την πύλη. `0` όταν το αρχείο δεν το γράφει. */
        val retrievedAt: Long = 0L,
    ) {
        /** Ό,τι έφυγε **από** τον πελάτη: τα συνημμένα του και η εξουσιοδότηση. */
        val submitted: List<Attachment>
            get() = attachments.filter { it.origin == Origin.SUBMITTED || it.origin == Origin.CERTIFICATE || it.origin == Origin.OTHER }

        /** Ό,τι ήρθε **από** την ΑΑΔΕ. */
        val replies: List<Attachment> get() = attachments.filter { it.origin == Origin.REPLY }

        val threadFiles: List<Attachment> get() = attachments.filter { it.origin == Origin.THREAD }

        /** Συνημμένα που υπάρχουν στην πύλη και δεν κατέβηκαν. */
        val missing: Int get() = attachments.count { !it.downloaded }
    }

    /** Το αρχείο όπου γράφει το config το αίτημα [id]. */
    fun fileName(id: String): String = "AITIMA_" + id.filter { it.isDigit() } + ".json"

    /** `null` όταν το JSON δεν είναι αίτημα — ποτέ ένα άδειο αίτημα στη θέση του. */
    fun parse(json: String): Detail? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val d = root.optJSONObject("detail") ?: return null
        val id = text(root, "messageId")
        if (id.isBlank()) return null
        val files = root.optJSONArray("files") ?: JSONArray()
        return Detail(
            id = id,
            caseNumber = text(d, "caseNumber"),
            topic = text(d, "thematicalGroupText").ifBlank { text(d, "processGroupText") },
            process = text(d, "diadikasiaText"),
            service = listOf(text(d, "orgGroupText"), text(d, "protipoTmimaText"))
                .filter { it.isNotBlank() }.distinct().joinToString(" · "),
            text = plain(text(d, "messageText")),
            answer = plain(text(d, "answerText")),
            supporting = plain(text(d, "supportingDocs")),
            reviewReason = plain(text(d, "reviewReason")),
            rejectReason = plain(text(d, "rejectReason")),
            reference = text(d, "messageReferenceId"),
            archived = d.optBoolean("isArchived", false),
            attachments = (0 until files.length())
                .mapNotNull { i -> files.optJSONObject(i)?.let(::attachment) }
                .distinctBy { it.id },
            thread = notes(root.opt("comm")),
            retrievedAt = runCatching { Instant.parse(text(root, "retrievedAt")).toEpochMilli() }.getOrDefault(0L),
        )
    }

    private fun attachment(o: JSONObject): Attachment? {
        val id = text(o, "fileId")
        if (id.isBlank()) return null
        return Attachment(
            id = id,
            name = text(o, "fileName").ifBlank { "αρχείο $id" },
            origin = Origin.of(text(o, "fileOrigin")),
            size = o.optLong("fileSize", 0L).coerceAtLeast(0L),
            saved = text(o, "savedFile"),
            error = text(o, "error"),
        )
    }

    /**
     * Η αλληλογραφία, χωρίς υποθέσεις για τα ονόματα των πεδίων: από κάθε
     * εγγραφή, το πρώτο πεδίο που μοιάζει με κείμενο και το πρώτο που μοιάζει
     * με ημερομηνία. Εγγραφή χωρίς κείμενο δεν εμφανίζεται.
     */
    private fun notes(raw: Any?): List<Note> {
        val items = raw as? JSONArray ?: return emptyList()
        val out = ArrayList<Note>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val keys = o.keys().asSequence().toList()
            val body = keys.filter { TEXT_KEY.containsMatchIn(it) }
                .map { plain(text(o, it)) }.firstOrNull { it.isNotBlank() } ?: continue
            val date = keys.filter { it.contains("date", ignoreCase = true) }
                .map { text(o, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            out += Note(date, body)
        }
        return out
    }

    private val TEXT_KEY = Regex("text$", RegexOption.IGNORE_CASE)

    /**
     * Χρειάζεται νέα λήψη;
     *
     * Ναι όταν η λίστα λέει ότι το αίτημα **ενημερώθηκε μετά** από τη στιγμή
     * που το διαβάσαμε. Αυτή είναι ακριβώς η περίπτωση που έχει σημασία: το
     * αίτημα κατέβηκε όσο περίμενε, και στο μεταξύ απαντήθηκε — τα παλιά
     * συνημμένα θα έδειχναν ένα αίτημα χωρίς απάντηση.
     *
     * @param updated η `updatedDate` της λίστας, «yyyy-MM-dd HH:mm:ss», ώρα Ελλάδας
     */
    fun stale(retrievedAt: Long, updated: String, zone: ZoneId = ATHENS): Boolean {
        if (retrievedAt <= 0L) return true
        val at = runCatching {
            LocalDateTime.parse(updated.trim().take(19).replace('T', ' '), STAMP).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        return at > retrievedAt
    }

    private val ATHENS: ZoneId = ZoneId.of("Europe/Athens")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** «1288743» -> «1,2 MB». Για να ξέρει ο χρήστης τι θα ανοίξει. */
    fun size(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${(bytes + 512L) / 1024L} KB"
        else -> {
            val tenths = (bytes * 10L + 524_288L) / 1_048_576L
            "${tenths / 10},${tenths % 10} MB"
        }
    }

    // -------------------------------------------------------------- βοηθοί

    /**
     * Τα κείμενα της πύλης είναι σκέτο κείμενο — αλλά περνούν από φόρμα ιστού,
     * και ένα `<br>` ή ένα `&amp;` που θα τύχει δεν πρέπει να φανεί ως έχει.
     */
    internal fun plain(raw: String): String {
        if (raw.indexOf('<') < 0 && raw.indexOf('&') < 0) return raw.trim()
        return raw
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[a-zA-Z/][^>]*>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            // Τελευταίο: αλλιώς το «&amp;lt;» θα γινόταν «<» αντί για «&lt;».
            .replace("&amp;", "&")
            .replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Το `optString` γυρίζει τη λέξη «null» για JSON null· εδώ θέλουμε κενό. */
    private fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key).trim()
}
