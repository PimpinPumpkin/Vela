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

    private val INTERNAL_KEYS = Regex(
        "^(last_fix_|session_|onboarding_|whatsnew_seen|parking_|theme_sun_|sim_location|" +
            "browser_viewport|google_session_|google_limited_session|map_init_|texture_render_auto_ms|" +
            "asr_load_strikes_|asr_model_bad_|last_update_check_ms|update_dismissed_code|" +
            "region_autopatch_at|saved_pin_tip_done|open_links_build|offline_storage|ui_font_name)",
    )

    /** Write all user settings from `vela_settings` to a typed JSON file and return a share intent. */
    fun exportIntent(context: Context): android.content.Intent? = runCatching {
        val all = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).all
        val entries = org.json.JSONObject()
        for ((k, v) in all.entries.sortedBy { it.key }) {
            if (INTERNAL_KEYS.containsMatchIn(k) || v == null) continue
            val item = org.json.JSONObject()
            when (v) {
                is Boolean -> { item.put("type", "bool"); item.put("value", v) }
                is Int -> { item.put("type", "int"); item.put("value", v) }
                is Long -> { item.put("type", "long"); item.put("value", v) }
                is Float -> { item.put("type", "float"); item.put("value", v.toDouble()) }
                is String -> { item.put("type", "string"); item.put("value", v) }
                is Set<*> -> {
                    item.put("type", "set")
                    val arr = org.json.JSONArray()
                    v.filterIsInstance<String>().sorted().forEach { arr.put(it) }
                    item.put("value", arr)
                }
                else -> continue
            }
            entries.put(k, item)
        }
        val root = org.json.JSONObject().apply {
            put("app", "Vela")
            put("kind", "settings")
            put("schema", 1)
            put("exportedAt", System.currentTimeMillis())
            put("entries", entries)
        }
        val dir = java.io.File(context.cacheDir, "export").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(dir, "vela-settings-$stamp.json")
        file.writeText(root.toString(2))
        shareFileIntent(
            context, file,
            mime = "application/json",
            subject = context.getString(app.vela.R.string.settings_backup_title),
            text = context.getString(app.vela.R.string.settings_backup_title),
            title = context.getString(app.vela.R.string.settings_export),
        )
    }.getOrNull()

    /** Import a JSON settings file produced by [exportIntent] into `vela_settings`.
     *  Returns the number of restored keys, or -1 if invalid. Call [reloadHolders] on the
     *  main thread afterwards to refresh all in-memory setting holders. */
    fun importFromUri(context: Context, uri: android.net.Uri): Int = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return -1
        val root = org.json.JSONObject(text)
        if (root.optString("kind") != "settings") return -1
        val entries = root.optJSONObject("entries") ?: return -1
        val ed = context.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).edit()
        var count = 0
        val keys = entries.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (INTERNAL_KEYS.containsMatchIn(k)) continue
            val item = entries.optJSONObject(k) ?: continue
            when (item.optString("type")) {
                "bool" -> { ed.putBoolean(k, item.getBoolean("value")); count++ }
                "int" -> { ed.putInt(k, item.getInt("value")); count++ }
                "long" -> { ed.putLong(k, item.getLong("value")); count++ }
                "float" -> { ed.putFloat(k, item.getDouble("value").toFloat()); count++ }
                "string" -> { ed.putString(k, item.getString("value")); count++ }
                "set" -> {
                    val arr = item.optJSONArray("value") ?: continue
                    val set = LinkedHashSet<String>(arr.length())
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    ed.putStringSet(k, set)
                    count++
                }
            }
        }
        ed.commit()
        count
    }.getOrDefault(-1)

    fun reloadHolders(context: Context) {
        val ctx = context.applicationContext
        app.vela.ui.theme.AppTheme.init(ctx)
        app.vela.ui.theme.DynamicColor.init(ctx)
        app.vela.ui.AppLocale.init(ctx)
        app.vela.ui.Traffic.init(ctx)
        app.vela.ui.TransitLayer.init(ctx)
        app.vela.ui.SatelliteLayer.init(ctx)
        app.vela.ui.LayersButton.init(ctx)
        app.vela.ui.MapTilt.init(ctx)
        app.vela.ui.NorthLock.init(ctx)
        app.vela.ui.ParkingButton.init(ctx)
        app.vela.ui.LinkAction.init(ctx)
        app.vela.ui.StartView.init(ctx)
        app.vela.ui.NavEndConfirm.init(ctx)
        app.vela.ui.SpeedDisplay.init(ctx)
        app.vela.ui.Topography.init(ctx)
        app.vela.ui.Flock.init(ctx)
        app.vela.ui.SpeedCams.init(ctx)
        app.vela.ui.SpeedCamWarn.init(ctx)
        app.vela.ui.SpeedingAlert.init(ctx)
        app.vela.ui.BridgeAlert.init(ctx)
        app.vela.ui.BikeSafe.init(ctx)
        ctx.getSharedPreferences("vela_settings", Context.MODE_PRIVATE).let { p ->
            app.vela.core.data.RoutingPrefs.avoidTolls = p.getBoolean("avoid_tolls", false)
            app.vela.core.data.RoutingPrefs.avoidHighways = p.getBoolean("avoid_highways", false)
            app.vela.core.data.RoutingPrefs.avoidFerries = p.getBoolean("avoid_ferries", false)
        }
        app.vela.ui.FlockRouteAlert.init(ctx)
        app.vela.ui.FlockDetour.init(ctx)
        app.vela.ui.FlockNavAlert.init(ctx)
        app.vela.ui.SimLocation.init(ctx)
        app.vela.ui.UiScale.init(ctx)
        app.vela.ui.PageTransitions.init(ctx)
        app.vela.ui.AppFont.init(ctx)
        app.vela.ui.MapColors.init(ctx)
        app.vela.ui.MapButtonShape.init(ctx)
        app.vela.ui.TurnArrows.init(ctx)
        app.vela.ui.RouteBarPref.init(ctx)
        app.vela.ui.CarSpeed.init(ctx)
        app.vela.ui.LiveReviews.init(ctx)
        app.vela.ui.ShowReviews.init(ctx)
        app.vela.ui.LoadPhotos.init(ctx)
        app.vela.ui.OfflinePlaces.init(ctx)
        app.vela.ui.ReviewsOnTap.init(ctx)
        app.vela.ui.PhotosOnTap.init(ctx)
        app.vela.ui.DetailsRetry.init(ctx)
        app.vela.ui.OtherLocationsAuto.init(ctx)
        app.vela.ui.RouteTrafficOnTap.init(ctx)
        app.vela.core.data.RoutingPrefs.googleTraffic = !app.vela.ui.RouteTrafficOnTap.on.value
        app.vela.ui.HideAdult.init(ctx)
        app.vela.ui.HideExternalLinks.init(ctx)
        app.vela.ui.GoogleFree.init(ctx)
        app.vela.ui.Buildings3d.init(ctx)
        app.vela.ui.RouteTrail.init(ctx)
        app.vela.ui.NavSchedule.init(ctx)
        app.vela.ui.NavLineWidth.init(ctx)
        app.vela.ui.TurnDeclutterPref.init(ctx)
        app.vela.ui.CategoryChipsPref.init(ctx)
        app.vela.ui.QuickChipsPref.init(ctx)
        app.vela.ui.RoadLabel.init(ctx)
        app.vela.ui.NavNorthUp.init(ctx)
        app.vela.ui.ZoomToTurn.init(ctx)
        app.vela.ui.PuckStyle.init(ctx)
        app.vela.ui.HouseNumbers.init(ctx)
        app.vela.ui.MapNames.init(ctx)
        app.vela.ui.PreferButtons.init(ctx)
        app.vela.ui.ZoomKeys.init(ctx)
        app.vela.ui.PipTurnCard.init(ctx)
        app.vela.ui.PauseInBar.init(ctx)
        app.vela.ui.FasterRouteAuto.init(ctx)
        app.vela.ui.RegionUpdates.init(ctx)
        app.vela.ui.BuildingOverlay.init(ctx)
        app.vela.ui.BuildingDebug.init(ctx)
        app.vela.ui.MapPoiPrefs.init(ctx)
        app.vela.ui.SpokenRoadNames.init(ctx)
        app.vela.ui.RoadNumbers.init(ctx)
        app.vela.ui.SpokenDetail.init(ctx)
        app.vela.ui.RoutePicker.init(ctx)
        app.vela.ui.VoiceSearch.init(ctx)
        app.vela.ui.ContactsSearch.init(ctx)
        NavTrace.init(ctx)
        app.vela.ui.MapLook.init(ctx)
        app.vela.ui.SpeechPreload.init(ctx)
        app.vela.ui.FullPlaceLoad.init(ctx)
        app.vela.ui.Clock24.refresh(ctx)
        app.vela.ui.WhatsNew.init(ctx)
    }
}
