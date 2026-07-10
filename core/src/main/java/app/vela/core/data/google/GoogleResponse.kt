package app.vela.core.data.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Google's XHR responses begin with an XSSI guard (`)]}'` then a newline) and
 * then a deeply nested *positional* JSON array - no field names anywhere. This
 * object strips the guard and parses; the extension functions below give
 * null-safe positional access so a missing/reordered index returns null instead
 * of throwing. That defensiveness is the whole game: Google inserts elements
 * mid-array without notice and a hard-coded `[0][1][3]` path silently rots.
 */
object GoogleResponse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val GUARDS = listOf(")]}'\n", ")]}',\n", ")]}'", ")]}", "while(1);", "for(;;);")

    fun strip(body: String): String {
        for (g in GUARDS) if (body.startsWith(g)) return body.substring(g.length).trimStart('\n', '\r')
        return body
    }

    fun parse(body: String): JsonElement = json.parseToJsonElement(strip(body))
}

/** Walk a positional path; any wrong/missing step yields null rather than throwing. */
fun JsonElement?.at(vararg path: Int): JsonElement? {
    var cur: JsonElement? = this
    for (i in path) {
        val arr = cur as? JsonArray ?: return null
        cur = arr.getOrNull(i)
    }
    return cur
}

fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

/** Depth-first search for the first string matching [predicate]. Handy for
 * locating a recognisable value (an encoded polyline, a "23 min" string)
 * without committing to an exact index path. */
fun JsonElement?.findString(predicate: (String) -> Boolean): String? {
    when (this) {
        is JsonArray -> for (e in this) e.findString(predicate)?.let { return it }
        is JsonPrimitive -> contentOrNull?.let { if (predicate(it)) return it }
        else -> {}
    }
    return null
}
