package app.vela.core.vehicle

/**
 * Steering-wheel buttons as a BYD head unit's accessibility key filter receives them (SPEC 10.9).
 * Codes vary by model and firmware, so these are the codes seen on some cars, not a promise for
 * every one. Vela acts on the voice keys only; every other key is the car's.
 */
object WheelKeys {
    /** The voice button, its long-press code, and the companion code some cars (Atto 3) send right
     *  after it. Some firmwares send a long code only after the release. */
    const val VOICE = 304
    const val VOICE_LONG = 312
    const val VOICE_COMPANION = 327
    const val VOICE_COMPANION_LONG = 328
    val VOICE_KEYS = setOf(VOICE, VOICE_LONG, VOICE_COMPANION, VOICE_COMPANION_LONG)

    /** Call keys. The car's own phone app answers and ends Bluetooth calls; Vela has no call of its
     *  own, so these always pass. On DiLink 3 the window manager eats 314 and 309 before any app and
     *  sends `com.byd.btcall.action.CLOSE_BLUETOOTHSETTING` instead, a report of a hang-up the car
     *  already handled. Vela does not listen for it. */
    const val ANSWER = 313
    const val HANG_UP = 314
    const val MULTIFUNCTION = 309

    /** The scroll wheel's press (353) and DiLink 3's play/pause (331). Media belongs to the player
     *  and the car's media center, so these pass too. */
    const val PLAY_PAUSE = 353
    const val PLAY_PAUSE_DILINK3 = 331

    /** A second voice-key press this soon after an owned one is the same press: a late long code
     *  or the companion code. */
    const val COMPANION_WINDOW_MS = 350L
    /** A held key with no edge for this long lost its release. */
    const val STALE_PRESS_MS = 3_000L
}

enum class WheelAction { REPEAT_INSTRUCTION }

/**
 * Decides, for one key event at a time, whether Vela takes it and what it does. A press is owned
 * whole or not at all: the down, any repeated downs, folded long codes and the release all go the
 * same way as the first down, so the car never sees a release without its press. The action runs
 * once, on the first down. Not thread-safe: the key filter calls it on one thread.
 */
class WheelKeyRouter {
    data class Decision(val consume: Boolean, val action: WheelAction? = null)

    private data class Press(val owned: Boolean, val atMs: Long)

    private val held = HashMap<Int, Press>()
    private var lastUpMs = -WheelKeys.COMPANION_WINDOW_MS - 1
    private var lastUpOwned = false

    /** [guiding] is true while a drive is giving directions (navigating, not paused, not arrived). */
    fun onKey(keyCode: Int, down: Boolean, nowMs: Long, guiding: Boolean): Decision {
        if (keyCode !in WheelKeys.VOICE_KEYS) return PASS
        held.entries.removeAll { nowMs - it.value.atMs > WheelKeys.STALE_PRESS_MS }
        if (!down) {
            val press = held.remove(keyCode) ?: return PASS
            lastUpMs = nowMs
            lastUpOwned = press.owned
            return if (press.owned) CONSUME else PASS
        }
        // A repeated down, or a long or companion code while another voice key is down.
        val sameOrOther = held[keyCode] ?: held.values.firstOrNull()
        if (sameOrOther != null) return follow(keyCode, sameOrOther.owned, nowMs)
        // A late long code or a companion code just after a release.
        if (nowMs - lastUpMs in 0..WheelKeys.COMPANION_WINDOW_MS) return follow(keyCode, lastUpOwned, nowMs)
        held[keyCode] = Press(guiding, nowMs)
        return if (guiding) Decision(true, WheelAction.REPEAT_INSTRUCTION) else PASS
    }

    private fun follow(keyCode: Int, owned: Boolean, nowMs: Long): Decision {
        held[keyCode] = Press(owned, nowMs)
        return if (owned) CONSUME else PASS
    }

    private companion object {
        val PASS = Decision(false)
        val CONSUME = Decision(true)
    }
}
