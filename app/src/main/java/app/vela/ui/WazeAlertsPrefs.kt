package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.core.data.WazeAlertCategory

/**
 * Preferences for crowdsourced incident reports from Waze.
 * Supports granular toggles for individual alert categories and spoken warnings.
 */
object WazeAlertsPrefs {
    val masterOn = mutableStateOf(true)

    // Category toggles
    val policeOn = mutableStateOf(true)
    val accidentsOn = mutableStateOf(true)
    val hazardsOn = mutableStateOf(true)
    val closuresOn = mutableStateOf(true)
    val jamsOn = mutableStateOf(true)

    // Spoken navigation warnings
    val policeWarnOn = mutableStateOf(false)
    val accidentsWarnOn = mutableStateOf(false)
    val hazardsWarnOn = mutableStateOf(false)
    val closuresWarnOn = mutableStateOf(true)  // default on: closure on your route is always relevant
    val jamsWarnOn = mutableStateOf(false)

    fun init(context: Context) {
        val p = prefs(context)
        val legacyPolice = p.getBoolean(KEY_LEGACY_POLICE, true)
        masterOn.value = p.getBoolean(KEY_MASTER, legacyPolice)
        policeOn.value = p.getBoolean(KEY_POLICE, true)
        accidentsOn.value = p.getBoolean(KEY_ACCIDENTS, true)
        hazardsOn.value = p.getBoolean(KEY_HAZARDS, true)
        closuresOn.value = p.getBoolean(KEY_CLOSURES, true)
        jamsOn.value = p.getBoolean(KEY_JAMS, true)

        val legacyPoliceWarn = p.getBoolean(KEY_LEGACY_POLICE_WARN, false)
        policeWarnOn.value = p.getBoolean(KEY_POLICE_WARN, legacyPoliceWarn)
        accidentsWarnOn.value = p.getBoolean(KEY_ACCIDENTS_WARN, false)
        hazardsWarnOn.value = p.getBoolean(KEY_HAZARDS_WARN, false)
        closuresWarnOn.value = p.getBoolean(KEY_CLOSURES_WARN, true)
        jamsWarnOn.value = p.getBoolean(KEY_JAMS_WARN, false)

        // Sync legacy objects
        syncLegacy(context)
    }

    fun setMaster(context: Context, value: Boolean) {
        masterOn.value = value
        prefs(context).edit().putBoolean(KEY_MASTER, value).apply()
        syncLegacy(context)
    }

    fun setPolice(context: Context, value: Boolean) {
        policeOn.value = value
        prefs(context).edit().putBoolean(KEY_POLICE, value).apply()
        syncLegacy(context)
    }

    fun setAccidents(context: Context, value: Boolean) {
        accidentsOn.value = value
        prefs(context).edit().putBoolean(KEY_ACCIDENTS, value).apply()
    }

    fun setHazards(context: Context, value: Boolean) {
        hazardsOn.value = value
        prefs(context).edit().putBoolean(KEY_HAZARDS, value).apply()
    }

    fun setClosures(context: Context, value: Boolean) {
        closuresOn.value = value
        prefs(context).edit().putBoolean(KEY_CLOSURES, value).apply()
    }

    fun setJams(context: Context, value: Boolean) {
        jamsOn.value = value
        prefs(context).edit().putBoolean(KEY_JAMS, value).apply()
    }

    fun setPoliceWarn(context: Context, value: Boolean) {
        policeWarnOn.value = value
        prefs(context).edit().putBoolean(KEY_POLICE_WARN, value).apply()
        syncLegacy(context)
    }

    fun setAccidentsWarn(context: Context, value: Boolean) {
        accidentsWarnOn.value = value
        prefs(context).edit().putBoolean(KEY_ACCIDENTS_WARN, value).apply()
    }

    fun setHazardsWarn(context: Context, value: Boolean) {
        hazardsWarnOn.value = value
        prefs(context).edit().putBoolean(KEY_HAZARDS_WARN, value).apply()
    }

    fun setClosuresWarn(context: Context, value: Boolean) {
        closuresWarnOn.value = value
        prefs(context).edit().putBoolean(KEY_CLOSURES_WARN, value).apply()
    }

    fun setJamsWarn(context: Context, value: Boolean) {
        jamsWarnOn.value = value
        prefs(context).edit().putBoolean(KEY_JAMS_WARN, value).apply()
    }

    fun enabledCategories(): Set<WazeAlertCategory> {
        if (!masterOn.value) return emptySet()
        val set = mutableSetOf<WazeAlertCategory>()
        if (policeOn.value) set.add(WazeAlertCategory.POLICE)
        if (accidentsOn.value) set.add(WazeAlertCategory.ACCIDENT)
        if (hazardsOn.value) set.add(WazeAlertCategory.HAZARD)
        if (closuresOn.value) set.add(WazeAlertCategory.ROAD_CLOSED)
        if (jamsOn.value) set.add(WazeAlertCategory.JAM)
        return set
    }

    private fun syncLegacy(context: Context) {
        val pOn = masterOn.value && policeOn.value
        val pWarn = pOn && policeWarnOn.value
        WazePolice.on.value = pOn
        WazePoliceWarn.on.value = pWarn
        prefs(context).edit()
            .putBoolean(KEY_LEGACY_POLICE, pOn)
            .putBoolean(KEY_LEGACY_POLICE_WARN, pWarn)
            .apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY_MASTER = "waze_alerts_master_on"
    private const val KEY_POLICE = "waze_alerts_police_on"
    private const val KEY_ACCIDENTS = "waze_alerts_accidents_on"
    private const val KEY_HAZARDS = "waze_alerts_hazards_on"
    private const val KEY_CLOSURES = "waze_alerts_closures_on"
    private const val KEY_JAMS = "waze_alerts_jams_on"

    private const val KEY_POLICE_WARN = "waze_alerts_police_warn_on"
    private const val KEY_ACCIDENTS_WARN = "waze_alerts_accidents_warn_on"
    private const val KEY_HAZARDS_WARN = "waze_alerts_hazards_warn_on"
    private const val KEY_CLOSURES_WARN = "waze_alerts_closures_warn_on"
    private const val KEY_JAMS_WARN = "waze_alerts_jams_warn_on"

    private const val KEY_LEGACY_POLICE = "waze_police_on"
    private const val KEY_LEGACY_POLICE_WARN = "waze_police_warn_on"
}
