package app.vela.core.data

import app.vela.core.model.LatLng
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** A fixed speed-enforcement camera at [loc] (OSM `highway=speed_camera`). */
data class SpeedCamera(val loc: LatLng)

@Serializable
private data class SpeedCamResp(val elements: List<SpeedCamNode> = emptyList())

@Serializable
private data class SpeedCamNode(val lat: Double? = null, val lon: Double? = null)

/**
 * Fixed radar/speed cameras from Overpass (issue #229) - the third sibling of
 * [OverpassTrafficSignals] and [OverpassAlprCameras]: keyless, per padded viewport, area-cached by
 * the caller, failover across mirrors, stream-parsed. Fixed installations only; mobile speed traps
 * need a live crowd service, which the keyless model has no source for. Coverage is OSM's - dense
 * in Europe where fixed cameras are common, sparse in US states that ban them.
 */
object OverpassSpeedCameras {
    private val json = Json { ignoreUnknownKeys = true }

    // Same bounded failover client rationale as OverpassAlprCameras: 15 s call / 8 s connect per
    // endpoint so a dead mirror is abandoned fast enough to reach a live one.
    @Volatile private var slowHttp: OkHttpClient? = null
    private fun slow(base: OkHttpClient): OkHttpClient =
        slowHttp ?: base.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
            .also { slowHttp = it }

    /** Speed-camera nodes in a bounding box. Null on FAILURE, a (possibly empty) list on a
     *  successful parse - the caller area-caches success only, so failures retry. */
    @OptIn(ExperimentalSerializationApi::class)
    fun fetchInBox(
        http: OkHttpClient,
        south: Double, west: Double, north: Double, east: Double,
        limit: Int = 4000,
    ): List<SpeedCamera>? {
        val box = "($south,$west,$north,$east)"
        // `out body`, never `out tags` - `out tags` omits a node's lat/lon (the empty-layer trap
        // the ALPR fetcher hit).
        val query = "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"]$box;out body $limit;"
        return OverpassEndpoints.run(slow(http), query) { body ->
            val parsed = json.decodeFromStream<SpeedCamResp>(body.byteStream())
            parsed.elements.mapNotNull { n ->
                val lat = n.lat ?: return@mapNotNull null
                val lng = n.lon ?: return@mapNotNull null
                SpeedCamera(LatLng(lat, lng))
            }
        }
    }
}
