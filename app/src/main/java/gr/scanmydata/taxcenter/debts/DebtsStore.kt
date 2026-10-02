package gr.scanmydata.taxcenter.debts

import gr.scanmydata.taxcenter.engine.FileBridge
import java.io.File

/**
 * Διαβάζει την τελευταία εικόνα οφειλών ενός πελάτη από τον δίσκο.
 *
 * ## Γιατί από τα αρχεία της λήψης και όχι από πίνακα της βάσης
 *
 * Τα JSON υπάρχουν ήδη: τα γράφει κάθε λήψη, μέσα στον ιδιωτικό φάκελο της
 * εφαρμογής, και σβήνονται μαζί με τον πελάτη. Ένας πίνακας θα ήταν δεύτερο
 * αντίγραφο των ίδιων ποσών, με δικό του κύκλο ζωής — και την ημέρα που τα δύο
 * θα διέφεραν, η καρτέλα θα έδειχνε άλλο ποσό από το έντυπο που στάλθηκε.
 *
 * Η ώρα ενημέρωσης είναι η ώρα τροποποίησης του αρχείου: μια λήψη που
 * **απέτυχε** δεν το ξαναγράφει, οπότε η καρτέλα συνεχίζει να δείχνει την
 * τελευταία καλή εικόνα με τη σωστή της ημερομηνία.
 *
 * Διαβάζει δίσκο — όχι από το κύριο νήμα.
 */
object DebtsStore {

    const val CONFIG_AADE = "aade-debts"
    const val CONFIG_KEAO = "keao-debts"

    fun load(filesDir: File, afm: String): Debts.Snapshot {
        if (afm.isBlank()) return Debts.Snapshot()
        val aade = newest(dir(filesDir, afm, CONFIG_AADE), "AADE_debts_")
        val keao = newest(dir(filesDir, afm, CONFIG_KEAO), "KEAO_ofeiles_")
        val aadeJson = aade?.let(::read).orEmpty()
        val keaoJson = keao?.let(::read).orEmpty()
        return Debts.Snapshot(
            lines = Debts.aade(aadeJson) + Debts.keao(keaoJson),
            aadeAt = aade?.lastModified() ?: 0L,
            keaoAt = keao?.lastModified() ?: 0L,
            keaoPartial = Debts.keaoFilter(keaoJson),
        )
    }

    /** Ο φάκελος όπου γράφει το `ProcessRunner` — ίδιος κανόνας ονομάτων. */
    private fun dir(filesDir: File, afm: String, config: String): File = File(
        filesDir,
        "runs/${FileBridge.sanitiseSegment(afm)}/${FileBridge.sanitiseSegment(config)}",
    )

    /**
     * Το νεότερο JSON με αυτό το πρόθεμα. Το όνομα δεν είναι σταθερό — το
     * `aade-debts` το χτίζει από τον λογαριασμό που συνδέθηκε — οπότε ψάχνουμε
     * με πρόθεμα και κρατάμε το πιο πρόσφατο.
     */
    private fun newest(dir: File, prefix: String): File? = dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
        ?.maxByOrNull { it.lastModified() }

    private fun read(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
}
