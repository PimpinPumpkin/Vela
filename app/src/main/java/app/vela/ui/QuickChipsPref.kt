package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Which quick-category chips show and in what order (Settings > Search > Quick search buttons,
 * issue #779). Both are keyed by [QuickCategories.Chip.key], the chip's fixed English query,
 * never by position, so an order saved by one build still reads in the next. An empty [order]
 * is the default order. Same holder shape as [HideAdult], persisted in `vela_settings`.
 */
object QuickChipsPref {
    /** Chip keys in the user's order. Empty until the user moves one. */
    val order = mutableStateOf<List<String>>(emptyList())

    /** Keys of the chips taken off the rows. */
    val hidden = mutableStateOf<Set<String>>(emptySet())

    fun init(context: Context) {
        val p = prefs(context)
        order.value = p.getString(KEY_ORDER, null)?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList()
        hidden.value = p.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()
    }

    fun setOrder(context: Context, keys: List<String>) {
        order.value = keys
        prefs(context).edit().putString(KEY_ORDER, keys.joinToString(SEP)).apply()
    }

    fun setShown(context: Context, key: String, shown: Boolean) {
        val next = if (shown) hidden.value - key else hidden.value + key
        hidden.value = next
        prefs(context).edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    fun reset(context: Context) {
        order.value = emptyList()
        hidden.value = emptySet()
        prefs(context).edit().remove(KEY_ORDER).remove(KEY_HIDDEN).apply()
    }

    /** True once the order or the hidden set differs from the default. */
    fun customized(): Boolean = order.value.isNotEmpty() || hidden.value.isNotEmpty()

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_ORDER = "quick_chips_order"
    private const val KEY_HIDDEN = "quick_chips_hidden"
    // The keys are short English phrases with spaces; none holds a bar.
    private const val SEP = "|"
}

/** The ordering rules behind [QuickChipsPref], on keys alone so a test needs no Android. */
object QuickChipOrder {
    /**
     * [defaults] in the user's [order]: the keys [order] names first, in that order, then every
     * other default in its default place. A key [order] names that is not in [defaults] (a chip
     * removed, or Bars under "Hide adult categories") is skipped, and a chip that comes back
     * takes the end of the list.
     */
    fun arrange(defaults: List<String>, order: List<String>): List<String> {
        val known = order.filter { it in defaults }.distinct()
        return known + defaults.filter { it !in known }
    }

    /** [keys] with [key] moved [delta] places, held at the ends. Unchanged when [key] is absent. */
    fun moved(keys: List<String>, key: String, delta: Int): List<String> {
        val from = keys.indexOf(key)
        if (from < 0) return keys
        val to = (from + delta).coerceIn(0, keys.size - 1)
        if (to == from) return keys
        return keys.toMutableList().apply { removeAt(from); add(to, key) }
    }
}
