package app.vela.ui.nav

import androidx.compose.ui.geometry.Offset
import app.vela.core.model.Lane
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LaneGlyphTest {
    private val combos = listOf(
        listOf("straight"),
        listOf("right"),
        listOf("slight right"),
        listOf("sharp right"),
        listOf("uturn"),
        listOf("straight", "slight right"),
        listOf("straight", "right"),
        listOf("straight", "sharp right"),
        listOf("slight left", "straight"),
        listOf("left", "straight"),
        listOf("left", "straight", "right"),
        listOf("slight left", "straight", "slight right"),
        listOf("left", "straight", "slight right"),
        listOf("slight right", "right"),
        listOf("left", "slight left"),
        listOf("sharp left", "left"),
        listOf("right", "sharp right"),
        listOf("slight left", "right"),
        listOf("left", "right"),
        listOf("left", "uturn"),
        listOf("straight", "uturn"),
        listOf("left", "straight", "sharp right"),
    )

    // Unit sizes in pixels: the turn card's strip and the step list's, on a 2.625x screen.
    private val units = listOf(1f, 21f / 97f * 2.625f)
    private val cx = 100f
    private val foot = 200f

    @Test
    fun `every arrow of a lane stands on the lane's center`() {
        for (u in units) for (inds in combos) for (g in laneGlyphs(inds, cx, foot, u)) {
            assertEquals("$inds ${g.indication}", Offset(cx, foot), g.body.first())
        }
    }

    @Test
    fun `an arrow is drawn the same in a lane that allows several moves`() {
        for (u in units) for (inds in combos) for (g in laneGlyphs(inds, cx, foot, u)) {
            val alone = laneGlyphs(listOf(g.indication), cx, foot, u).single()
            assertEquals("$inds ${g.indication}", alone.body, g.body)
            assertEquals("$inds ${g.indication}", alone.head, g.head)
        }
    }

    @Test
    fun `no head of a lane touches another head`() {
        for (u in units) for (inds in combos) {
            val glyphs = laneGlyphs(inds, cx, foot, u)
            for (a in glyphs) for (b in glyphs) {
                if (a === b) continue
                val gap = polygonDistance(laneHeadOutline(a, u), laneHeadOutline(b, u)) / u
                assertTrue("$inds: ${a.indication} head is ${"%.1f".format(gap)} units from ${b.indication}'s", gap >= 1f)
            }
        }
    }

    @Test
    fun `arrows stay on the strip and inside its side margin`() {
        // The strip's rows have 8 dp at each end: 21 units on the turn card, more in the step list.
        val reach = LANE_PITCH / 2f + 21f
        for (u in units) for (inds in combos) for (g in laneGlyphs(inds, cx, foot, u)) {
            val half = LANE_STROKE / 2f
            val pts = (g.body + g.head).map { Offset((it.x - cx) / u, (foot - it.y) / u) } + miterTip(g, u)
            assertTrue("$inds ${g.indication} reaches ${pts.maxOf { kotlin.math.abs(it.x) } + half}", pts.all { kotlin.math.abs(it.x) + half <= reach })
            assertTrue("$inds ${g.indication} rises to ${pts.maxOf { it.y }}", pts.all { it.y <= LANE_GLYPH + 0.5f })
            assertTrue("$inds ${g.indication} drops to ${pts.minOf { it.y }}", pts.all { it.y >= 0f })
        }
    }

    @Test
    fun `glyphs keep Google's measured size`() {
        // Outer tips measured on Google's card at 2.625x, in units from the lane's foot: straight
        // 96.8 up, a left turn 56.3 out at 48.5 up, a slight right 42.45 out at 93.0 up, a sharp
        // right 37 out at the foot.
        fun tip(ind: String) = miterTip(laneGlyphs(listOf(ind), cx, foot, 1f).single(), 1f)
        assertEquals(96.8f, tip("straight").y, 0.3f)
        assertEquals(56.3f, tip("right").x, 0.3f); assertEquals(48.5f, tip("right").y, 0.3f)
        assertEquals(-56.3f, tip("left").x, 0.3f)
        assertEquals(42.45f, tip("slight right").x, 0.3f); assertEquals(93.0f, tip("slight right").y, 0.3f)
        assertEquals(37f, tip("sharp right").x, 1f); assertEquals(0.5f, tip("sharp right").y, 1f)
        assertTrue(tip("uturn").x < 0f)
    }

    @Test
    fun `a straight or right lane is the straight arrow with the right arrow's bend off its shaft`() {
        val glyphs = laneGlyphs(listOf("straight", "slight right"), cx, foot, 1f)
        val straight = glyphs.first { it.indication == "straight" }
        val right = glyphs.first { it.indication == "slight right" }
        // The right arrow leaves the shaft above 40 percent of the height, well under the straight head.
        val leaves = right.body.first { it.x > cx + 0.5f }
        assertTrue(foot - leaves.y > 0.4f * LANE_GLYPH)
        assertTrue(foot - leaves.y < 0.6f * LANE_GLYPH)
        assertEquals(straight.body.last().x, cx)
    }

    @Test
    fun `a valid lane lights only the arrow for the turn being made`() {
        val straightOrRight = Lane(listOf("through", "right"), true)
        assertEquals(listOf("straight", "right"), laneArrows(straightOrRight, ManeuverType.TURN_RIGHT).kinds)
        assertEquals(setOf("right"), laneArrows(straightOrRight, ManeuverType.TURN_RIGHT).lit)
        assertEquals(setOf("straight"), laneArrows(straightOrRight, ManeuverType.STRAIGHT).lit)
        assertEquals(setOf("right"), laneArrows(Lane(listOf("right", "sharp_right"), true), ManeuverType.TURN_RIGHT).lit)
        assertEquals(emptySet<String>(), laneArrows(Lane(listOf("right"), false), ManeuverType.TURN_RIGHT).lit)
        // A lane the data marks valid with no arrow toward the turn lights nothing: a straight-or-
        // right lane beside two left lanes at a left turn.
        assertEquals(emptySet<String>(), laneArrows(Lane(listOf("straight", "right"), true), ManeuverType.TURN_LEFT).lit)
        assertEquals(setOf("left"), laneArrows(Lane(listOf("left"), true), ManeuverType.TURN_LEFT).lit)
        assertEquals(setOf("slight left"), laneArrows(Lane(listOf("straight", "slight left"), true), ManeuverType.TURN_LEFT).lit)
        assertEquals(setOf("straight"), laneArrows(Lane(listOf("straight"), true), ManeuverType.SLIGHT_LEFT).lit)
        // A merge does not say which side: every arrow of a valid lane lights.
        assertEquals(setOf("slight left", "straight"), laneArrows(Lane(listOf("merge_to_left", "none"), true), ManeuverType.MERGE).lit)
        assertEquals(listOf("straight"), laneArrows(Lane(emptyList(), true), ManeuverType.STRAIGHT).kinds)
    }

    @Test
    fun `a head has a sharp point and rounded barb ends`() {
        for (u in units) for (g in laneGlyphs(listOf("straight", "right", "slight left", "sharp right", "uturn"), cx, foot, u)) {
            val outline = laneHeadOutline(g, u)
            val tip = miterTip(g, u)
            assertEquals(g.indication, cx + tip.x * u, outline.first().x, 0.01f)
            assertEquals(g.indication, foot - tip.y * u, outline.first().y, 0.01f)
            // Square corners would sit sqrt(barb^2 + half stroke^2) from the point; rounded ones closer.
            val p = g.head[1]
            val far = outline.maxOf { hypot(it.x - p.x, it.y - p.y) } / u
            val square = hypot(LANE_BARB, LANE_STROKE / 2f)
            assertTrue("${g.indication}: $far", far < square - 0.1f * LANE_ROUND && far > square - 0.3f * LANE_ROUND)
        }
    }

    /** The outer point of a glyph's head in units from the foot: the chevron's miter. */
    private fun miterTip(g: LaneGlyph, u: Float): Offset {
        val p = g.head[1]
        val mid = Offset((g.head[0].x + g.head[2].x) / 2f, (g.head[0].y + g.head[2].y) / 2f)
        val len = hypot(p.x - mid.x, p.y - mid.y)
        val out = LANE_STROKE / 2f * kotlin.math.sqrt(2f) * u
        val tip = Offset(p.x + (p.x - mid.x) / len * out, p.y + (p.y - mid.y) / len * out)
        return Offset((tip.x - cx) / u, (foot - tip.y) / u)
    }

    private fun polygonDistance(a: List<Offset>, b: List<Offset>): Float {
        val ea = (a + a.first()).zipWithNext(); val eb = (b + b.first()).zipWithNext()
        if (a.any { inside(it, b) } || b.any { inside(it, a) }) return 0f
        return ea.minOf { x -> eb.minOf { y -> segmentDistance(x.first, x.second, y.first, y.second) } }
    }

    private fun inside(p: Offset, poly: List<Offset>): Boolean {
        var c = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    private fun segmentDistance(p1: Offset, p2: Offset, q1: Offset, q2: Offset): Float {
        fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        if (cross(p1, p2, q1) * cross(p1, p2, q2) < 0 && cross(q1, q2, p1) * cross(q1, q2, p2) < 0) return 0f
        fun pointTo(p: Offset, a: Offset, b: Offset): Float {
            val dx = b.x - a.x; val dy = b.y - a.y
            val l = dx * dx + dy * dy
            val t = if (l == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l).coerceIn(0f, 1f)
            return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }
        return minOf(pointTo(p1, q1, q2), pointTo(p2, q1, q2), pointTo(q1, p1, p2), pointTo(q2, p1, p2))
    }
}
