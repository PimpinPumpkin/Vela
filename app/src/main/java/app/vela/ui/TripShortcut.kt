package app.vela.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.vela.MainActivity
import app.vela.R
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.TravelMode

/**
 * A trip as a home-screen shortcut (issue #675): one tap opens the route picker for a start, any
 * stops and a destination, in the travel mode it was made with. A pinned shortcut, not a widget:
 * every launcher since Android 8 takes one, it needs no widget provider or setup screen, and
 * several can sit side by side (home to work, home to school).
 *
 * The trip rides in the shortcut's own intent, so nothing is stored in Vela and removing the
 * shortcut removes it. A point with no place is "your location", resolved when the shortcut is
 * tapped; that is what makes "from wherever I am to work" a shortcut worth having.
 */
object TripShortcut {
    const val ACTION = "app.vela.action.OPEN_TRIP"
    private const val NAMES = "trip_names"
    private const val LATS = "trip_lats"
    private const val LNGS = "trip_lngs"
    private const val MODE = "trip_mode"
    private const val HERE = Double.NaN

    /** Asks the launcher to pin the trip. False when the launcher cannot pin shortcuts. */
    fun pin(
        context: Context, points: List<Place?>, mode: TravelMode, hereLabel: String,
        label: String? = null, iconKey: String? = null, themed: Boolean = false,
    ): Boolean {
        if (points.size < 2 || points.all { it == null }) return false
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NAMES, points.map { it?.name.orEmpty() }.toTypedArray())
            .putExtra(LATS, points.map { it?.location?.lat ?: HERE }.toDoubleArray())
            .putExtra(LNGS, points.map { it?.location?.lng ?: HERE }.toDoubleArray())
            .putExtra(MODE, mode.name)
        val from = points.first()?.name ?: hereLabel
        val to = points.last()?.name ?: hereLabel
        // The launcher shows the short label under the icon; the destination is what tells two
        // shortcuts apart there. The long one is for the launcher's own dialogs.
        val name = label?.trim().orEmpty().ifBlank { to }
        val icon = runCatching {
            IconCompat.createWithAdaptiveBitmap(app.vela.ui.map.PoiIcons.shortcutIcon(context, iconKey ?: defaultIcon(mode), themed))
        }.getOrElse { IconCompat.createWithResource(context, R.mipmap.ic_launcher) }
        val info = ShortcutInfoCompat.Builder(context, "trip-" + (from + to + mode.name + points.size + name).hashCode())
            .setShortLabel(name.take(24))
            .setLongLabel("$from \u2192 $to".take(60))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
    }

    /** The glyph a shortcut starts with: its travel mode. */
    fun defaultIcon(mode: TravelMode): String = when (mode) {
        TravelMode.TRANSIT -> "transit"
        TravelMode.BICYCLE -> "bike"
        TravelMode.WALK -> "walk"
        else -> "drive"
    }

    /** The trip a shortcut's intent carries: its points (null = your location) and its mode. */
    fun read(intent: Intent): Pair<List<Place?>, TravelMode?>? {
        if (intent.action != ACTION) return null
        val names = intent.getStringArrayExtra(NAMES) ?: return null
        val lats = intent.getDoubleArrayExtra(LATS) ?: return null
        val lngs = intent.getDoubleArrayExtra(LNGS) ?: return null
        if (names.size < 2 || lats.size != names.size || lngs.size != names.size) return null
        val points = names.indices.map { i ->
            val la = lats[i]; val ln = lngs[i]
            if (la.isNaN() || ln.isNaN() || la !in -90.0..90.0 || ln !in -180.0..180.0) null
            else Place(id = "trip:$i:$la,$ln", name = names[i].ifBlank { "$la, $ln" }, location = LatLng(la, ln))
        }
        if (points.all { it == null }) return null
        val mode = intent.getStringExtra(MODE)?.let { runCatching { TravelMode.valueOf(it) }.getOrNull() }
        return points to mode
    }
}
