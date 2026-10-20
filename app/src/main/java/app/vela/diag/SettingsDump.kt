package app.vela.diag

import android.content.Context

/**
 * The user's settings as one line, for a crash report and a recorded drive: a bug that only
 * happens with a certain mix of switches is otherwise guesswork. Switches, numbers and short
 * option names only. Anything that could be a place, a name or a file is left out: free text, and
 * every key that holds a position, an address, a contact, a provider or a font.
 */
object SettingsDump {
    private val SKIP = Regex("lat|lng|lon|loc|home|work|addr|font|provider|contact|name|query|recent|dismiss|seen|stamp|_ms$|_at$|token|key|session", RegexOption.IGNORE_CASE)
    private val OPTION = Regex("^[A-Za-z0-9_.:-]{1,24}$")

    fun line(context: Context): String = runCatching {
        context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).all.entries
            .filter { (k, v) -> !SKIP.containsMatchIn(k) && (v is Boolean || v is Int || v is Long || v is Float || (v is String && OPTION.matches(v))) }
            .sortedBy { it.key }
            .joinToString(" ") { (k, v) -> "$k=" + if (v is Boolean) (if (v) "1" else "0") else v }
    }.getOrDefault("")

    /**
     * Where fixes come from, for a recorded drive: the location providers that are on, and on
     * GrapheneOS its network location, SUPL, PSDS and geocoder choices, which it keeps in
     * `Settings.Global` under these keys (absent on other phones). Words, never a position.
     */
    /**
     * How Vela reads the device's input, for a report where taps do nothing (issue #744, a head
     * unit whose hardware buttons may register as a D-pad): whether it has a touchscreen, whether
     * Vela treats it as keypad-first (`isDpadFirstDevice`), and the physical input devices that
     * report a D-pad, by their driver names. Words, never a position.
     */
    fun input(context: Context): String = runCatching {
        val touch = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        val pads = ArrayList<String>()
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            val virtual = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) d.isVirtual else id < 0
            if (!virtual && (d.sources and android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD) pads += d.name
        }
        "touchscreen=${if (touch) "yes" else "no"} keypadFirst=${if (app.vela.ui.isDpadFirstDevice(context)) "yes" else "no"} dpadDevices=" +
            (pads.joinToString(";").ifEmpty { "none" })
    }.getOrDefault("unknown")

    fun location(context: Context): String = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val on = lm.allProviders.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        val cr = context.contentResolver
        fun pick(key: String, names: Array<String>): String? {
            val v = android.provider.Settings.Global.getInt(cr, key, -1)
            return if (v < 0) null else "$key=" + (names.getOrNull(v) ?: v.toString())
        }
        val gos = listOfNotNull(
            pick("network_location", arrayOf("off", "apple", "grapheneos_proxy", "apple_china")),
            pick("force_disable_supl", arrayOf("standard", "off", "grapheneos_proxy")),
            pick("psds_server", arrayOf("grapheneos", "standard", "off")),
            pick("geocoder", arrayOf("off", "openstreetmap", "grapheneos")),
        )
        "providers " + on.joinToString(",").ifEmpty { "none" } + (if (gos.isEmpty()) "" else "; grapheneos " + gos.joinToString(" "))
    }.getOrDefault("")
}
