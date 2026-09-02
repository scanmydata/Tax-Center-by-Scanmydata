package gr.scanmydata.taxcenter.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Ανάγνωση `.xlsx` χωρίς Apache POI.
 *
 * Δύο λόγοι:
 *
 *  1. **Τα exports της ΑΑΔΕ και των λογιστικών προγραμμάτων σπάνε τους κανονικούς
 *     parsers.** Το `timologio-downloader` το έχει τεκμηριώσει: το openpyxl
 *     πέφτει με «could not read stylesheet ... invalid XML», και το pandas από
 *     κάτω μαζί. Εμείς διαβάζουμε μόνο τιμές — τα styles δεν μας αφορούν.
 *  2. Το POI είναι δεκάδες MB για μια δουλειά που κάνουν 150 γραμμές.
 *
 * Διαβάζουμε με SAX (όχι XmlPullParser) ώστε τα unit tests να τρέχουν σε σκέτη
 * JVM, χωρίς Robolectric.
 */
object XlsxReader {

    /** Ένα φύλλο: γραμμές, κάθε μία `γράμμα στήλης -> τιμή`. Τα κενά κελιά λείπουν. */
    data class Sheet(val name: String, val rows: List<Map<String, String>>)

    class NotAnXlsxException(message: String) : IOException(message)

    /**
     * Διαβάζει όλα τα φύλλα, με τη σειρά που εμφανίζονται στο αρχείο.
     *
     * Το [input] καταναλώνεται σε ένα πέρασμα: τα `sharedStrings.xml` και τα
     * φύλλα κρατούνται προσωρινά στη μνήμη γιατί το zip δεν εγγυάται σειρά, και
     * τα αρχεία αυτά είναι μικρά (δεκάδες KB).
     */
    fun read(input: InputStream): List<Sheet> {
        val sheetXml = LinkedHashMap<String, ByteArray>()
        var sharedXml: ByteArray? = null
        var sawZipEntry = false

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                sawZipEntry = true
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedXml = zip.readBytes()
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") ->
                        sheetXml[name] = zip.readBytes()
                }
            }
        }

        if (!sawZipEntry) {
            throw NotAnXlsxException(
                "Το αρχείο δεν είναι .xlsx. Οι μορφές .xls και .csv δεν υποστηρίζονται — " +
                    "αποθηκεύστε το ως .xlsx από το πρόγραμμά σας.",
            )
        }
        if (sheetXml.isEmpty()) {
            throw NotAnXlsxException("Το .xlsx δεν περιέχει φύλλα εργασίας.")
        }

        val shared = sharedXml?.let(::parseSharedStrings) ?: emptyList()
        return sheetXml.entries
            .sortedBy { sheetNumber(it.key) }
            .map { (path, bytes) -> Sheet(path, parseSheet(bytes, shared)) }
    }

    /** `xl/worksheets/sheet12.xml` -> 12, ώστε το sheet10 να μην έρχεται πριν το sheet2. */
    private fun sheetNumber(path: String): Int =
        Regex("""sheet(\d+)\.xml$""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * `<si>` -> κείμενο. Ένα `<si>` μπορεί να έχει πολλά `<t>` (rich text με
     * διαφορετική μορφοποίηση ανά κομμάτι) που πρέπει να ενωθούν.
     */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inText = false

        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (qName) {
                    "si" -> current.setLength(0)
                    "t" -> inText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "t" -> inText = false
                    "si" -> out.add(current.toString())
                }
            }
        })
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<Map<String, String>> {
        val rows = ArrayList<Map<String, String>>()
        var row: LinkedHashMap<String, String>? = null
        var cellRef = ""
        var cellType = ""
        val value = StringBuilder()
        var capturing = false

        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                when (qName) {
                    "row" -> row = LinkedHashMap()
                    "c" -> {
                        cellRef = attrs?.getValue("r") ?: ""
                        cellType = attrs?.getValue("t") ?: ""
                        value.setLength(0)
                    }
                    // <v> αριθμός ή δείκτης shared string· <t> κείμενο inline.
                    "v", "t" -> capturing = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capturing) value.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "v", "t" -> capturing = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (cellType) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            else -> raw
                        }.trim()
                        if (text.isNotEmpty()) {
                            column(cellRef)?.let { col -> row?.put(col, text) }
                        }
                        value.setLength(0)
                    }
                    "row" -> {
                        row?.let { rows.add(it) }
                        row = null
                    }
                }
            }
        })
        return rows
    }

    /** `BI42` -> `BI`. */
    fun column(cellRef: String): String? {
        val letters = cellRef.takeWhile { it.isLetter() }
        return letters.ifEmpty { null }
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            // Τα .xlsx δεν έχουν λόγο να φορτώνουν εξωτερικές οντότητες, και ένα
            // αρχείο πελάτη είναι μη έμπιστη είσοδος (XXE).
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }
}
