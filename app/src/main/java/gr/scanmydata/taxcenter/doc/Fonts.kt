package gr.scanmydata.taxcenter.doc

import android.content.Context

/**
 * Φορτώνει τις γραμματοσειρές των εντύπων από τα assets, μία φορά.
 *
 * Τα 36 KB της καθεμιάς διαβάζονται σε κάθε έντυπο αλλιώς — και μια παρτίδα
 * λήψης βγάζει δεκάδες. Η μνήμη είναι μικρότερη από τη ζημιά.
 *
 * Αν λείψουν τα αρχεία, η εξαίρεση ανεβαίνει: ένα έντυπο χωρίς γραμματοσειρά
 * δεν έχει νόημα να γραφτεί, και ο καλών ξέρει ήδη πώς να το χειριστεί (τα
 * δεδομένα μένουν στο JSON και η λήψη δεν πέφτει).
 */
object Fonts {

    @Volatile
    private var cached: ReportFonts? = null

    fun of(context: Context): ReportFonts = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): ReportFonts {
        val assets = context.assets
        fun font(file: String, name: String): PdfFont {
            val program = assets.open("fonts/$file.ttf").use { it.readBytes() }
            val metrics = assets.open("fonts/$file.json").use { it.readBytes() }
                .toString(Charsets.UTF_8)
            return PdfFont(name, program, metrics)
        }
        return ReportFonts(
            regular = font("report-regular", "SMDATA+DejaVuSans"),
            bold = font("report-bold", "SMDATA+DejaVuSans-Bold"),
        )
    }
}
