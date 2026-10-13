package gr.scanmydata.taxcenter.requests

import gr.scanmydata.taxcenter.data.db.SendEntity

/**
 * Ό,τι **φεύγει** από την εφαρμογή για ένα αίτημα ΑΑΔΕ, όπως γράφεται στο
 * ημερολόγιο αποστολών.
 *
 * Το ημερολόγιο απαντά στο «τι έφυγε, πότε, για ποιον». Μέχρι τώρα ήξερε μόνο
 * email, Viber και SMS· τα αιτήματα έχουν δύο δικές τους κινήσεις:
 *
 *  * η **ειδοποίηση** της συσκευής, όταν ο αυτόματος έλεγχος βρει απάντηση ή
 *    αλλαγή — για να φαίνεται μετά πότε το έμαθε το γραφείο,
 *  * η **κοινοποίηση ενός συνημμένου** σε άλλη εφαρμογή — το έγγραφο της ΑΑΔΕ
 *    που προωθήθηκε στον πελάτη.
 *
 * Καθαρή Kotlin: τι ακριβώς γράφεται ελέγχεται με τεστ. Στο ημερολόγιο μπαίνουν
 * είδος αιτήματος, αριθμός υπόθεσης και όνομα αρχείου — όπως και για τα έντυπα.
 * Ποτέ το κείμενο του αιτήματος ή της απάντησης.
 */
object RequestSends {

    const val TO_DEVICE = "ειδοποίηση στη συσκευή"
    const val TO_APP = "κοινοποίηση σε άλλη εφαρμογή"
    const val NOTIFICATIONS_OFF =
        "Οι ειδοποιήσεις της εφαρμογής είναι κλειστές στο Android — η αλλαγή βρέθηκε, " +
            "αλλά η συσκευή δεν ειδοποίησε."

    /**
     * Η ειδοποίηση για τις αλλαγές ενός πελάτη.
     *
     * Μία εγγραφή **ανά πελάτη**, ενώ η ειδοποίηση του Android είναι μία για
     * όλους και γράφει μόνο πλήθη: το ημερολόγιο είναι μέσα στην κλειδωμένη
     * εφαρμογή και μπορεί να πει ποιον αφορά.
     *
     * @param delivered αν το Android επιτρέπει ειδοποιήσεις στην εφαρμογή. Όταν
     *   όχι, γράφεται ως **αποτυχία** — αλλιώς το ημερολόγιο θα έλεγε ότι ο
     *   λογιστής ειδοποιήθηκε για κάτι που δεν είδε ποτέ.
     */
    fun notice(
        clientId: Long,
        afm: String,
        clientName: String,
        changes: List<AadeRequests.Change>,
        at: Long,
        delivered: Boolean,
    ): SendEntity {
        val answered = changes.count { it.kind == AadeRequests.Kind.ANSWERED }
        return SendEntity(
            clientId = clientId,
            afm = afm,
            clientName = clientName,
            toEmail = TO_DEVICE,
            subject = when {
                answered == 1 && changes.size == 1 -> "Νέα απάντηση της ΑΑΔΕ σε αίτημα"
                answered == changes.size -> "Νέες απαντήσεις της ΑΑΔΕ σε $answered αιτήματα"
                answered == 0 && changes.size == 1 -> "Αλλαγή κατάστασης σε αίτημα ΑΑΔΕ"
                answered == 0 -> "Αλλαγή κατάστασης σε ${changes.size} αιτήματα ΑΑΔΕ"
                else -> "Αλλαγές σε ${changes.size} αιτήματα ΑΑΔΕ"
            },
            kind = SendEntity.KIND_REQUEST_NOTICE,
            items = changes.joinToString("\n", transform = ::line),
            itemCount = changes.size,
            sentAt = at,
            status = if (delivered) SendEntity.STATUS_SENT else SendEntity.STATUS_FAILED,
            error = if (delivered) "" else NOTIFICATIONS_OFF,
        )
    }

    /** «Τροποποιητική δήλωση Ε1 — νέα απάντηση (Αρ. 1000001/…)». */
    private fun line(change: AadeRequests.Change): String {
        val request = change.request
        val what = when (change.kind) {
            AadeRequests.Kind.ANSWERED -> "νέα απάντηση"
            AadeRequests.Kind.STATUS -> "κατάσταση: " + request.status.ifBlank { "άλλαξε" }
        }
        return request.title + " — " + what +
            if (request.caseNumber.isBlank()) "" else " (Αρ. " + request.caseNumber + ")"
    }

    /**
     * Ένα συνημμένο αιτήματος που δόθηκε σε άλλη εφαρμογή.
     *
     * «Παραδόθηκε», όχι «στάλθηκε»: από τον επιλογέα κοινοποίησης και πέρα
     * αποφασίζει ο άνθρωπος, και δεν μαθαίνουμε αν το έστειλε — ο ίδιος κανόνας
     * με το Viber.
     *
     * @param error μη κενό όταν ο επιλογέας δεν άνοιξε
     */
    fun file(
        clientId: Long,
        afm: String,
        clientName: String,
        caseNumber: String,
        fileName: String,
        at: Long,
        error: String = "",
    ): SendEntity = SendEntity(
        clientId = clientId,
        afm = afm,
        clientName = clientName,
        toEmail = TO_APP,
        subject = "Συνημμένο αιτήματος ΑΑΔΕ" + if (caseNumber.isBlank()) "" else " $caseNumber",
        kind = SendEntity.KIND_REQUEST_FILE,
        items = fileName,
        itemCount = 1,
        sentAt = at,
        status = if (error.isBlank()) SendEntity.STATUS_HANDED else SendEntity.STATUS_FAILED,
        error = error,
    )
}
