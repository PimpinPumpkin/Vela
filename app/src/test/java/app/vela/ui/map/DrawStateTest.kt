package app.vela.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** Adding, removing and undoing points of a drawing (issue #846). Points are in Davis. */
class DrawStateTest {

    // A square of about 100 m: lat, lng of its corners in drawing order, and a tap past its right side.
    private val sq = listOf(38.5440 to -121.7410, 38.5440 to -121.7398, 38.5449 to -121.7398, 38.5449 to -121.7410)
    private val right = 38.54445 to -121.7392

    private fun DrawState.addAll(vararg p: Pair<Double, Double>) = p.fold(this) { d, (lat, lng) -> d.add(lat, lng) }
    private fun DrawState.points() = pts.chunked(2).map { it[0] to it[1] }

    @Test fun `an area tap past a side goes into that side and Undo takes it back out`() {
        val square = DrawState(closed = true).addAll(*sq.toTypedArray())
        assertEquals(sq, square.points())
        val five = square.add(right.first, right.second)
        assertEquals(listOf(sq[0], sq[1], right, sq[2], sq[3]), five.points())
        assertEquals(sq, five.undo().points())
        // Undo again takes the last corner tapped, then the one before.
        assertEquals(sq.take(3), five.undo().undo().points())
        assertEquals(sq.take(2), five.undo().undo().undo().points())
    }

    @Test fun `a line always adds at the end`() {
        val line = DrawState(closed = false).addAll(*sq.toTypedArray(), right)
        assertEquals(sq + right, line.points())
        assertEquals(sq, line.undo().points())
    }

    @Test fun `removing a point keeps Undo on the newest of the rest`() {
        val five = DrawState(closed = true).addAll(*sq.toTypedArray(), right)
        // Remove the first corner; the inserted point is still the newest.
        val four = five.remove(0)
        assertEquals(listOf(sq[1], right, sq[2], sq[3]), four.points())
        assertEquals(listOf(sq[1], sq[2], sq[3]), four.undo().points())
        // Remove the inserted point by hand; Undo then takes the last corner tapped.
        assertEquals(sq.take(3), five.remove(2).undo().points())
        assertEquals(five, five.remove(9))
    }

    @Test fun `a saved shape opened for changes undoes from its end, then its new points first`() {
        val saved = DrawState(pts = sq.flatMap { listOf(it.first, it.second) }, closed = true)
        assertEquals(sq.take(3), saved.undo().points())
        val five = saved.add(right.first, right.second)
        assertEquals(listOf(sq[0], sq[1], right, sq[2], sq[3]), five.points())
        assertEquals(sq, five.undo().points())
        assertEquals(sq.take(3), five.undo().undo().points())
    }

    @Test fun `undo on nothing is nothing`() {
        val empty = DrawState()
        assertEquals(empty, empty.undo())
    }
}
