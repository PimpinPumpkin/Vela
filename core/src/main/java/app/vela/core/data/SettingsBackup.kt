package app.vela.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Vela's settings as one file (issue #951), so a reinstall or a new phone starts with the same
 * setup. Two parts:
 *
 * - Settings, always: the preference files in [SETTINGS_FILES] (choices, the welcome and tips
 *   already seen, each voice's loudness), and the custom interface font when there is one.
 * - Places, when the user includes them: Home and Work, saved routes, search history (the files
 *   in [PLACE_FILES]), and the keys of `vela_settings` in [PLACE_KEYS] (the parking spot, where
 *   the map opens, the home-screen trip shortcuts).
 *
 * A key [skip] matches is never written or read: machine state that is not a choice and would be
 * wrong on another phone (timestamps, crash and health counters, the simulators, the Google
 * session, the cached sun position, the location and notification permission prompts).
 *
 * Import makes what the file covers match it: the keys in the file are written, and the keys
 * [resets] lists (left at their default when the file was made) are removed so they read their
 * default again. The same rules run on import, so a hand-edited file cannot set a skipped key,
 * and a file whose places were left out neither sets nor clears yours.
 */
object SettingsBackup {
    const val FORMAT = "vela-settings"
    const val VERSION = 1
    const val MIME = "application/json"

    /** Preference files of settings. */
    val SETTINGS_FILES = listOf("vela_settings", "vela_units", "vela_onboarding", "vela_notices", "vela_voice_level")

    /** Preference files that hold places. */
    val PLACE_FILES = listOf("vela_shortcuts", "vela_saved_routes", "vela_recents", "vela_recent_places")

    /** Keys of `vela_settings` that hold a place. */
    val PLACE_KEYS = setOf("parking_lat", "parking_lng", "parking_at", "start_view_loc", "trip_names", "trip_lats", "trip_lngs")

    /** The largest font the file carries; the font picker refuses anything larger (`AppFont`). */
    const val MAX_FONT_BYTES = 12 * 1024 * 1024

    private val SKIP = setOf(
        // timestamps and the update checker
        "first_ms", "last_update_check_ms", "texture_render_auto_ms", "region_autopatch_at",
        "update_dismissed_code", "last_seen_version", "whats_new_after_update", "open_links_build",
        // crash and health counters
        "map_init_crashes", "map_init_inflight",
        // the location and driving simulators
        "sim_location", "demo_drive",
        // the Google session, which must not be the same on two phones
        "google_session_started", "browser_viewport",
        // the sun position cached from the last fix, worked out again from the next one
        "theme_sun_lat", "theme_sun_lng", "theme_sun_at",
        // permission prompts: the new phone has not granted them
        "location_prompt_done", "notif_prompt_done",
    )
    private val SKIP_PREFIX = listOf("asr_load_strikes_", "asr_model_bad_")

    /** Whether [key] is machine state that never goes in the file. */
    fun skip(key: String): Boolean = key.isBlank() || key in SKIP || SKIP_PREFIX.any { key.startsWith(it) }

    /** Whether [key] of preference file [file] belongs in a file made with [places] on or off. */
    fun keep(file: String, key: String, places: Boolean): Boolean = when {
        skip(key) -> false
        file in PLACE_FILES -> places
        file == "vela_settings" && key in PLACE_KEYS -> places
        else -> file in SETTINGS_FILES
    }

    /** A custom interface font: its file's bytes and the name it shows under. */
    class Font(val name: String, val bytes: ByteArray)

    /**
     * [prefs] (file name to its entries, as `SharedPreferences.getAll` gives them) and [font] as
     * the file's text. A value of a type preferences cannot hold is left out.
     */
    fun encode(prefs: Map<String, Map<String, Any?>>, font: Font?, places: Boolean, appVersion: String): String {
        val out = LinkedHashMap<String, JsonElement>()
        out["format"] = JsonPrimitive(FORMAT)
        out["version"] = JsonPrimitive(VERSION)
        out["app"] = JsonPrimitive(appVersion)
        out["places"] = JsonPrimitive(places)
        val body = LinkedHashMap<String, JsonElement>()
        for (file in SETTINGS_FILES + if (places) PLACE_FILES else emptyList()) {
            val entries = prefs[file] ?: continue
            val kept = LinkedHashMap<String, JsonElement>()
            for ((k, v) in entries.entries.sortedBy { it.key }) {
                if (!keep(file, k, places)) continue
                typed(v)?.let { kept[k] = it }
            }
            if (kept.isNotEmpty()) body[file] = JsonObject(kept)
        }
        out["files"] = JsonObject(body)
        if (font != null && font.bytes.isNotEmpty() && font.bytes.size <= MAX_FONT_BYTES) {
            out["font"] = JsonObject(mapOf(
                "name" to JsonPrimitive(font.name),
                "data" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(font.bytes)),
            ))
        }
        return JSON.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    /** What [decode] read. */
    sealed interface Decoded {
        /** [prefs]: file name to key to value (Boolean, Int, Long, Float, String or Set of String). */
        class Ok(val prefs: Map<String, Map<String, Any>>, val font: Font?, val places: Boolean) : Decoded {
            val count: Int get() = prefs.values.sumOf { it.size } + (if (font != null) 1 else 0)
        }
        /** JSON, but not a settings file (a places or lists export, say). */
        data object WrongFormat : Decoded
        /** A settings file from a newer Vela that this one cannot read. */
        data object TooNew : Decoded
        data object Unreadable : Decoded
    }

    fun decode(text: String): Decoded {
        val root = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Decoded.Unreadable
        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return Decoded.WrongFormat
        val version = root["version"]?.jsonPrimitive?.longOrNull ?: return Decoded.Unreadable
        if (version > VERSION) return Decoded.TooNew
        val places = root["places"]?.jsonPrimitive?.booleanOrNull ?: false
        val body = runCatching { root["files"]?.jsonObject }.getOrNull() ?: return Decoded.Unreadable
        val prefs = LinkedHashMap<String, Map<String, Any>>()
        for (file in SETTINGS_FILES + PLACE_FILES) {
            val entries = runCatching { body[file]?.jsonObject }.getOrNull() ?: continue
            val values = LinkedHashMap<String, Any>()
            for ((k, el) in entries) {
                if (!keep(file, k, places)) continue
                untyped(el)?.let { values[k] = it }
            }
            if (values.isNotEmpty()) prefs[file] = values
        }
        val font = runCatching {
            val f = root["font"]?.jsonObject ?: return@runCatching null
            val name = f["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val data = f["data"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            // Base64 is 4 characters for 3 bytes: refuse an oversized font before decoding it.
            if (data.length.toLong() * 3 / 4 > MAX_FONT_BYTES) return@runCatching null
            java.util.Base64.getDecoder().decode(data).takeIf { it.isNotEmpty() }?.let { Font(name, it) }
        }.getOrNull()
        return Decoded.Ok(prefs, font, places)
    }

    /**
     * The keys to remove on import so the phone matches [got]: in each file the import covers,
     * every key [keep] allows that the file does not hold. A setting the user never changed is
     * not stored, so the file leaves it out; without this, a setting changed after the export
     * would keep the new value instead of going back to the default the file was made with.
     * [current] is file name to the keys stored on this phone. Skipped keys are never removed,
     * and with no places in the file, places are left as they are.
     */
    fun resets(current: Map<String, Set<String>>, got: Decoded.Ok): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for (file in SETTINGS_FILES + if (got.places) PLACE_FILES else emptyList()) {
            val have = current[file] ?: continue
            val inFile = got.prefs[file]?.keys.orEmpty()
            val drop = have.filter { keep(file, it, got.places) && it !in inFile }.toSet()
            if (drop.isNotEmpty()) out[file] = drop
        }
        return out
    }

    // One value as {"t": type, "v": value}: preferences tell Int from Long and Float from Double,
    // and plain JSON numbers would lose that.
    private fun typed(v: Any?): JsonObject? = when (v) {
        is Boolean -> pair("b", JsonPrimitive(v))
        is Int -> pair("i", JsonPrimitive(v))
        is Long -> pair("l", JsonPrimitive(v))
        is Float -> pair("f", JsonPrimitive(v))
        is String -> pair("s", JsonPrimitive(v))
        is Set<*> -> if (v.all { it is String }) pair("ss", JsonArray(v.map { JsonPrimitive(it as String) }.sortedBy { it.content })) else null
        else -> null
    }

    private fun pair(t: String, v: JsonElement) = JsonObject(mapOf("t" to JsonPrimitive(t), "v" to v))

    private fun untyped(el: JsonElement): Any? = runCatching {
        val o = el.jsonObject
        val v = o["v"] ?: return null
        when (o["t"]?.jsonPrimitive?.contentOrNull) {
            "b" -> v.jsonPrimitive.booleanOrNull
            "i" -> v.jsonPrimitive.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            "l" -> v.jsonPrimitive.longOrNull
            "f" -> v.jsonPrimitive.doubleOrNull?.toFloat()
            "s" -> v.jsonPrimitive.takeIf { it.isString }?.content
            "ss" -> v.jsonArray.mapNotNull { e -> e.jsonPrimitive.takeIf { it.isString }?.content }.toSet()
            else -> null
        }
    }.getOrNull()

    private val JSON = Json { prettyPrint = true }
}
