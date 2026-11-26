package app.vela.ui.nav

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LaneShapeTest {
    private val combos = listOf(
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
        listOf("left", "right"),
        listOf("left", "uturn"),
        listOf("left", "straight", "sharp right"),
        listOf("slight left", "straight", "right"),
    )

    // The turn card's strip cell and the steps list's.
    private val cells = listOf(38f to 34f, 30f to 26f)

    @Test
    fun `no arrowhead touches another arrow of its lane`() {
        for ((w, h) in cells) for (inds in combos) {
            val shape = laneShape(inds, w, h)
            val stroke = w * 0.11f
            for (a in shape.arms) for (b in shape.arms) {
                if (a === b) continue
                val gap = minDistance(head(a, w), head(b, w) + outerLegs(b)) - stroke
                assertTrue("$inds in $w x $h: ${a.indication} head is ${"%.2f".format(gap)} from ${b.indication}", gap >= 0.015f * w)
            }
        }
    }

    @Test
    fun `every arrow stays in its cell`() {
        for ((w, h) in cells) for (inds in combos) {
            val shape = laneShape(inds, w, h)
            val half = w * 0.11f / 2f
            val xs = shape.arms.flatMap { arm -> arm.path.map { it.x } + laneBarbs(arm, w).map { it.second.x } }
            assertTrue("$inds in $w x $h reaches ${xs.min() - half}", xs.min() - half >= -0.02f * w)
            assertTrue("$inds in $w x $h reaches ${xs.max() + half}", xs.max() + half <= 1.02f * w)
            val ys = shape.arms.flatMap { arm -> arm.path.map { it.y } + laneBarbs(arm, w).map { it.second.y } }
            assertTrue("$inds in $w x $h reaches down to ${ys.max() + half}", ys.max() + half <= h)
        }
    }

    @Test
    fun `a turn arm points the way the same turn's arrow does in a lane of its own`() {
        for ((w, h) in cells) for (inds in combos) {
            if ("uturn" in inds) continue
            for (arm in laneShape(inds, w, h).arms) {
                val single = laneShape(listOf(arm.indication), w, h).arms.single()
                assertEquals("$inds ${arm.indication} in $w x $h", lastLegDegrees(single), lastLegDegrees(arm), 0.5)
            }
        }
    }

    @Test
    fun `a lane with one arrow bends where it always has`() {
        val shape = laneShape(listOf("right"), 38f, 34f)
        assertEquals(Offset(19f, 34f * 0.44f), shape.shaftTo)
        assertEquals(1, shape.arms.size)
    }

    @Test
    fun `a straight or right lane splits the right arm off low and keeps the straight arrow whole`() {
        val shape = laneShape(listOf("straight", "slight right"), 38f, 34f)
        val straight = shape.arms.first { it.indication == "straight" }
        val right = shape.arms.first { it.indication == "slight right" }
        assertEquals(straight.path.first(), right.path.first())
        assertTrue(right.path.first().y > 34f * 0.6f)
        assertTrue(right.path.last().x - straight.path.last().x > 38f * 0.4f)
    }

    private fun lastLegDegrees(arm: LaneArm): Double {
        val tip = arm.path.last()
        val from = arm.path[arm.path.size - 2]
        return Math.toDegrees(kotlin.math.atan2((tip.x - from.x).toDouble(), (from.y - tip.y).toDouble()))
    }

    /** The last barb-length of an arm's last leg and its two barbs. */
    private fun head(arm: LaneArm, w: Float): List<Pair<Offset, Offset>> {
        val tip = arm.path.last()
        val from = arm.path[arm.path.size - 2]
        val len = hypot(tip.x - from.x, tip.y - from.y)
        val t = (1f - w * 0.22f / len).coerceAtLeast(0f)
        return listOf(Offset(from.x + (tip.x - from.x) * t, from.y + (tip.y - from.y) * t) to tip) + laneBarbs(arm, w)
    }

    /** An arm's legs without the first 40% of the first one, where arms of one lane meet. */
    private fun outerLegs(arm: LaneArm): List<Pair<Offset, Offset>> {
        val legs = arm.path.zipWithNext()
        val (a, b) = legs.first()
        return listOf(Offset(a.x + (b.x - a.x) * 0.4f, a.y + (b.y - a.y) * 0.4f) to b) + legs.drop(1)
    }

    private fun minDistance(xs: List<Pair<Offset, Offset>>, ys: List<Pair<Offset, Offset>>): Float =
        xs.minOf { x -> ys.minOf { y -> segmentDistance(x.first, x.second, y.first, y.second) } }

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
