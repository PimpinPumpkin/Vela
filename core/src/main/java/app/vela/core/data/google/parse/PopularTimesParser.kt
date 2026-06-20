package app.vela.core.data.google.parse

import app.vela.core.config.Calibration
import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.at
import app.vela.core.model.PopularTimes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

/**
 * Extracts a place's popular-times histogram from a **WebView** `/search?tbm=map`
 * response (see [app.vela.web.WebPopularTimesFetcher] for why a WebView and why a
 * *specific* query).
 *
 * A specific query (name + address) resolves to a single focused result whose place
 * node at `[0][1][0][14]` keeps the `[84]` histogram. [SearchParser] already snaps
 * to that node (via `singleResultEntry`) and reads `[84]` into [Place.popularTimes],
 * so path 1 normally finds it; path 2 reads `[84]` straight off the focused node as
 * a fallback in case SearchParser snapped to a different entry.
 */
object PopularTimesParser {

    fun parse(body: String, featureId: String? = null): PopularTimes? {
        val root = runCatching { GoogleResponse.parse(body) }.getOrNull() ?: return null
        val places = runCatching { SearchParser.parse("", root).places }.getOrDefault(emptyList())

        // 1. the focused single result (or, rarely, a [64] entry that kept the
        //    histogram) — SearchParser already read [84] into Place.popularTimes.
        (places.firstOrNull { featureId != null && it.featureId == featureId && it.popularTimes != null }?.popularTimes
            ?: places.firstOrNull { it.popularTimes != null }?.popularTimes)?.let { return it }

        // 2. fallback: read [84] straight off the focused node [0][1][0][14], even if
        //    SearchParser snapped elsewhere (e.g. an "at this place" list). Wrap as a
        //    [null, node] entry so parsePopularTimes (which reads [1][84]) parses it.
        val node = root.at(0, 1, 0, 14) ?: return null
        return runCatching {
            SearchParser.parsePopularTimes(JsonArray(listOf(JsonNull, node)), Calibration.DEFAULT_PATHS)
        }.getOrNull()
    }
}
