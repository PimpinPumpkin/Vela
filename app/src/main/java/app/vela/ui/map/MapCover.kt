package app.vela.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import app.vela.core.util.VisibleArea

/**
 * What covers the map, for the map keys' select key and its crosshair (docs/dpad.md, Map keys).
 * Every panel that covers part of the map reports its own bounds with [coversMap]; a page that
 * covers all of it calls [CoversWholeMap]. The point both the crosshair and the select key use is
 * [center]: the middle of what the panels leave uncovered ([VisibleArea]), in window pixels.
 *
 * A panel that does not report is invisible here: the crosshair would draw over it and the select
 * key would tap the map under it. New map chrome that covers the map reports.
 */
internal object MapCover {
    private val covers = mutableStateMapOf<Any, FloatArray>()

    /** Owners in [covers] that count only while some other panel covers the map too. */
    private val onlyWithOthers = mutableStateMapOf<Any, Boolean>()

    /** The map's own bounds in window pixels, set by the crosshair's layer ([MapKeysCrosshair]). */
    val map = mutableStateOf<FloatArray?>(null)

    /** False while a drive's camera follows the car: no crosshair, and the select key does nothing.
     *  Set by [MapKeysCrosshair]. */
    @Volatile var selectLive = true

    /** The middle of the visible map in window pixels, or null when nothing of it is visible.
     *  Reads snapshot state: read it in a layout or draw phase. */
    fun center(): Pair<Float, Float>? = centerIn(map.value)

    /** [center] for map bounds the caller already read. */
    fun centerIn(map: FloatArray?): Pair<Float, Float>? {
        val m = map ?: return null
        val alone = covers.keys.all { onlyWithOthers[it] == true }
        return VisibleArea.center(m, if (alone) emptyList() else covers.values.toList())
    }

    internal fun set(owner: Any, bounds: FloatArray?, withOthersOnly: Boolean = false) {
        if (bounds == null) {
            covers.remove(owner)
            onlyWithOthers.remove(owner)
            return
        }
        if (withOthersOnly && onlyWithOthers[owner] != true) onlyWithOthers[owner] = true
        if (!bounds.contentEquals(covers[owner])) covers[owner] = bounds
    }

    internal fun bounds(c: LayoutCoordinates): FloatArray {
        val b = c.boundsInWindow()
        return floatArrayOf(b.left, b.top, b.right, b.bottom)
    }

    /** Larger than any window: whatever it overlaps is covered. */
    internal val WHOLE = floatArrayOf(-1e7f, -1e7f, 1e7f, 1e7f)
}

/** Reports this element's bounds as covering the map while it is laid out ([MapCover]). Put it on
 *  a panel's outermost element. It reports as the panel moves and stops when the panel leaves.
 *  [withOthersOnly] is for the search bar: it narrows the strip a sheet leaves, but on the bare map
 *  the crosshair stays in the middle of the screen, where the D-pad map target draws its own. */
internal fun Modifier.coversMap(withOthersOnly: Boolean = false): Modifier =
    this then if (withOthersOnly) CoversMapElement.WITH_OTHERS else CoversMapElement.ALWAYS

/** For a page that covers the whole map while it is composed (the search page). */
@Composable
internal fun CoversWholeMap() {
    DisposableEffect(Unit) {
        val owner = Any()
        MapCover.set(owner, MapCover.WHOLE)
        onDispose { MapCover.set(owner, null) }
    }
}

private class CoversMapElement private constructor(val withOthersOnly: Boolean) : ModifierNodeElement<CoversMapNode>() {
    override fun create() = CoversMapNode(withOthersOnly)
    override fun update(node: CoversMapNode) { node.withOthersOnly = withOthersOnly }
    override fun equals(other: Any?) = other is CoversMapElement && other.withOthersOnly == withOthersOnly
    override fun hashCode() = withOthersOnly.hashCode()

    companion object {
        val ALWAYS = CoversMapElement(false)
        val WITH_OTHERS = CoversMapElement(true)
    }
}

private class CoversMapNode(var withOthersOnly: Boolean) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) =
        MapCover.set(this, MapCover.bounds(coordinates), withOthersOnly)
    override fun onDetach() = MapCover.set(this, null)
}
