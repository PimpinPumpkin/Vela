package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import app.vela.core.data.WazeAlertCategory
import app.vela.core.data.WazePoliceAlert
import java.util.Locale

/**
 * User-configurable warning trigger distances (in meters) for navigation alerts.
 * Follows morphe-patches / Waze CGE customization style:
 * - 0 = Dynamic (speed-scaled: 12 seconds floored at 150m, capped at 600m)
 * - -1 = Use default distance setting
 * - >0 = Fixed trigger distance in meters (e.g. 200m, 300m, 500m, 750m, 1000m, 1500m, or custom)
 */
object AlertDistancePrefs {
    const val DISTANCE_DYNAMIC = 0
    const val DISTANCE_USE_DEFAULT = -1

    val defaultMeters = mutableIntStateOf(DISTANCE_DYNAMIC)

    // Cameras
    val speedCamMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val flockMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)

    // Police
    val policeMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val policeHiddenMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val policeCameraMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)

    // Accidents
    val accidentMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val accidentMinorMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)

    // Hazards
    val hazardMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val hazardConstructionMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val hazardPotholeMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val hazardShoulderMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val hazardWeatherMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)

    // Closures & Traffic
    val closureMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)
    val jamMeters = mutableIntStateOf(DISTANCE_USE_DEFAULT)

    fun init(context: Context) {
        val p = prefs(context)
        defaultMeters.intValue = p.getInt(KEY_DEFAULT, DISTANCE_DYNAMIC)
        speedCamMeters.intValue = p.getInt(KEY_SPEED_CAM, DISTANCE_USE_DEFAULT)
        flockMeters.intValue = p.getInt(KEY_FLOCK, DISTANCE_USE_DEFAULT)
        policeMeters.intValue = p.getInt(KEY_POLICE, DISTANCE_USE_DEFAULT)
        policeHiddenMeters.intValue = p.getInt(KEY_POLICE_HIDDEN, DISTANCE_USE_DEFAULT)
        policeCameraMeters.intValue = p.getInt(KEY_POLICE_CAMERA, DISTANCE_USE_DEFAULT)
        accidentMeters.intValue = p.getInt(KEY_ACCIDENT, DISTANCE_USE_DEFAULT)
        accidentMinorMeters.intValue = p.getInt(KEY_ACCIDENT_MINOR, DISTANCE_USE_DEFAULT)
        hazardMeters.intValue = p.getInt(KEY_HAZARD, DISTANCE_USE_DEFAULT)
        hazardConstructionMeters.intValue = p.getInt(KEY_HAZARD_CONSTRUCTION, DISTANCE_USE_DEFAULT)
        hazardPotholeMeters.intValue = p.getInt(KEY_HAZARD_POTHOLE, DISTANCE_USE_DEFAULT)
        hazardShoulderMeters.intValue = p.getInt(KEY_HAZARD_SHOULDER, DISTANCE_USE_DEFAULT)
        hazardWeatherMeters.intValue = p.getInt(KEY_HAZARD_WEATHER, DISTANCE_USE_DEFAULT)
        closureMeters.intValue = p.getInt(KEY_CLOSURE, DISTANCE_USE_DEFAULT)
        jamMeters.intValue = p.getInt(KEY_JAM, DISTANCE_USE_DEFAULT)
    }

    fun setDefault(context: Context, value: Int) {
        defaultMeters.intValue = value
        prefs(context).edit().putInt(KEY_DEFAULT, value).apply()
    }

    fun setSpeedCam(context: Context, value: Int) {
        speedCamMeters.intValue = value
        prefs(context).edit().putInt(KEY_SPEED_CAM, value).apply()
    }

    fun setFlock(context: Context, value: Int) {
        flockMeters.intValue = value
        prefs(context).edit().putInt(KEY_FLOCK, value).apply()
    }

    fun setPolice(context: Context, value: Int) {
        policeMeters.intValue = value
        prefs(context).edit().putInt(KEY_POLICE, value).apply()
    }

    fun setPoliceHidden(context: Context, value: Int) {
        policeHiddenMeters.intValue = value
        prefs(context).edit().putInt(KEY_POLICE_HIDDEN, value).apply()
    }

    fun setPoliceCamera(context: Context, value: Int) {
        policeCameraMeters.intValue = value
        prefs(context).edit().putInt(KEY_POLICE_CAMERA, value).apply()
    }

    fun setAccident(context: Context, value: Int) {
        accidentMeters.intValue = value
        prefs(context).edit().putInt(KEY_ACCIDENT, value).apply()
    }

    fun setAccidentMinor(context: Context, value: Int) {
        accidentMinorMeters.intValue = value
        prefs(context).edit().putInt(KEY_ACCIDENT_MINOR, value).apply()
    }

    fun setHazard(context: Context, value: Int) {
        hazardMeters.intValue = value
        prefs(context).edit().putInt(KEY_HAZARD, value).apply()
    }

    fun setHazardConstruction(context: Context, value: Int) {
        hazardConstructionMeters.intValue = value
        prefs(context).edit().putInt(KEY_HAZARD_CONSTRUCTION, value).apply()
    }

    fun setHazardPothole(context: Context, value: Int) {
        hazardPotholeMeters.intValue = value
        prefs(context).edit().putInt(KEY_HAZARD_POTHOLE, value).apply()
    }

    fun setHazardShoulder(context: Context, value: Int) {
        hazardShoulderMeters.intValue = value
        prefs(context).edit().putInt(KEY_HAZARD_SHOULDER, value).apply()
    }

    fun setHazardWeather(context: Context, value: Int) {
        hazardWeatherMeters.intValue = value
        prefs(context).edit().putInt(KEY_HAZARD_WEATHER, value).apply()
    }

    fun setClosure(context: Context, value: Int) {
        closureMeters.intValue = value
        prefs(context).edit().putInt(KEY_CLOSURE, value).apply()
    }

    fun setJam(context: Context, value: Int) {
        jamMeters.intValue = value
        prefs(context).edit().putInt(KEY_JAM, value).apply()
    }

    /** Effective lead distance in meters, or null for dynamic speed-scaling. */
    fun speedCamLeadM(): Double? = resolveLeadM(speedCamMeters.intValue)
    fun flockLeadM(): Double? = resolveLeadM(flockMeters.intValue)
    fun policeLeadM(): Double? = resolveLeadM(policeMeters.intValue)
    fun hazardLeadM(): Double? = resolveLeadM(hazardMeters.intValue)
    fun accidentLeadM(): Double? = resolveLeadM(accidentMeters.intValue)
    fun closureLeadM(): Double? = resolveLeadM(closureMeters.intValue)
    fun jamLeadM(): Double? = resolveLeadM(jamMeters.intValue)

    fun wazeAlertLeadM(alert: WazePoliceAlert?): Double? =
        wazeAlertLeadM(alert?.category(), alert?.subtype)

    fun wazeAlertLeadM(cat: WazeAlertCategory?, subtype: String? = null): Double? {
        if (cat == null) return defaultLeadM()
        val sub = subtype?.uppercase(Locale.US) ?: ""
        val specific = when (cat) {
            WazeAlertCategory.POLICE -> when {
                "CAMERA" in sub || "SPEED" in sub -> policeCameraMeters.intValue
                "HIDING" in sub || "HIDDEN" in sub -> policeHiddenMeters.intValue
                else -> policeMeters.intValue
            }
            WazeAlertCategory.ACCIDENT -> when {
                "MINOR" in sub -> accidentMinorMeters.intValue
                else -> accidentMeters.intValue
            }
            WazeAlertCategory.HAZARD -> when {
                "CONSTRUCTION" in sub || "WORKS" in sub -> hazardConstructionMeters.intValue
                "POTHOLE" in sub || "POT_HOLE" in sub || "OBJECT" in sub -> hazardPotholeMeters.intValue
                "SHOULDER" in sub -> hazardShoulderMeters.intValue
                "WEATHER" in sub || "FOG" in sub || "ICE" in sub || "RAIN" in sub || "FLOOD" in sub || "SNOW" in sub ->
                    hazardWeatherMeters.intValue
                else -> hazardMeters.intValue
            }
            WazeAlertCategory.ROAD_CLOSED -> closureMeters.intValue
            WazeAlertCategory.JAM -> jamMeters.intValue
            WazeAlertCategory.OTHER -> DISTANCE_USE_DEFAULT
        }
        if (specific != DISTANCE_USE_DEFAULT) {
            return resolveLeadM(specific)
        }
        val categoryVal = when (cat) {
            WazeAlertCategory.POLICE -> policeMeters.intValue
            WazeAlertCategory.ACCIDENT -> accidentMeters.intValue
            WazeAlertCategory.HAZARD -> hazardMeters.intValue
            WazeAlertCategory.ROAD_CLOSED -> closureMeters.intValue
            WazeAlertCategory.JAM -> jamMeters.intValue
            WazeAlertCategory.OTHER -> DISTANCE_USE_DEFAULT
        }
        if (categoryVal != DISTANCE_USE_DEFAULT) {
            return resolveLeadM(categoryVal)
        }
        return defaultLeadM()
    }

    fun defaultLeadM(): Double? = resolveLeadM(defaultMeters.intValue)

    private fun resolveLeadM(value: Int): Double? {
        val effective = if (value == DISTANCE_USE_DEFAULT) defaultMeters.intValue else value
        return if (effective > 0) effective.toDouble() else null
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY_DEFAULT = "alert_dist_default"
    private const val KEY_SPEED_CAM = "alert_dist_speed_cam"
    private const val KEY_FLOCK = "alert_dist_flock"
    private const val KEY_POLICE = "alert_dist_police"
    private const val KEY_POLICE_HIDDEN = "alert_dist_police_hidden"
    private const val KEY_POLICE_CAMERA = "alert_dist_police_camera"
    private const val KEY_ACCIDENT = "alert_dist_accident"
    private const val KEY_ACCIDENT_MINOR = "alert_dist_accident_minor"
    private const val KEY_HAZARD = "alert_dist_hazard"
    private const val KEY_HAZARD_CONSTRUCTION = "alert_dist_hazard_construction"
    private const val KEY_HAZARD_POTHOLE = "alert_dist_hazard_pothole"
    private const val KEY_HAZARD_SHOULDER = "alert_dist_hazard_shoulder"
    private const val KEY_HAZARD_WEATHER = "alert_dist_hazard_weather"
    private const val KEY_CLOSURE = "alert_dist_closure"
    private const val KEY_JAM = "alert_dist_jam"
}
