package app.vela.core.data

/**
 * "This link is precious" flag, set by the app when the active network is satellite or otherwise
 * bandwidth-constrained (issue #235). Same seam as [LowRamMode]: a plain `:core` flag written from
 * `:app`, because `:core` cannot read an app-side holder.
 *
 * Consumers make the SAME trade LowRamMode does - a leaner ambient fan-out - but for a different
 * reason: there the constraint is heap, here it is bytes over a satellite link.
 */
object LowDataMode {
    @Volatile var enabled: Boolean = false
}
