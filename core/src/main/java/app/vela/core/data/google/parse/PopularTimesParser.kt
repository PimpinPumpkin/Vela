package app.vela.core.data.google.parse

import app.vela.core.data.google.GoogleResponse
import app.vela.core.model.PopularTimes

/**
 * Extracts a place's popular-times histogram from a **WebView** `/search?tbm=map`
 * response.
 *
 * The catch that fooled us for a while: the *OkHttp* keyless search is silently
 * **bot-degraded** (TLS-fingerprint detection, same as photos/transit) and comes
 * back WITHOUT the `[84]` histogram — so we wrongly concluded popular times was
 * login-gated. A real browser engine isn't degraded: a warmed hidden WebView's
 * same-origin search returns the full ~240 KB response *with* `[84]` (proven
 * on-device 2026-06-19). [app.vela.web.WebPopularTimesFetcher] does that fetch and
 * hands the raw string here; we run it through the normal [SearchParser] (which
 * already reads `[84]` into `Place.popularTimes`) and pick the matching place.
 */
object PopularTimesParser {

    /** Parse a raw `/search?tbm=map` body and return the histogram for the place
     *  matching [featureId] (falling back to the first result that has one), or null. */
    fun parse(body: String, featureId: String? = null): PopularTimes? {
        val result = runCatching {
            SearchParser.parse("", GoogleResponse.parse(body))
        }.getOrNull() ?: return null
        val places = result.places
        return places.firstOrNull { featureId != null && it.featureId == featureId && it.popularTimes != null }?.popularTimes
            ?: places.firstOrNull { it.popularTimes != null }?.popularTimes
    }
}
