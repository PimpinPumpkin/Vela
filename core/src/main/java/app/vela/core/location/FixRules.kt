package app.vela.core.location

/**
 * Which location fixes the map's dot takes (issue #630). GPS fixes are always taken; these two
 * rules keep the coarse NETWORK feed (Wi-Fi and cell, often hundreds of meters off) from walking
 * the dot around, and let a real GPS lock land at once.
 */
object FixRules {
    /** Organic Maps' isLocationBetterThanLast: the last fix's accuracy radius grows by the speed
     *  (at least 5 m/s) for every second of its age, and a new fix must beat that. */
    fun betterThanLast(newAccM: Float, lastAccM: Float, ageS: Double, speedMps: Double): Boolean =
        newAccM < lastAccM + maxOf(5.0, speedMps) * ageS

    /**
     * A fix at least twice as accurate as the one showing, which is itself 50 m or worse: an
     * upgrade to take as is, not an outlier to hold back. A GPS fix after a GPS fix must also
     * land within twice the shown fix's own radius ([movedM] from it), so it refines that
     * position instead of contradicting it: under poor sky a receiver's accuracy figure swings
     * from fix to fix, and a multipath leap taken as is becomes the position the outlier hold
     * then keeps against the next good fixes. A first lock sharpening on a phone with no
     * network location still lands at once.
     */
    fun isUpgrade(shownAccM: Float?, newAccM: Float, shownIsGps: Boolean, newIsGps: Boolean, movedM: Double): Boolean {
        if (shownAccM == null || shownAccM < 50f || newAccM * 2f > shownAccM) return false
        return !(shownIsGps && newIsGps) || movedM <= 2.0 * shownAccM
    }
}
