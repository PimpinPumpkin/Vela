package app.vela.car.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.service.NavigationService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Presents navigation as a media session so Vela reaches a car screen through Android Auto's MEDIA
 * surface, which lists apps the NAVIGATION/template surface does not (SPEC 13). The car host draws
 * the card; Vela fills it: the map as the artwork, the next turn as the title, the distance and ETA
 * as the subtitle, and a browse tree of saved and recent places that each start a drive.
 *
 * This is a presentation of the same [NavSession] the phone and the projected car screen drive, not
 * a second nav loop. It draws nothing and offers no content until "Navigation on the car's media
 * screen" is turned on ([app.vela.ui.CarMedia]); the manifest service is what exposes the surface.
 *
 * It does not draw a live map surface (the host grants that only to the navigation category): the
 * artwork is a still image refreshed as the drive advances, a second or so apart, from
 * [MapCardRenderer].
 */
@AndroidEntryPoint
class VelaMediaService : MediaBrowserServiceCompat() {

    @Inject lateinit var navSession: NavSession
    @Inject lateinit var locationProvider: LocationProvider
    @Inject lateinit var mapDataSource: app.vela.core.data.MapDataSource
    @Inject lateinit var savedPlaces: app.vela.core.data.SavedPlaceStore
    @Inject lateinit var recentPlaces: app.vela.core.data.RecentPlaceStore
    @Inject lateinit var routeEngine: app.vela.core.data.RouteEngine

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer by lazy { MapCardRenderer(applicationContext) }

