package app.vela.core.data

import java.io.File

/** Where downloaded offline data lives (internal storage or the app's folder on an SD card, issue
 *  #613). Set by the app at start and after a move; null = the caller's internal files dir. */
object OfflineRoot {
    @Volatile var dir: File? = null
}
