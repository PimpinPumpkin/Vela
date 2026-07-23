package app.vela.ui

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide memory-pressure fan-out, in the same shape as the other app-level holders
 * (`TransitLayer`, `AppTheme`): `init()` from `VelaApp`, then anything holding a large or native
 * allocation registers a release callback.
 *
 * Why registration and not a Hilt entry point: reaching `WhisperRecognizer`/`PiperSynth` from
 * `onTrimMemory` through an EntryPoint would CONSTRUCT them if they had never been used, so a
 * trim would allocate the very models it is trying to free. A holder registers only once it
 * actually owns something worth releasing, so a trim can never create work.
 *
 * Measured on the M5 (2.9 GB, Android 13, standardDebug) before this existed: TRIM_MEMORY_COMPLETE
 * released 0 KB, because nothing in the app implemented ComponentCallbacks2 at all.
 */
object MemoryPressure {

    /** A registered releaser. [level] is a `ComponentCallbacks2.TRIM_MEMORY_*` constant. */
    fun interface Listener {
        fun release(level: Int)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * True when the OS classes this device as low-RAM (`ActivityManager.isLowRamDevice`) OR its
     * heap class is small enough that our normal budgets do not fit. The heap-class arm matters:
     * plenty of cheap keypad phones do NOT set the low-RAM system property yet still hand out a
     * 96 MB heap class, and those are exactly the phones this work is for.
     */
    @Volatile var lowRam: Boolean = false
        private set

    /** The device's normal (non-large) heap class in MB. 0 until [init]. */
    @Volatile var heapClassMb: Int = 0
        private set

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        heapClassMb = am?.memoryClass ?: 0
        val forced = forcedLowRam()
        lowRam = forced ?: ((am?.isLowRamDevice == true) || (heapClassMb in 1..127))
        android.util.Log.i("MemoryPressure", "init lowRam=$lowRam heapClassMb=$heapClassMb forced=${forced?.toString() ?: "no"}")
    }

    /**
     * Debug-only override so the low-RAM path can be exercised on a normal dev phone:
     *
     *     adb shell setprop debug.vela.lowram true    # then relaunch the app
     *     adb shell setprop debug.vela.lowram ""      # back to real detection
     *
     * Without this the low-RAM branches are dead code on every device we actually own (the M5 dev
     * phone reports heapClassMb=256, lowRam=false), which means they would ship unverified. Returns
     * null when unset or on a non-debug build, so release behaviour is untouched.
     */
    private fun forcedLowRam(): Boolean? {
        if (!app.vela.BuildConfig.DEBUG) return null
        val v = runCatching {
            @Suppress("PrivateApi")
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java).invoke(null, "debug.vela.lowram") as? String
        }.getOrNull()
        return when (v?.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    /** Register [listener]; returns a handle whose `close()` unregisters. Safe to call any time. */
    fun register(listener: Listener): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /**
     * Fan a trim out to every registered holder. Each listener is isolated: one throwing must not
     * stop the rest from releasing, since under real pressure we want every byte we can get.
     */
    fun dispatch(level: Int) {
        android.util.Log.i("MemoryPressure", "dispatch level=$level listeners=${listeners.size}")
        for (l in listeners) {
            runCatching { l.release(level) }
                .onFailure { android.util.Log.w("MemoryPressure", "listener failed", it) }
        }
    }

    /**
     * The app is backgrounded or the OS is genuinely short of memory, so caches that only speed
     * things up should go. Everything at or above this level is a "drop it" signal.
     */
    fun isSevere(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    /** Only the harshest levels, where we drop things that cost real time to rebuild. */
    fun isCritical(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
}
