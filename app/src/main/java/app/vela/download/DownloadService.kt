package app.vela.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.vela.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-lifetime scope for long downloads (voice models, ASR engines, region graphs, place packs,
 * overlays, the update APK). Downloads used to ride `viewModelScope`, which is cancelled the moment
 * the task is swiped away, and nothing marked the process foreground-important while one ran, so
 * OEM background killers (the issue #212 Galaxy) reaped the process seconds after a Home press.
 * Work launched here survives the ViewModel; [DownloadService] holds the process alive around it.
 *
 * Main.immediate on purpose: it matches viewModelScope's dispatcher exactly, so the download
 * bodies (which do their own `withContext(IO)` hops) behave byte-identically to before.
 */
object DownloadWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/**
 * A refcounted `dataSync` foreground service that runs for exactly as long as any download is in
 * flight (issue #212). It does no work itself - the downloads run in [DownloadWork] - it exists so
 * the process is foreground-important while they do: backgrounding the app or swiping it from
 * recents no longer kills an in-flight voice/region/update download. The notification is silent
 * and low-importance ("Downloading <thing>"), and disappears the moment the last download ends.
 *
 * Foreground promotion can be refused on Android 14+ (a dataSync FGS cannot start from the
 * background). Every start here happens on a user tap, but if it is ever refused the service
 * degrades to nothing: the download keeps running in [DownloadWork] with exactly the old
 * lifetime, never a crash (the NavigationService rule).
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val labels = synchronized(active) { active.toList() }
        if (labels.isEmpty()) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notif = buildNotification(labels)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "foreground start failed; download continues without the keeper", t)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(labels: List<String>): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.download_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notif_title, labels.joinToString(", ")))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "VelaDownloadSvc"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 41

        // Labels of in-flight downloads. The service reads this on every (re)start command; poking
        // it with startService/startForegroundService is how begin/end refresh or retire the keeper.
        private val active = mutableListOf<String>()

        /** A download started: keep the process alive until the matching [end]. */
        fun begin(context: Context, label: String) {
            synchronized(active) { active.add(label) }
            poke(context)
        }

        /** A download finished (success or failure). The last one out stops the service. */
        fun end(context: Context, label: String) {
            synchronized(active) { active.remove(label) }
            poke(context)
        }

        private fun poke(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            // startForegroundService when work is pending (we promise the promotion); plain
            // startService for the retire poke. Both can throw when the app is deep in the
            // background - the download itself never depends on the poke landing.
            runCatching {
                val any = synchronized(active) { active.isNotEmpty() }
                if (any) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "service poke failed", it) }
        }
    }
}
