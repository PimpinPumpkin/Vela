package app.vela.core.data

/**
 * Whether this device is memory-constrained, exposed as a `:core`-visible flag.
 *
 * Same shape and reason as [CategoryFilter.enabled]: the detection lives in `:app`
 * (`app.vela.ui.MemoryPressure`, which needs `ActivityManager`), but the behaviour it gates has to
 * act down at the data-source seam. `:core` stays UI-agnostic and never reads an app holder, so the
 * app pushes the value in at startup instead.
 *
 * Off by default, which keeps every roomier device byte-identical to previous behaviour.
 */
object LowRamMode {

    /** Set once from `VelaApp.onCreate` after `MemoryPressure.init`. */
    @Volatile var enabled: Boolean = false
}
