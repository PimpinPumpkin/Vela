package app.vela.web

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Settings > Privacy "Block Google's page telemetry" (pref `block_google_telemetry`, default OFF,
 * 2026-09-25). Google's pages report on themselves: the `play.google.com/log` pings, `gen_204`
 * beacons and the account bar's background calls. Nothing Vela reads depends on them, so blocking
 * them costs nothing on screen, but a real browser always sends them, and a session whose pages
 * never do looks less like a person to Google's traffic scoring, which is what decides whether a
 * session gets the limited view. So they flow by default and blocking is the user's choice.
 * [WebProxy.intercept] answers them locally with an empty 200 when this is on, whether or not the
 * proxy itself is on. The calibration / debug dial `webProxyBlockLogs` still overrides when set.
 */
object GoogleTelemetry {
    val block = mutableStateOf(false)

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    fun init(context: Context) { block.value = prefs(context).getBoolean(KEY, false) }

    fun set(context: Context, on: Boolean) {
        block.value = on
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    private const val KEY = "block_google_telemetry"
}
