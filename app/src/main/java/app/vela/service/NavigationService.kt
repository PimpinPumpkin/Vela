package app.vela.service

import android.app.Notification
import android.app.NotificationChannel
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
import androidx.core.content.getSystemService
import app.vela.MainActivity
import app.vela.R
import app.vela.core.nav.NavSession
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Keeps navigation alive while the app is backgrounded or the screen is off: a
 * foreground service that mirrors the shared [NavSession]'s state into an ongoing
 * notification and holds the process up so the nav loop keeps running with the
 * screen off.
 *
 * **Location is fed by the ViewModel, not here** — deliberately. Promoting to a
 * `location`-typed foreground service can *throw* on Android 14+ (the runtime
 * location grant has to be in the exact state the type demands; GrapheneOS is
 * especially strict), and an uncaught throw in `onStartCommand` crashes the whole
 * app. So this start is wrapped and, if it fails, the app simply falls back to
 * in-app (foreground) navigation — the [app.vela.ui.map.MapViewModel] drives
 * `NavSession.onLocation` from its own location collector independently of this
 * service. The service is best-effort polish (background continuation +
 * notification), never a hard dependency of navigation.
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject lateinit var navSession: NavSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            navSession.stop()
            teardown()
            return START_NOT_STICKY
        }

        // Foreground promotion can throw on Android 14+ (e.g. ForegroundServiceStart-
        // NotAllowed, or a SecurityException when the location grant isn't in the state
        // the FGS-location type requires). Never let that crash the app — nav keeps
        // working in the foreground because the ViewModel feeds NavSession itself.
        try {
            startForegroundCompat(buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "foreground start failed; continuing without the nav service", t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!observing) {
            observing = true
            navSession.state
                .onEach { s ->
                    if (!s.navigating && !s.arrived) {
                        teardown()
                    } else {
                        runCatching { notificationManager().notify(NOTIF_ID, buildNotification()) }
                    }
                }
                .launchIn(scope)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val s = navSession.state.value
        // Google-style: lead with the distance to the next turn ("In 500 ft · Turn right
        // onto Main St") when we have it, so the collapsed notification reads at a glance.
        val title = when {
            s.arrived -> getString(R.string.navservice_notif_title_arrived)
            s.maneuverText.isEmpty() -> getString(R.string.navservice_notif_title_navigating)
            s.nav.distanceToNextManeuver > 0.0 ->
                getString(
                    R.string.navservice_notif_title_in_distance,
                    formatDistance(s.nav.distanceToNextManeuver),
                    s.maneuverText,
                )
            else -> s.maneuverText
        }
        val text = if (s.arrived) {
            ""
        } else {
            getString(
                R.string.navservice_notif_text_remaining,
                formatDuration(s.remainingDuration),
                formatDistance(s.remainingDistance),
            ) +
                when {
                    s.fasterRoute != null && s.fasterSavingSeconds > 0 ->
                        getString(
                            R.string.navservice_notif_text_faster_saving,
                            formatDuration(s.fasterSavingSeconds),
                        )
                    s.fasterRoute != null -> getString(R.string.navservice_notif_text_faster_available)
                    else -> ""
                }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.navservice_notif_action_end), stop)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun teardown() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.navservice_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun notificationManager(): NotificationManager = getSystemService()!!

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VelaNavService"
        private const val ACTION_STOP = "app.vela.service.NAV_STOP"
        private const val CHANNEL_ID = "vela_nav"
        private const val NOTIF_ID = 42

        /** Best-effort: start the background nav service. A failure here (background
         *  start not allowed, OEM restriction) is swallowed — foreground nav, driven
         *  by the ViewModel, does not depend on it. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            }.onFailure { Log.w(TAG, "could not start nav service", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, NavigationService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
