package app.vela.core.data.naming

import app.vela.core.model.Maneuver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The steps for each stretch where Google's line leaves the open route ([HybridRoute]), from the
 * best source that answers before the route has to go out: the map matcher with the open
 * router's lane detail, then the map tiles, then the line's bends alone.
 *
 * The tiles are read beside the matcher, not after it: [hedgeAfterMs] into a match that has not
 * answered, or at once when it fails. A slow matcher then costs no names, because the tiles are
 * in hand when the deadline passes. A matcher that answers sooner costs no tile request.
 *
 * What a stretch has when the deadline passes is kept ([inHand]): a finished match stays when
 * its lanes did not come, and one slow stretch does not cost the others their names.
 */
class StretchNamer(
    /** The matcher's steps for the stretch, or null when it has none. */
    private val match: suspend (HybridRoute.Stretch) -> Named?,
    /** The matched steps with lane detail, or null to keep them as they are. */
    private val lanes: suspend (HybridRoute.Stretch, Named) -> Named?,
    /** The stretch named from the map tiles, bare where they have no name. With `fetch` false
     *  only tiles already read are used. Null when the stretch cannot be made into steps at all. */
    private val tiles: suspend (HybridRoute.Stretch, fetch: Boolean) -> Named?,
    private val hedgeAfterMs: Long,
) {
    enum class Source { MATCHED, TILES, BARE }

    /** One stretch's steps and what they came from. [names] counts the matcher's turn names kept,
     *  renamed and dropped. */
    class Named(
        val steps: List<Maneuver>, val source: Source,
        val lanes: Boolean = false, val names: IntArray = IntArray(3), val noEdges: Boolean = false,
    )

    private val best = ConcurrentHashMap<HybridRoute.Stretch, Named>()

    /** The best steps for [st]. Run under the route's deadline; what is finished when it passes
     *  is still there for [inHand]. */
    suspend fun name(st: HybridRoute.Stretch): Named? = coroutineScope {
        val failed = CompletableDeferred<Unit>()
        val hedge = async {
            withTimeoutOrNull(hedgeAfterMs) { failed.await() }
            tiles(st, true)
        }
        val m = match(st)
        if (m != null) {
            hedge.cancel()
            best[st] = m
            // The matcher's call blocks, so a deadline that passed during it is only seen here.
            currentCoroutineContext().ensureActive()
            val detailed = lanes(st, m)
            if (detailed != null) best[st] = detailed
            detailed ?: m
        } else {
            failed.complete(Unit)
            hedge.await()?.also { best[st] = it }
        }
    }

    /** After the deadline: what [name] finished for [st], else the stretch named from the tiles
     *  already read. No request is made. */
    suspend fun inHand(st: HybridRoute.Stretch): Named? = best[st] ?: tiles(st, false)?.also { best[st] = it }

    /** What each stretch ended up with. */
    fun result(st: HybridRoute.Stretch): Named? = best[st]
}
