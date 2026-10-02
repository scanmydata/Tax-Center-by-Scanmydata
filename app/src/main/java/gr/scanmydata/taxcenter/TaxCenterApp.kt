package gr.scanmydata.taxcenter

import android.app.Application
import gr.scanmydata.taxcenter.ui.AppContainer

/**
 * Σημείο εκκίνησης. Κρατά το **ένα** [AppContainer] της διεργασίας — χωρίς DI
 * framework, όπως και στο Prosfora-APK.
 *
 * Το container ζούσε στο `MainActivity`. Από τη στιγμή που υπάρχει δουλειά
 * χωρίς οθόνη (η αυτόματη ενημέρωση οφειλών), αυτό δεν αρκεί — και δεν είναι
 * θέμα τάξης: η ουρά λήψης πρέπει να είναι **μία**, αλλιώς μια προγραμματισμένη
 * ενημέρωση και μια λήψη του χρήστη θα έτρεχαν ταυτόχρονα σε δύο ουρές, δηλαδή
 * δύο συνεδρίες GSIS για τον ίδιο λογαριασμό.
 */
class TaxCenterApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TaxCenterApp
            private set
    }
}
