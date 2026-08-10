package app.vela.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Is the current default network a **constrained** one - satellite, or otherwise flagged by the
 * system as bandwidth-limited (issue #235)?
 *
 * Vela's manifest declares `PROPERTY_SATELLITE_DATA_OPTIMIZED`, which is what lets carriers who
 * gate satellite service (Rogers, AT&T, KDDI) pass its traffic at all. Android's own guidance is
 * that the declaration is only half of it: an app that claims to be satellite-optimized is also
 * expected to ADAPT and conserve on such a link. This is the detector behind that adaptation.
 *
 * Both signals are read by NAME through reflection rather than compiled against:
 * `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED` is Android 16+ and `TRANSPORT_SATELLITE` is 15+,
 * while Vela compiles against 35 and ships to minSdk 26. Reflection resolves them where they
 * exist and yields null everywhere else - no hardcoded framework integer that could silently
 * mean something different on another release, and no compileSdk bump for a two-field read.
 */
object ConstrainedNetwork {

    private val notConstrainedCap: Int? by lazy { capabilityField("NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED") }
    private val satelliteTransport: Int? by lazy { capabilityField("TRANSPORT_SATELLITE") }

    private fun capabilityField(name: String): Int? = runCatching {
        NetworkCapabilities::class.java.getField(name).getInt(null)
    }.getOrNull()

    /** True when the active network is satellite or lacks the "not bandwidth constrained"
     *  capability. False on any older platform that reports neither - absence of the signal is
     *  NOT evidence of a constrained link, and guessing would put every user on the data diet. */
    fun isConstrained(cm: ConnectivityManager?): Boolean {
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return isConstrained(caps)
    }

    fun isConstrained(caps: NetworkCapabilities): Boolean {
        // Android's guidance is to check BOTH: a satellite transport should take the conserving
        // path whatever its capability bits say, and a non-satellite link can still be flagged
        // constrained by the carrier.
        satelliteTransport?.let { if (runCatching { caps.hasTransport(it) }.getOrDefault(false)) return true }
        notConstrainedCap?.let { return !runCatching { caps.hasCapability(it) }.getOrDefault(true) }
        return false
    }
}
