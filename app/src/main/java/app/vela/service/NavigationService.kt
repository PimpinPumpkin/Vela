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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.vela.MainActivity
import app.vela.R
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.nav.NavSession
import app.vela.ui.formatDistance
import app.vela.ui.formatDuration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps navigation alive while the app is backgrounded or the screen is off: a
 * foreground service that streams location into the shared [NavSession] and
 * mirrors its state into an ongoing notification. The nav loop, voice, and live
 * re-routing all live in [NavSession]; this class is just the Android lifecycle
 * + notification shell around it.
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject lateinit var locationProvider: LocationProvider
    @Inject lateinit var navSession: NavSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            navSession.stop()
            teardown()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification())

        if (!observing) {
            observing = true
            navSession.state
                .onEach { s ->
                    if (!s.navigating && !s.arrived) {
                        teardown()
                    } else {
                        notificationManager().notify(NOTIF_ID, buildNotification())
                    }
                }
                .launchIn(scope)
        }
        if (locationJob == null) {
            locationJob = scope.launch {
                locationProvider.updates().collect { loc ->
                    navSession.onLocation(LatLng(loc.latitude, loc.longitude))
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val s = navSession.state.value
        val title = if (s.arrived) "Arrived" else s.maneuverText.ifEmpty { "Navigating" }
        val text = if (s.arrived) {
            ""
        } else {
            "${formatDuration(s.remainingDuration)} · ${formatDistance(s.remainingDistance)}" +
                if (s.fasterRoute != null) " · faster route available" else ""
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "End", stop)
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
        locationJob?.cancel()
        locationJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notificationManager(): NotificationManager = getSystemService()!!

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "app.vela.service.NAV_STOP"
        private const val CHANNEL_ID = "vela_nav"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavigationService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
