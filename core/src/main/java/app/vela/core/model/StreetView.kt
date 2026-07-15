package app.vela.core.model

/**
 * A Street View panorama's metadata, resolved from a lat/lng by the keyless
 * `GeoPhotoService.SingleImageSearch` endpoint (the same one Google's own JS Maps API
 * uses - no API key, authorised by referer). Everything the in-app sphere viewer needs to
 * fetch tiles, orient the camera, walk to neighbours, and go back in time; the tiles
 * themselves come from `streetviewpixels-pa.googleapis.com/v1/tile` (also keyless).
 *
 * The equirectangular image is a fixed 2:1 pyramid: at zoom `z` it is `512·2^z` wide by
 * `256·2^z` tall, cut into [tileSize]²  tiles. So a chosen zoom fully determines the tile
 * grid - see `StreetViewTiles`.
 */
data class StreetViewPano(
    val panoId: String,
    val lat: Double,
    val lng: Double,
    // The pano's CAPTURE heading (degrees, true north). This is the texture's compass reference:
    // Google's equirect is stitched with the capture direction at the IMAGE CENTRE (u=0.5), so the
    // viewer needs it to map compass bearings onto the sphere. Never overwrite it with a desired
    // facing - that's [initialFacingDeg].
    val headingDeg: Double = 0.0,
    // The compass direction the viewer should FACE when this pano opens (Google's own yaw for the
    // place, or a computed look-at). Null = face down the street ([headingDeg]).
    val initialFacingDeg: Double? = null,
    val tileSize: Int = 512,
    // Number of pyramid levels; the max usable zoom is [maxZoom] - 1. The viewer picks a level
    // well below this (a full-res 16384×8192 equirect is ~400 MB decoded).
    val maxZoom: Int = 5,
    // Per-level equirect dimensions (width, height), zoom 0 upward, straight from the metadata.
    // The pyramid is NOT one fixed shape: modern car panos are 512·2^z wide, but pre-2016
    // captures are 416·2^z (13312×6656 max, sometimes only 4 levels) - assuming the modern shape
    // requested tiles past the old grid's edge, which failed and stitched as BLACK BANDS over
    // part of the sphere in time travel (live-verified 2026-07-16: a 2012 capture's level list).
    val levelDims: List<Pair<Int, Int>> = emptyList(),
    // Attribution: the place name Google labels the pano with, and the copyright line.
    val addressLabel: String? = null,
    val copyright: String? = null,
    // Capture date of THIS pano (year, month). Google shows it ("Image capture: May 2025").
    val captureYear: Int? = null,
    val captureMonth: Int? = null,
    // Walkable neighbours - the panoramas you can step to, one per rough direction (the viewer
    // draws a tappable arrow for each). Already de-cluttered from the raw ~100-pano local graph.
    val neighbors: List<StreetViewLink> = emptyList(),
    // Other captures AT THIS SPOT, newest first, INCLUDING this one - the "go back in time" list.
    // Empty or size 1 = no time machine here.
    val history: List<StreetViewTime> = emptyList(),
)

/** A neighbouring pano you can walk to: its id, position, and the bearing+distance from the
 *  current pano (so the viewer can place a directional arrow and label how far it is). */
data class StreetViewLink(
    val panoId: String,
    val lat: Double,
    val lng: Double,
    val bearingDeg: Double,
    val distanceM: Double,
)

/** A historical capture at (approximately) the current spot. */
data class StreetViewTime(
    val panoId: String,
    val year: Int,
    val month: Int,
)
