package app.vela.core.data

/**
 * Whether this device is memory-constrained, exposed as a `:core`-visible flag.
 *
 * Same shape and reason as [CategoryFilter.enabled]: the detection lives in `:app`
 * (`app.vela.ui.MemoryPressure`, which needs `ActivityManager`), but the behavior it gates has to
 * act down at the data-source seam. `:core` stays UI-agnostic and never reads an app holder, so the
 * app pushes the value in at startup instead.
 *
 * Off by default, which keeps every roomier device byte-identical to previous behavior.
 */
object LowRamMode {

    /** Set once from `VelaApp.onCreate` after `MemoryPressure.init`. */
    @Volatile var enabled: Boolean = false

    /**
     * Heap-class ceiling for the low-RAM path, INCLUSIVE.
     *
     * 128 is the heap class OEMs hand out across 1 GB phones and the low end of 2 GB ones, so an
     * exclusive `1..127` leaves out the devices this path exists for. 192 and up stays normal.
     */
    const val LOW_HEAP_CLASS_MB = 128

    /**
     * Total-RAM ceiling for the low-RAM path, INCLUSIVE.
     *
     * `ActivityManager.MemoryInfo.totalMem` reports what the OS can hand out, not the marketing
     * figure: a nominal 2 GB phone reports about 1900 MB and lands inside this, a 3 GB phone about
     * 2800 MB and does not.
     */
    const val LOW_TOTAL_RAM_MB = 2048

    /**
     * Whether this device is memory-constrained, from probes the `:app` side gathers.
     *
     * Pure so it can be unit-tested. Pass 0 for a probe that could not be read; 0 is never evidence
     * of a roomy device.
     *
     * @param isLowRamDevice `ActivityManager.isLowRamDevice`, which only Go-configured builds set.
     * @param heapClassMb `ActivityManager.memoryClass`, a Dalvik knob an OEM can set to anything.
     * @param totalRamMb total system RAM, the signal that describes the device.
     * @param is32Bit whether THIS PROCESS is 32-bit (`!android.os.Process.is64Bit()`).
     */
    fun classify(
        isLowRamDevice: Boolean,
        heapClassMb: Int,
        totalRamMb: Int,
        is32Bit: Boolean = false,
    ): Boolean = when {
        isLowRamDevice -> true
        // A 32-bit process is constrained whatever the phone holds, because the limit is address
        // space rather than RAM, and scudo answers a failed mmap by aborting the process. That
        // arrives as a native SIGABRT ("Scudo ERROR: internal map or unmap failure") inside
        // whatever allocated next, so the crash never names what filled the space. Measured on a
        // QM215 handset running a 32-bit userspace on 64-bit silicon: 3566 MB of RAM, heap class
        // 256, isLowRamDevice false, dead 49 s into a routing session after three main-thread ANRs
        // blocked on the GC, with lowmemorykiller reporting "watermarks ok" throughout.
        is32Bit -> true
        heapClassMb in 1..LOW_HEAP_CLASS_MB -> true
        totalRamMb in 1..LOW_TOTAL_RAM_MB -> true
        // Neither probe read. Assume constrained: that costs a roomy phone about a second on its
        // first mic tap, while the other way can OOM a phone with no headroom.
        heapClassMb == 0 && totalRamMb == 0 -> true
        else -> false
    }
}
