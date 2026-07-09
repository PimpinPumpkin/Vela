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
import android.graphics.Bitmap
import app.vela.core.model.ManeuverType
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
import java.text.DateFormat
import java.util.Date
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

    // One-entry glyph cache (state ticks ~1 Hz; the type changes only at each turn).
    private var cachedGlyph: Bitmap? = null
    private var cachedGlyphType: ManeuverType? = null

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
                    when {
                        !s.navigating && !s.arrived -> teardown()
                        s.arrived -> {
                            // Arrival is TERMINAL for the service: the old `!navigating &&
                            // !arrived` condition kept the location-typed FGS, the ongoing
                            // notification and 1 Hz GPS alive INDEFINITELY if the driver
                            // pocketed the phone without tapping Done. DETACH FIRST, then post
                            // the dismissable arrival notification — posting before the detach
                            // lets the system re-stamp FLAG_FOREGROUND_SERVICE onto the record
                            // and it stays non-swipeable on older APIs.
                            runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
                            runCatching {
                                notificationManager().notify(
                                    NOTIF_ID,
                                    buildNotification().also {
                                        it.flags = it.flags and
                                            (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE).inv()
                                    },
                                )
                            }
                            stopSelf()
                        }
                        else -> runCatching { notificationManager().notify(NOTIF_ID, buildNotification()) }
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
            // "12 min · 3.4 mi · Arrive 12:45" - the ETA clock is what a passenger glancing at
            // the shade actually wants, same line Google puts there.
            val eta = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(Date(System.currentTimeMillis() + (s.remainingDuration * 1000).toLong()))
            getString(
                R.string.navservice_notif_text_remaining,
                formatDuration(s.remainingDuration),
                formatDistance(s.remainingDistance),
            ) + " · " + getString(R.string.navservice_notif_eta, eta) +
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
        // The big left-hand arrow: the CURRENT maneuver's glyph (white on Vela teal), so the
        // notification shows WHAT to do, not just how far. Cached per type - state ticks every
        // second and re-rasterizing an identical bitmap each tick is waste.
        val maneuverType = if (s.arrived) {
            ManeuverType.ARRIVE
        } else {
            s.route?.maneuvers?.getOrNull(s.nav.stepIndex)?.type
        }
        val largeIcon = maneuverType?.let { t ->
            cachedGlyph?.takeIf { cachedGlyphType == t } ?: NavGlyphs.bitmap(
                t,
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width).coerceAtLeast(96),
            ).also { cachedGlyph = it; cachedGlyphType = t }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(largeIcon)
            .setColor(NavGlyphs.TEAL) // VelaTeal accent on the action/app name row
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false) // the post time is noise on a continuously-updating nav card
            .setContentIntent(open)
            .addAction(0, getString(R.string.navservice_notif_action_end), stop)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // full turn info on the lock screen
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
        // Also clear a DETACHED arrival notification (the arrived branch posts one dismissable —
        // tapping Done in-app must not leave it stranded in the shade).
        runCatching { notificationManager().cancel(NOTIF_ID) }
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
