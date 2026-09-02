package gr.scanmydata.taxcenter.ui

import android.content.Context
import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.Crypto
import gr.scanmydata.taxcenter.data.KeyStoreKeys
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import gr.scanmydata.taxcenter.engine.EngineAssets
import gr.scanmydata.taxcenter.engine.FetchController
import gr.scanmydata.taxcenter.engine.ProcessRunner
import gr.scanmydata.taxcenter.mail.MailService

/**
 * Χειροκίνητο DI, χωρίς framework — όπως και στο Prosfora-APK.
 *
 * Η εφαρμογή έχει μία γραμμή εξαρτήσεων και δεν κερδίζει τίποτα από Hilt: η
 * ρητή κατασκευή κάνει προφανές ποιος κρατά τι, κάτι που μετράει όταν το «τι»
 * είναι κωδικοί πελατών.
 */
class AppContainer(context: Context) {

    private val app = context.applicationContext

    val db: TaxCenterDatabase by lazy { TaxCenterDatabase.get(app) }
    val crypto: Crypto by lazy { Crypto { KeyStoreKeys.dataKey() } }
    val settings: Settings by lazy { Settings(app) }
    val assets: EngineAssets by lazy { EngineAssets(app) }

    val repository: ClientRepository by lazy { ClientRepository(app, db, crypto) }
    val mail: MailService by lazy { MailService(app, db, repository, settings) }
    val processRunner: ProcessRunner by lazy { ProcessRunner(app, db, crypto, assets, settings) }

    /**
     * Η ουρά λήψης ζει εδώ, όχι σε ViewModel: μια παρτίδα κρατάει δεκάδες λεπτά
     * και δεν πρέπει να ακυρώνεται επειδή ο χρήστης άλλαξε οθόνη.
     */
    val fetch: FetchController by lazy { FetchController(app, processRunner, repository, assets) }
}
