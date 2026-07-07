package app.vela.core.data.google.parse

import app.vela.core.config.Calibration
import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.at
import app.vela.core.model.PlaceDetails
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

/**
 * Extracts the rich place fields the keyless/list search trims out — popular times
 * (`[84]`), the editorial one-liner (`[32][1][1]`) and the owner's "From the owner"
 * blurb (`[154][0][0]`) — from a **WebView** `/search?tbm=map` response (see
 * [app.vela.web.WebPopularTimesFetcher] for why a WebView and why a *specific* query).
 *
 * A specific query (name + address) resolves to a single focused result whose place
 * node at `[0][1][0][14]` keeps all of these. [SearchParser] already snaps to that
 * node and reads them into a [app.vela.core.model.Place], so we just lift them off;
 * popular times has a second path that reads `[84]` straight off the focused node in
 * case SearchParser snapped to a different entry.
 */
object PopularTimesParser {

    fun parse(
        body: String,
        featureId: String? = null,
        paths: Map<String, List<Int>> = Calibration.DEFAULT_PATHS,
    ): PlaceDetails? {
        val root = runCatching { GoogleResponse.parse(body) }.getOrNull() ?: return null
        // Thread the LIVE calibrated paths through so a remote paths fix reaches the WebView
        // details/popular-times path too (it used to pin DEFAULT_PATHS — audit 2026-07-06).
        val places = runCatching { SearchParser.parse("", root, paths = paths).places }.getOrDefault(emptyList())

        // The focused place: the feature-id match if we have one, else the first entry
        // that actually carries any of the rich fields, else just the first.
        val place = places.firstOrNull { featureId != null && it.featureId == featureId && hasDetail(it) }
            ?: places.firstOrNull { hasDetail(it) }
            ?: places.firstOrNull()

        // Trust the FULL backfill (rating / review count / hours / address …) only from a
        // feature-id MATCH — so a focused query that happened to return a neighbour can't graft
        // its rating or hours onto this place. No id / no match → the sensitive fields stay
        // blank (no wrong data); the id-agnostic rich fields still come from `place` as before.
        val matched = if (featureId != null) places.firstOrNull { it.featureId == featureId } else null

        // Popular times fallback: read [84] straight off the focused node [0][1][0][14]
        // even if SearchParser snapped elsewhere (e.g. an "at this place" list).
        val popularTimes = place?.popularTimes ?: run {
            val node = root.at(0, 1, 0, 14) ?: return@run null
            runCatching {
                SearchParser.parsePopularTimes(JsonArray(listOf(JsonNull, node)), paths)
            }.getOrNull()
        }

        val details = PlaceDetails(
            popularTimes = popularTimes,
            editorialSummary = place?.editorialSummary,
            ownerDescription = place?.ownerDescription,
            // Backfill the fields a summary node drops (review count, full hours, address, …) —
            // the focused result is a FULL place node, so SearchParser already read them.
            // Sourced from the feature-id-matched result only (see `matched` above).
            rating = matched?.rating,
            reviewCount = matched?.reviewCount,
            hours = matched?.hours.orEmpty(),
            address = matched?.address,
            phone = matched?.phone,
            website = matched?.website,
            statusText = matched?.statusText,
            openNow = matched?.openNow,
            priceText = matched?.priceText,
            priceLevel = matched?.priceLevel,
            about = matched?.about.orEmpty(),
            featuredReview = matched?.featuredReview,
        )
        return if (details.isEmpty) null else details
    }

    private fun hasDetail(p: app.vela.core.model.Place): Boolean =
        p.popularTimes != null || p.editorialSummary != null || p.ownerDescription != null ||
            p.reviewCount != null || p.hours.size >= 2 || p.about.isNotEmpty()
}
