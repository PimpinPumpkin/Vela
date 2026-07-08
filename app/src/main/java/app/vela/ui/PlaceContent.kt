package app.vela.ui

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.vela.BuildConfig

/**
 * Whether the build LOCKS the content settings. In the `restricted` product flavor
 * ([BuildConfig.RESTRICTED]) every [LockableToggle] is forced to its safe value regardless of any
 * stored pref, its setter is a no-op, and the whole content-toggle section is hidden in Settings — so
 * a managed/locked device can't turn content back on. In the `standard` flavor this is false and the
 * toggles behave normally.
 */
object ContentPolicy {
    val locked: Boolean get() = BuildConfig.RESTRICTED
}

/**
 * A process-wide reactive content-visibility setting that is **automatically enforced in the restricted
 * build**: in a locked build it reads [lockedValue] (ignoring any stored pref) and its setter is a
 * no-op. This is the seam that makes "any future content restriction is baked into the restricted
 * build" true by construction — a new content toggle just extends this (and its Settings row goes in
 * the content-toggle section, which is hidden wholesale when locked). Persisted in vela_settings.
 */
abstract class LockableToggle(
    private val key: String,
    private val default: Boolean,
    /** The value forced in the restricted (locked) build — the safe/most-restrictive setting. */
    private val lockedValue: Boolean,
) {
    val on: MutableState<Boolean> = mutableStateOf(if (ContentPolicy.locked) lockedValue else default)

    fun init(context: Context) {
        on.value = if (ContentPolicy.locked) lockedValue else prefs(context).getBoolean(key, default)
        onChanged(on.value)
    }

    fun set(context: Context, value: Boolean) {
        if (ContentPolicy.locked) return // locked in the restricted build — setter is a no-op
        on.value = value
        prefs(context).edit().putBoolean(key, value).apply()
        onChanged(value)
    }

    /** Side effect to run whenever the value changes (e.g. push it into a :core flag). */
    protected open fun onChanged(value: Boolean) {}

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
}

/**
 * Whether place pages show reviews at all. OFF means the sheet renders no review section and the app
 * never fetches reviews for a selected place (no hidden WebView scrape, no review traffic). Locked OFF
 * in the restricted build.
 */
object ShowReviews : LockableToggle(key = "show_reviews", default = true, lockedValue = false)

/**
 * Whether place pages load photos. OFF means no hero strip, no gallery, and no photo fetch at all (the
 * WebView gallery scrape is the heaviest per-place request, so this doubles as a data-saver switch).
 * Locked OFF in the restricted build.
 */
object LoadPhotos : LockableToggle(key = "load_photos", default = true, lockedValue = false)

/**
 * Whether to hide adult / nightlife categories (bars, clubs, casinos, liquor stores, adult, smoking,
 * gambling, …) from search results and the ambient map. OFF by default (everything shown); ON drops
 * those places at the data-source seam via [app.vela.core.data.CategoryFilter] (category-only, never
 * the name). Locked ON in the restricted build.
 */
object HideAdult : LockableToggle(key = "hide_adult", default = false, lockedValue = true) {
    override fun onChanged(value: Boolean) {
        app.vela.core.data.CategoryFilter.enabled = value // gate the :core data-source seam
    }
}
