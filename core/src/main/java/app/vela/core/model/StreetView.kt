package app.vela.core.model

/**
 * A Street View panorama's metadata, resolved from a lat/lng by the keyless
 * `GeoPhotoService.SingleImageSearch` endpoint (the same one Google's own JS Maps API
 * uses - no API key, authorised by referer). Everything the in-app sphere viewer needs to
 * fetch tiles and orient the camera; the tiles themselves come from
 * `streetviewpixels-pa.googleapis.com/v1/tile` (also keyless).
 *
 * The equirectangular image is a fixed 2:1 pyramid: at zoom `z` it is `512·2^z` wide by
 * `256·2^z` tall, cut into [tileSize]²  tiles. So a chosen zoom fully determines the tile
 * grid - see `StreetViewTiles`.
 */
data class StreetViewPano(
    val panoId: String,
    val lat: Double,
    val lng: Double,
    // The pano's own yaw (degrees, true-north referenced). The sphere's texture seam (u=0) is
    // this direction, so the viewer offsets the initial camera by it to face down the street.
    val headingDeg: Double = 0.0,
    val tileSize: Int = 512,
    // Number of pyramid levels; the max usable zoom is [maxZoom] - 1. The viewer picks a level
    // well below this (a full-res 16384×8192 equirect is ~400 MB decoded).
    val maxZoom: Int = 5,
    // Attribution: the place name Google labels the pano with, and the copyright line.
    val addressLabel: String? = null,
    val copyright: String? = null,
)
