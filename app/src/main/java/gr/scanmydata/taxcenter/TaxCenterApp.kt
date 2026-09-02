package gr.scanmydata.taxcenter

import android.app.Application

/**
 * Σημείο εκκίνησης. Προς το παρόν κρατά μόνο μια αναφορά στον εαυτό της· οι
 * υπηρεσίες (βάση, engine, Google) στήνονται χειροκίνητα όπου χρειάζονται —
 * χωρίς DI framework, όπως και στο Prosfora-APK.
 */
class TaxCenterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TaxCenterApp
            private set
    }
}
