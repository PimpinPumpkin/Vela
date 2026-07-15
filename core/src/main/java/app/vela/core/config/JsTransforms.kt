package app.vela.core.config

import app.vela.core.model.Place
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the remotely-pushed (signature-verified) `transforms.js` to the scraper.
 * The script can hot-fix a Google response whose *shape* changed - not just a moved
 * field - without an app update. Two optional hooks on the search path:
 * - `parseSearch(rawResponse)` → flat places JSON: a full re-parse, used in place of
 * the compiled parser when present.
 * - `transformPlaces(placesJson)` → flat places JSON: post-processes the result.
 *
 * **Compiled Kotlin is always the fallback** - no script, a missing function, or any
 * error means the call returns null/unchanged and the caller keeps its own result.
 * Execution is sandboxed (see [JsSandbox]); the fetched code sees only the JSON string
 * it's handed, never Java/the device.
 */
@Singleton
class JsTransforms @Inject constructor(private val calibration: CalibrationStore) {

    private fun call(fn: String, arg: String): String? {
        val src = calibration.current().transformsJs?.takeIf { it.isNotBlank() } ?: return null
        return JsSandbox.run(src, fn, arg)
    }

    fun searchOverride(rawResponse: String): List<Place>? =
        call("parseSearch", rawResponse)
            ?.let { PlaceJson.decode(it) }
            ?.takeIf { it.isNotEmpty() }

    fun refineSearch(places: List<Place>): List<Place> {
        if (places.isEmpty()) return places
        return call("transformPlaces", PlaceJson.encode(places))
            ?.let { PlaceJson.decode(it) }
            ?: places
    }
}
