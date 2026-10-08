package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo

/**
 * A sound when a drive passes a place on a list that asked for one. Nothing here plays
 * anything: [Tracker] says which place a fix has just come up on, and the caller makes the sound.
 */
object PassAlerts {
    /** A place sounds when the car comes within this of it. About seven seconds at 45 mph. */
    const val RADIUS_M = 150.0
    /** ...and can sound again only after leaving this far, so GPS wander at the edge is one alert. */
    const val EXIT_M = 250.0
    /** Slower than this is not passing: parked beside it, walking out to the car. */
    const val MIN_SPEED_MPS = 2.0
    /** The same place stays quiet this long, for a drive that loops past it. */
    const val AGAIN_MS = 15 * 60_000L

    /** The sounds a list can pick ([app.vela.core.model.PlaceList.alert]). [NAME] speaks the place's name. */
    const val NAME = "name"
    val SOUNDS = listOf("ping", "bell", "double", "low", NAME)

    /** The notes of a tone, hertz to milliseconds, or null for [NAME] or an unknown key. */
    fun notes(sound: String): List<Pair<Double, Int>>? = when (sound) {
        "ping" -> listOf(1046.5 to 170)
        "bell" -> listOf(783.99 to 90, 987.77 to 90, 1174.66 to 110)
        "double" -> listOf(880.0 to 110, 880.0 to 110)
        "low" -> listOf(329.63 to 240)
        else -> null
    }

    /** [list] is the name of the list the place is on, for the card. */
    data class Target(val id: String, val name: String, val at: LatLng, val sound: String, val list: String = "")

    /** Follows one session's fixes. Not thread-safe: call it from the fix handler. */
    class Tracker {
        private val inside = HashSet<String>()
        private val lastAt = HashMap<String, Long>()
        private var primed = false

        /**
         * The place this fix has come up on, the nearest when several, or null. The first fix
         * alerts nothing: a place the session starts beside is where the car is, not somewhere it
         * is passing. A place reached at a crawl is held, and sounds if the car speeds up while
         * still inside its radius. A place within [RADIUS_M] of a [goingTo] point (the drive's
         * destination and stops) never sounds: the arrival line already says it.
         */
        fun onFix(fix: LatLng, speedMps: Double?, nowMs: Long, targets: List<Target>, goingTo: List<LatLng> = emptyList()): Target? {
            if (targets.isEmpty()) { inside.clear(); primed = true; return null }
            // A degree box first: a list can hold thousands of places.
            val dLat = EXIT_M / 111_320.0
            val dLng = dLat / kotlin.math.cos(Math.toRadians(fix.lat)).coerceAtLeast(0.1)
            val near = targets.filter { kotlin.math.abs(it.at.lat - fix.lat) <= dLat && kotlin.math.abs(it.at.lng - fix.lng) <= dLng }
                .map { it to fix.distanceTo(it.at) }
            val nearIds = near.filter { it.second <= EXIT_M }.mapTo(HashSet()) { it.first.id }
            inside.retainAll(nearIds)
            val within = near.filter { it.second <= RADIUS_M }
            if (!primed) {
                primed = true
                within.forEach { inside += it.first.id }
                return null
            }
            if ((speedMps ?: 0.0) < MIN_SPEED_MPS) return null
            var hit: Pair<Target, Double>? = null
            for (w in within) {
                if (!inside.add(w.first.id)) continue
                if (goingTo.any { it.distanceTo(w.first.at) <= RADIUS_M }) continue
                val last = lastAt[w.first.id]
                if (last != null && nowMs - last < AGAIN_MS) continue
                lastAt[w.first.id] = nowMs
                if (hit == null || w.second < hit.second) hit = w
            }
            return hit?.first
        }
    }
}
