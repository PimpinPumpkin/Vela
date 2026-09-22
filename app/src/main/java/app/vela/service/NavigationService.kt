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
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.vela.MainActivity
import app.vela.R
import android.graphics.Bitmap
import app.vela.core.model.ManeuverType
import app.vela.core.nav.NavSession
import app.vela.ui.theme.DynamicColor
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
    @Inject lateinit var voice: app.vela.core.voice.VoiceGuide

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    /** The live update's tracker puck, drawn once per process. */
    private var cachedPuck: android.graphics.Bitmap? = null
    // One-entry glyph cache (state ticks ~1 Hz; the type changes only at each turn).
    // Keyed on the accent too, so flipping Material You mid-drive recolors the arrow.
    private var cachedGlyph: Bitmap? = null
    private var cachedGlyphType: ManeuverType? = null
    private var cachedGlyphAccent: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            navSession.stop()
            teardown()
            return START_NOT_STICKY
        }
        // Pause/resume from the notification: the phone is usually in a cradle and the stop you
        // are pulling into is decided from behind the wheel, so the hold has to be reachable
        // without unlocking and finding the map (user 2026-09-18). It toggles the same session
        // state the map's Pause button does, and the card re-renders on the next state tick.
        if (intent?.action == ACTION_PAUSE) {
            navSession.setPaused(!navSession.state.value.paused)
            return START_STICKY
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

        // Backgrounded turn alerts (user 2026-07-24): the ongoing notification is deliberately
        // silent/minimized, but when guidance SPEAKS while the app isn't visible, the shade
        // should show the turn the voice just said. Post-mute hook, so a muted drive stays
        // quiet in the shade too; foreground drives already show the banner card.
        voice.onPromptAlert = {
            if (!app.vela.ui.AppVisibility.foreground.value) {
                runCatching { notificationManager().notify(TURN_ALERT_ID, buildTurnAlert()) }
            }
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
        val pause = PendingIntent.getService(
            this, 2, Intent(this, NavigationService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = navSession.state.value.paused
        // The big left-hand arrow: the CURRENT maneuver's glyph (white on Vela teal), so the
        // notification shows WHAT to do, not just how far. Cached per type - state ticks every
        // second and re-rasterizing an identical bitmap each tick is waste.
        val maneuverType = if (s.arrived) {
            ManeuverType.ARRIVE
        } else {
            s.route?.maneuvers?.getOrNull(s.nav.stepIndex)?.type
        }
        // Material You (issue #15): the arrow tile + accent row follow the system accent when
        // the user opted into dynamic color; Vela teal otherwise (matches the in-app theme).
        val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DynamicColor.isOn(this)) {
            getColor(android.R.color.system_accent1_600)
        } else {
            NavGlyphs.TEAL
        }
        val largeIcon = maneuverType?.let { t ->
            cachedGlyph?.takeIf { cachedGlyphType == t && cachedGlyphAccent == accent } ?: NavGlyphs.bitmap(
                t,
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width).coerceAtLeast(96),
                background = accent,
            ).also { cachedGlyph = it; cachedGlyphType = t; cachedGlyphAccent = accent }
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(largeIcon)
            .setColor(accent) // dynamic accent when Material You is on, VelaTeal otherwise
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false) // the post time is noise on a continuously-updating nav card
            .setContentIntent(open)
            .addAction(
                0,
                getString(if (paused) R.string.navservice_notif_action_resume else R.string.navservice_notif_action_pause),
                pause,
            )
            .addAction(0, getString(R.string.navservice_notif_action_end), stop)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // full turn info on the lock screen
        // The live update's TRACKER is the puck, the thing that moves along the bar (Google's own
        // card draws its blue arrow dot there and keeps the turn glyph as the large icon on the
        // right, user 2026-09-22); it used to be the maneuver glyph, which changed shape at every
        // turn and read as the next action rather than the car.
        val puck = cachedPuck ?: app.vela.ui.map.navPuckBitmap(scale = 1f).also { cachedPuck = it }
        promoteToLiveUpdate(b, s, puck)
        return b.build()
    }

    /**
     * Android 16's live update: the drive gets the status bar chip and a progress bar on the lock
     * screen, instead of living only in the shade (issue #595).
     *
     * The bar is the ROUTE, not a download: its scale is the route's length in meters and the
     * tracker sits where the car is. The segments are the traffic Vela already knows about, so the
     * jam ahead is visible without unlocking the phone, and each remaining stop is a point on the
     * bar. The chip's critical text is the distance to the next turn, because that is the one
     * number worth a glance while moving.
     *
     * Everything here is additive: below API 36, or with no route, the notification is exactly what
     * it was. A live update is a REQUEST - the system decides whether to promote it - so nothing
     * depends on it being granted.
     */
    private fun promoteToLiveUpdate(
        b: NotificationCompat.Builder,
        s: app.vela.core.nav.NavSession.State,
        tracker: android.graphics.Bitmap?,
    ) {
        if (Build.VERSION.SDK_INT < 36) return
        val route = s.route ?: return
        val total = route.distanceMeters
        if (total <= 0 || s.arrived) return
        runCatching {
            val traveled = (total - s.remainingDistance).coerceIn(0.0, total)
            val style = NotificationCompat.ProgressStyle()
                .setProgress(traveled.toInt())
                .setProgressTrackerIcon(tracker?.let { IconCompat.createWithBitmap(it) })
            // One segment per traffic span so the bar is colored the way the route line is, and a
            // plain one for whatever the spans do not cover. A route with no traffic data gets a
            // single segment, which is the same bar Google draws before it knows anything.
            val segments = ArrayList<NotificationCompat.ProgressStyle.Segment>()
            var at = 0.0
            for (span in route.trafficSpans.sortedBy { it.startMeters }) {
                if (span.lengthMeters <= 0) continue
                if (span.startMeters > at) {
                    segments += NotificationCompat.ProgressStyle.Segment((span.startMeters - at).toInt())
                        .setColor(TRAFFIC_CLEAR)
                }
                segments += NotificationCompat.ProgressStyle.Segment(span.lengthMeters.toInt())
                    .setColor(trafficColor(span.level))
                at = span.startMeters + span.lengthMeters
            }
            if (at < total) segments += NotificationCompat.ProgressStyle.Segment((total - at).toInt()).setColor(TRAFFIC_CLEAR)
            if (segments.isNotEmpty()) style.setProgressSegments(segments)
            // Stops still ahead: one point each, so a multi-stop trip reads as a trip rather than
            // one long bar. The final destination is the end of the bar and needs no point.
            var legStart = 0.0
            val points = ArrayList<NotificationCompat.ProgressStyle.Point>()
            for (leg in route.legs.dropLast(1)) {
                legStart += leg.distanceMeters
                if (legStart > traveled) points += NotificationCompat.ProgressStyle.Point(legStart.toInt())
            }
            if (points.isNotEmpty()) style.setProgressPoints(points)
            b.setStyle(style)
            b.setRequestPromotedOngoing(true)
            if (!s.arrived && s.nav.distanceToNextManeuver > 0.0) {
                b.setShortCriticalText(formatDistance(s.nav.distanceToNextManeuver))
            }
        }.onFailure { android.util.Log.d("VelaNav", "live update not applied: ${it.javaClass.simpleName}") }
    }

    private fun trafficColor(level: Int): Int = when {
        level >= 3 -> 0xFFD93025.toInt()  // stopped, the route line's red
        level == 2 -> 0xFFE8A33D.toInt()  // slow, its amber
        else -> TRAFFIC_CLEAR
    }


    /** The transient heads-up posted when guidance speaks in the background: same title/text as
     *  the ongoing card, but on the HIGH channel (visual pop, no sound - the voice IS the sound),
     *  its own id (a posted notification can never change channel, so the FGS card can't just be
     *  re-posted louder), self-expiring so the shade doesn't collect two nav rows. */
    private fun buildTurnAlert(): Notification {
        ensureChannel()
        val base = buildNotification()
        return NotificationCompat.Builder(this, TURN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(base.extras.getCharSequence(Notification.EXTRA_TITLE))
            .setContentText(base.extras.getCharSequence(Notification.EXTRA_TEXT))
            .setLargeIcon(cachedGlyph)
            .setColor(base.color)
            .setAutoCancel(true)
            .setTimeoutAfter(TURN_ALERT_TIMEOUT_MS)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        voice.onPromptAlert = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        // Also clear a DETACHED arrival notification (the arrived branch posts one dismissable —
        // tapping Done in-app must not leave it stranded in the shade).
        runCatching { notificationManager().cancel(NOTIF_ID) }
        runCatching { notificationManager().cancel(TURN_ALERT_ID) }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DEFAULT importance with no sound and no buzz, which is what Google Maps' own
            // navigation channel is. LOW would be quieter still, but the system files a LOW channel
            // as "silent" and a phone set to hide silent notifications on the lock screen then hides
            // the drive - which is the one place a live update is worth having. A foreground
            // notification with ONLY_ALERT_ONCE and no sound makes no noise at DEFAULT either.
            // (A channel's importance is fixed once it exists, so this is a new id; the old one is
            // removed so it does not sit in Settings doing nothing.)
            notificationManager().deleteNotificationChannel(OLD_CHANNEL_ID)
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.navservice_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            // HIGH so it pops as a heads-up, but explicitly soundless and buzz-free: the spoken
            // guidance is the audio channel, and a system ding on top of "turn right onto..."
            // would double-announce every turn.
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    TURN_CHANNEL_ID,
                    getString(R.string.navservice_channel_turns_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
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
        private const val ACTION_PAUSE = "app.vela.service.NAV_PAUSE"
        private const val CHANNEL_ID = "vela_nav_drive"
        /** The LOW-importance channel the drive used to live on, deleted on first run. */
        private const val OLD_CHANNEL_ID = "vela_nav"
        private const val TURN_CHANNEL_ID = "vela_nav_turns"
        private const val NOTIF_ID = 42

        /** The live-update bar's color for road that is not congested: the route line's own blue,
         *  so the notification and the map agree about what the drive looks like. */
        private const val TRAFFIC_CLEAR = 0xFF1F6FEB.toInt()
        private const val TURN_ALERT_ID = 43
        private const val TURN_ALERT_TIMEOUT_MS = 9_000L

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
