package app.vela.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.vela.MainActivity
import app.vela.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * What the transit guidance notification shows (issue #880), written by `MapViewModel` as the
 * legs advance and the rider moves; [TransitGuideService] mirrors it. [alertKey] is set once per
 * leg when the next stop is the rider's, and the service pops [alert] once for it.
 */
object TransitGuide {
    data class Notice(
        val title: String,
        val text: String,
        val alert: String? = null,
        val alertKey: String? = null,
        val arrived: Boolean = false,
    )

    val notice = MutableStateFlow<Notice?>(null)

    /** The notification's End button: `MapViewModel` ends the guidance. */
    @Volatile var onEnd: (() -> Unit)? = null

    /** Guidance ended in the app: the service stops, and an arrival card left in the shade goes. */
    fun end(context: Context) {
        notice.value = null
        runCatching { context.getSystemService(android.app.NotificationManager::class.java)?.cancel(TransitGuideService.ARRIVED_ID) }
    }

    /** Best effort, like the drive's service: transit guidance in the app does not depend on it. */
    fun start(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TransitGuideService::class.java)) }
            .onFailure { Log.w(TAG, "could not start the transit guide service", it) }
    }

    internal const val TAG = "VelaTransitGuide"
}

/**
 * Keeps transit guidance going with the phone in a pocket (issue #880), as Google Maps does: an
 * ongoing notification with the leg in hand (the line and where to get off, with the stops left,
 * or the walk), a heads-up when the next stop is the rider's, and, being a location foreground
 * service, the fixes that advance the legs while the screen is off. Location itself is fed by the
 * ViewModel, as for a drive; a failed promotion leaves guidance working in the app.
 */
class TransitGuideService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var alerted: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            TransitGuide.onEnd?.invoke()
            teardown()
            return START_NOT_STICKY
        }
        NavigationService.ensureNavChannels(this)
        val first = TransitGuide.notice.value ?: run { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, build(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, build(first))
            }
        } catch (t: Throwable) {
            Log.w(TransitGuide.TAG, "foreground start failed; guidance continues in the app", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            TransitGuide.notice.onEach { n ->
                when {
                    n == null -> teardown()
                    n.arrived -> {
                        // Arrival ends the service. The arrival card is its own notification:
                        // replacing the ongoing one right after detaching it lost the race on a
                        // 4a and left the last leg's card in the shade, ongoing.
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        runCatching { nm().cancel(ALERT_ID) }
                        runCatching { nm().notify(ARRIVED_ID, build(n)) }
                        stopSelf()
                    }
                    else -> {
                        runCatching { nm().notify(NOTIF_ID, build(n)) }
                        if (n.alertKey != null && n.alertKey != alerted && n.alert != null) {
                            alerted = n.alertKey
                            runCatching { nm().notify(ALERT_ID, buildAlert(n.alert)) }
                        }
                    }
                }
            }.launchIn(scope)
        }
        return START_STICKY
    }

    private fun build(n: TransitGuide.Notice): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, NavigationService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (n.arrived) {
            b.setOngoing(false).setAutoCancel(true)
        } else {
            val end = PendingIntent.getService(
                this, 11, Intent(this, TransitGuideService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.setOngoing(true).addAction(0, getString(R.string.navservice_notif_action_end), end)
        }
        return b.build()
    }

    /** The heads-up for "get off at the next stop": the turn channel, silent, gone after a while. */
    private fun buildAlert(text: String): Notification =
        NotificationCompat.Builder(this, NavigationService.TURN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 12, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun teardown() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { nm().cancel(NOTIF_ID) }
        runCatching { nm().cancel(ALERT_ID) }
        stopSelf()
    }

    private fun nm(): NotificationManager = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_END = "app.vela.service.TRANSIT_END"
        // Beside the drive's 42 and 43.
        private const val NOTIF_ID = 44
        private const val ALERT_ID = 45
        internal const val ARRIVED_ID = 46
        private const val ALERT_TIMEOUT_MS = 30_000L
    }
}
