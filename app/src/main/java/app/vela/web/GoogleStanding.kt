package app.vela.web

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether Google is giving this install's session its LIMITED view right now (2026-09-25).
 *
 * In the limited view the place sheet is quietly thinner: no popular times on places that have
 * them, five reviews and a "More reviews" that loads nothing, 10 photos per request instead of the
 * 50 asked for. None of that looks like Google's doing from the outside, so without a signal Vela
 * just looks broken (issue #602 was exactly this). Only strong evidence marks it:
 *
 * - the first photo page comes back with at most [LIMITED_PHOTO_PAGE_MAX] photos while a next page
 *   exists (a full session answers the 50 asked for; a limited one 10, measured on two phones on
 *   one connection, same query, same minute);
 * - "More reviews" on the full reviews page loads nothing.
 *
 * A photo page of at least [FULL_PHOTO_PAGE_MIN] clears it. The mark belongs to the current
 * session (stored against [SessionRotation]'s start stamp), so a new session starts unmarked.
 * A missing popular-times chart alone is never evidence: plenty of places have none.
 */
object GoogleStanding {
    val limited = mutableStateOf(false)

    const val LIMITED_PHOTO_PAGE_MAX = 20
    const val FULL_PHOTO_PAGE_MIN = 40

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        limited.value = p.getLong(KEY_LIMITED_SESSION, -1L).let { it >= 0 && it == SessionRotation.sessionStarted(context) }
    }

    /** Called with the first photo page's size and whether a next page exists. */
    fun onPhotoPage(context: Context, count: Int, hasMore: Boolean) {
        when {
            count >= FULL_PHOTO_PAGE_MIN -> markFull(context)
            hasMore && count in 1..LIMITED_PHOTO_PAGE_MAX -> markLimited(context, "photos: $count per page with more waiting")
        }
    }

    fun markLimited(context: Context, why: String) {
        if (limited.value) return
        limited.value = true
        prefs(context).edit().putLong(KEY_LIMITED_SESSION, SessionRotation.sessionStarted(context)).apply()
        android.util.Log.i("VelaSession", "limited view: $why")
    }

    fun markFull(context: Context) {
        if (!limited.value) return
        limited.value = false
        prefs(context).edit().remove(KEY_LIMITED_SESSION).apply()
        android.util.Log.i("VelaSession", "full view again")
    }

    /** A new session: whatever the old one was marked, this one has not been judged yet. */
    fun reset(context: Context) {
        limited.value = false
        prefs(context).edit().remove(KEY_LIMITED_SESSION).apply()
    }

    private const val KEY_LIMITED_SESSION = "google_limited_session"
}