    private var artJob: Job? = null
    private var lastArtMs = 0L
    private var lastTitle = ""
    private var lastPuck: LatLng? = null
    private var lastArt: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        app.vela.ui.CarMedia.load(applicationContext)
        session = MediaSessionCompat(this, "VelaMediaService").apply {
            setCallback(callback)
            setPlaybackState(idleState())
            isActive = true
        }
        sessionToken = session.sessionToken
        scope.launch { navSession.state.collectLatest { onNav(it) } }
        // Load the style and palette now, so the first card during a drive is a warm ~100 ms
        // render instead of a cold multi-second one.
        if (app.vela.ui.CarMedia.on.value) scope.launch { prewarm() }
    }

    private suspend fun prewarm() {
        val at = locationProvider.lastKnown() ?: LatLng(0.0, 0.0)
        runCatching {
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                renderer.render(at, 16.0, 0.0, isNight(), ART_PX, ART_PX)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        artJob = null
        renderer.release()
        session.release()
        super.onDestroy()
    }

    // ---- Browsing: saved + recent places, each a one-tap drive -------------------------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!app.vela.ui.CarMedia.on.value) return BrowserRoot(ROOT_EMPTY, null)
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT) { result.sendResult(mutableListOf()); return }
        val items = ArrayList<MediaBrowserCompat.MediaItem>()
        val seen = HashSet<String>()
        fun add(name: String, at: LatLng, icon: String?) {
            val key = "%.5f,%.5f".format(at.lat, at.lng)
            if (!seen.add(key)) return
            val extras = Bundle().apply { putDouble(EX_LAT, at.lat); putDouble(EX_LNG, at.lng); putString(EX_NAME, name) }
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId("$PLACE_PREFIX$key|$name")
                .setTitle(name)
                .setSubtitle(iconLabel(icon))
                .setExtras(extras)
                .build()
            items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }
        runCatching { savedPlaces.saved().filter { it.pinned }.forEach { add(it.name, it.location, it.icon) } }
        runCatching { savedPlaces.saved().filterNot { it.pinned }.take(6).forEach { add(it.name, it.location, it.icon) } }
        runCatching { recentPlaces.recent().take(8).forEach { add(it.place.name, it.place.location, it.place.icon) } }
        result.sendResult(items)
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId ?: return
            if (!id.startsWith(PLACE_PREFIX)) return
            val body = id.removePrefix(PLACE_PREFIX)
            val coords = body.substringBefore('|')
            val name = body.substringAfter('|', "")
            val lat = coords.substringBefore(',').toDoubleOrNull() ?: return
            val lng = coords.substringAfter(',').toDoubleOrNull() ?: return
            driveTo(LatLng(lat, lng), name)
        }

        override fun onStop() { endDrive() }
        override fun onPause() { if (navSession.state.value.navigating) navSession.setPaused(true) }
        override fun onPlay() {
            val s = navSession.state.value
            if (s.navigating && s.paused) navSession.setPaused(false)
        }
    }

    private fun driveTo(dest: LatLng, name: String) {
        scope.launch {
            val from = locationProvider.lastKnown() ?: return@launch
            val routes = runCatching {
                mapDataSource.directions(
                    from, dest, TravelMode.DRIVE,
                    avoidTolls = app.vela.core.data.RoutingPrefs.avoidTolls,
                    avoidHighways = app.vela.core.data.RoutingPrefs.avoidHighways,
                    avoidFerries = app.vela.core.data.RoutingPrefs.avoidFerries,
                )
            }.getOrDefault(emptyList())
            val picked = routes.firstOrNull() ?: return@launch
            val named = if (picked.provisional) {
                runCatching { mapDataSource.nameRoute(picked, from, dest, TravelMode.DRIVE) }.getOrDefault(picked)
            } else picked
            val engine = if (app.vela.core.voice.VelaPiper.isReady(applicationContext)) app.vela.core.voice.VelaPiper.ENGINE_ID else null
            navSession.start(named, dest, name, engine, emptyList(), TravelMode.DRIVE)
            runCatching { NavigationService.start(applicationContext) }
        }
    }

    private fun endDrive() {
        runCatching { navSession.stop() }
        runCatching { NavigationService.stop(applicationContext) }
    }

    // ---- Mirroring the live drive onto the card ----------------------------------------------

    private fun onNav(s: NavSession.State) {
        if (!app.vela.ui.CarMedia.on.value) { session.setPlaybackState(idleState()); return }
        if (!s.navigating) {
            session.setPlaybackState(idleState())
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, titleIdle())
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitleIdle())
                    .build(),
            )
            lastArt = null; lastTitle = ""; lastPuck = null
            return
        }
        val title = s.maneuverText.ifBlank { destinationLine(s) }
        val subtitle = buildString {
            append(app.vela.ui.formatDistance(s.remainingDistance))
            if (s.remainingDuration > 0) { append("  ·  "); append(app.vela.ui.formatDuration(s.remainingDuration)) }
        }
        session.setPlaybackState(drivingState(s.paused))
        pushMetadata(title, subtitle, lastArt)
        maybeRenderArt(s, title)
    }

    private fun maybeRenderArt(s: NavSession.State, title: String) {
        val puck = locationProvider.lastKnown() ?: s.route?.polyline?.firstOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val moved = lastPuck?.let { it.distanceTo(puck) > 12.0 } ?: true
        val changed = title != lastTitle
        if (!changed && !moved && now - lastArtMs < 1500L) return
        if (artJob?.isActive == true) return
        lastArtMs = now; lastTitle = title; lastPuck = puck
        val route = s.route?.polyline
        artJob = scope.launch {
            val bmp = runCatching {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    renderer.render(
                        center = puck,
                        zoom = 16.0,
                        bearing = 0.0,
                        dark = isNight(),
                        width = ART_PX,
                        height = ART_PX,
                        route = route,
                        puck = puck,
                    )
                }
            }.getOrNull() ?: return@launch
            lastArt = bmp
            if (navSession.state.value.navigating) pushMetadata(lastTitle, currentSubtitle(), bmp)
        }
    }

    private fun pushMetadata(title: String, subtitle: String, art: Bitmap?) {
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
        if (art != null) {
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            b.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            dumpCard(art)
        }
        session.setMetadata(b.build())
    }

    private fun currentSubtitle(): String {
        val s = navSession.state.value
        return buildString {
            append(app.vela.ui.formatDistance(s.remainingDistance))
            if (s.remainingDuration > 0) { append("  ·  "); append(app.vela.ui.formatDuration(s.remainingDuration)) }
        }
    }

    // adb-only aid to see the card's artwork without a car: `setprop debug.vela.cardshot true`,
    // then the latest map image is written to filesDir/vela-card.png.
    private val dumpCards by lazy {
        runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java, String::class.java).invoke(null, "debug.vela.cardshot", "") == "true"
        }.getOrDefault(false)
    }
    private fun dumpCard(art: Bitmap) {
        if (!dumpCards) return
        runCatching {
            java.io.File(filesDir, "vela-card.png").outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun idleState() = PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
        .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
        .build()

    private fun drivingState(paused: Boolean) = PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY)
        .setState(if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING, 0, 1f)
        .build()

    private fun destinationLine(s: NavSession.State): String =
        s.destinationLabel.ifBlank { getString(app.vela.R.string.car_media_navigating) }

    private fun titleIdle() = getString(app.vela.R.string.car_media_idle_title)
    private fun subtitleIdle() = getString(app.vela.R.string.car_media_idle_subtitle)
    private fun iconLabel(icon: String?): String = icon?.replaceFirstChar { it.uppercase() } ?: ""

    private fun isNight(): Boolean = when (app.vela.ui.theme.AppTheme.mode.value) {
        app.vela.ui.theme.ThemeMode.LIGHT -> false
        app.vela.ui.theme.ThemeMode.DARK, app.vela.ui.theme.ThemeMode.AMOLED -> true
        app.vela.ui.theme.ThemeMode.AUTO -> app.vela.ui.theme.AppTheme.night.value
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        const val ROOT = "vela_root"
        const val ROOT_EMPTY = "vela_root_empty"
        const val PLACE_PREFIX = "place:"
        const val EX_LAT = "lat"
        const val EX_LNG = "lng"
        const val EX_NAME = "name"
        const val ART_PX = 500
    }
}
