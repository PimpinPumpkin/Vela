package app.vela.core.data

/**
 * "Use Vela without Google" (Settings > Privacy). One switch at the network edge: with it on, no
 * request leaves for a Google host from this module - search goes to the OpenStreetMap geocoder
 * (Photon) and the downloaded place packs, the ambient business fan-out is off, directions are the
 * open router's alone (no traffic, no Google alternates, no Google fallback), and Street View
 * answers "no coverage". The app gates its own Google surfaces (the hidden WebViews, the traffic
 * raster, the satellite fallback, the tap lookup) on the same setting.
 *
 * Same seam as [LowRamMode]: the preference lives in `:app` (`app.vela.ui.GoogleFree`) and is
 * pushed down here, because `:core` never reads an app holder.
 */
object NoGoogle {
    @Volatile var enabled: Boolean = false
}
