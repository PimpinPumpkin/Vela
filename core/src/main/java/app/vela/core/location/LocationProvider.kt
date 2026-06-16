package app.vela.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.getSystemService
import app.vela.core.model.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Location, the degoogled way.
 *
 * Uses AOSP [LocationManager] — NOT FusedLocationProviderClient, which lives in
 * Play Services and is absent on GrapheneOS/Calyx/etc. We request the GPS and
 * NETWORK providers simultaneously and surface whichever fixes first; on
 * GrapheneOS the NETWORK provider is backed by its own BeaconDB service, giving
 * a fast coarse fix to seed the map while GPS warms up.
 *
 * Cold-fix strategy (see also the PSDS tip the UI shows): we cache the last
 * known location and expose [cachedLatLng]/[lastKnown] so the map can recenter
 * instantly on launch instead of staring at null while GPS locks. Callers
 * should start collecting [updates] as early as possible (onCreate) to pre-warm.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vela_location", Context.MODE_PRIVATE)

    private val lm: LocationManager? = context.getSystemService()

    /** Cached last-known coordinate from a previous session, if any. */
    fun cachedLatLng(): LatLng? {
        val lat = prefs.getFloat(KEY_LAT, Float.NaN)
        val lng = prefs.getFloat(KEY_LNG, Float.NaN)
        return if (lat.isNaN() || lng.isNaN()) null else LatLng(lat.toDouble(), lng.toDouble())
    }

    /** Most recent OS last-known across providers, falling back to our cache. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): LatLng? {
        val mgr = lm ?: return cachedLatLng()
        val best = PROVIDERS
            .filter { mgr.allProviders.contains(it) }
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        return best?.let { LatLng(it.latitude, it.longitude) } ?: cachedLatLng()
    }

    /**
     * Cold stream of fixes from GPS + NETWORK. Caller must hold
     * ACCESS_FINE_LOCATION (checked at the UI layer before collecting).
     */
    @SuppressLint("MissingPermission")
    fun updates(minIntervalMs: Long = 1_000L, minDistanceM: Float = 2f): Flow<Location> =
        callbackFlow {
            val mgr = lm ?: run { close(); return@callbackFlow }
            val listener = LocationListener { loc ->
                cache(loc)
                trySend(loc)
            }
            val active = PROVIDERS.filter { mgr.allProviders.contains(it) }
            active.forEach { p ->
                runCatching {
                    mgr.requestLocationUpdates(p, minIntervalMs, minDistanceM, listener, Looper.getMainLooper())
                }
            }
            awaitClose { mgr.removeUpdates(listener) }
        }

    private fun cache(loc: Location) {
        prefs.edit()
            .putFloat(KEY_LAT, loc.latitude.toFloat())
            .putFloat(KEY_LNG, loc.longitude.toFloat())
            .apply()
    }

    private companion object {
        // GPS first (accurate), NETWORK second (fast coarse seed).
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val KEY_LAT = "last_lat"
        const val KEY_LNG = "last_lng"
    }
}
