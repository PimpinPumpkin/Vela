package app.vela.core.data

import app.vela.core.model.Place
import java.math.BigInteger
import java.net.URLDecoder

/**
 * Google Takeout's "Saved" folder: one CSV per list (`Title,Note,URL,Comment`; older exports say
 * `Tags`, and the header is in the account's language: `Titre,Note,URL,Tags,Commentaire`) and
 * no coordinates (issue #761). Only the `URL` column keeps its name, so the columns are found by
 * position around it: the title is the first column that is not the link, the note the one after
 * the title, the comment the last. A row's link is one of three shapes:
 *
 *  - `google.com/maps/place/<Name>/data=...!1s0x<cell>:0x<id>` for a listing, carrying the
 *    feature id and nothing else;
 *  - `google.com/maps/search/<lat>,<lng>` for a dropped pin;
 *  - `maps.google.com/?cid=<decimal>` in older exports: the feature id's second half in decimal.
 *
 * This reads the file and each row's link. Looking a listing up is the caller's: a search by
 * title, whose hit [pick] chooses on [featureKey], the stable half of the feature id.
 */
object TakeoutCsv {
    data class Row(val title: String, val note: String?, val url: String, val comment: String?)

    /** What a row's link says by itself. [featureKey] is the feature id's second half, lowercase
     *  hex without `0x`, the part a `cid` link carries too. */
    data class Link(val name: String?, val lat: Double?, val lng: Double?, val featureId: String?, val featureKey: String?) {
        val hasPoint: Boolean get() = lat != null && lng != null

        /** Roughly where the place is, from the feature id's first half, an S2 cell: within a
         *  hundred meters for a leaf cell. Null for a link without a feature id. */
        fun cellCenter(): app.vela.core.model.LatLng? = MapLinkParser.featureCell(featureId)
    }

    /** Sanity bound for one file. */
    const val MAX_ROWS = 2_000

    /** A Takeout list: a header with a URL column and at least one other, in any language. */
    fun isTakeoutCsv(text: String): Boolean {
        val header = firstRecord(text) ?: return false
        val cols = header.map { it.trim().lowercase() }
        return "url" in cols && cols.size >= 2
    }

    /** Where each field sits in a header, by name where the name is English and by position
     *  otherwise. Null without a URL column. */
    internal fun columns(header: List<String>): IntArray? {
        val cols = header.map { it.trim().lowercase() }
        val url = cols.indexOf("url")
        if (url < 0 || cols.size < 2) return null
        val title = cols.indexOf("title").takeIf { it >= 0 } ?: cols.indices.first { it != url }
        val note = cols.indexOf("note").takeIf { it >= 0 }
            ?: (title + 1).takeIf { it < cols.size && it != url } ?: -1
        // The comment is the last column; an older four-column export's last is "Tags".
        val comment = cols.indexOf("comment").takeIf { it >= 0 }
            ?: (cols.size - 1).takeIf { it != url && it != title && it != note } ?: -1
        return intArrayOf(title, url, note, comment)
    }

    fun parse(text: String): List<Row> {
        val records = records(text)
        if (records.isEmpty()) return emptyList()
        val at = columns(records[0]) ?: return emptyList()
        val title = at[0]; val url = at[1]; val note = at[2]; val comment = at[3]
        return records.drop(1).asSequence()
            .filter { r -> r.any { it.isNotBlank() } }
            .map { r ->
                fun at(i: Int) = r.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
                Row(at(title) ?: "", at(note), at(url) ?: "", at(comment))
            }
            .filter { it.url.isNotBlank() || it.title.isNotBlank() }
            .take(MAX_ROWS)
            .toList()
    }

    /** The row's link read without the network. Null when the link is not a Maps link at all. */
    fun readLink(url: String): Link? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val isMaps = "google." in raw && ("/maps" in raw || "maps.google" in raw)
        if (!isMaps) return null
        val fid = FID.find(raw)?.groupValues?.get(1)
        val cid = queryParam(raw, "cid")?.toBigIntegerOrNull()
        val key = fid?.substringAfter(':')?.removePrefix("0x")?.lowercase()
            ?: cid?.let { it.toString(16).lowercase() }
        val pin = PIN.find(raw)
        val at = AT.find(raw)
        val search = SEARCH.find(raw)?.groupValues?.get(1)?.let { decode(it) }
        val searchCoord = search?.let { COORD.matchEntire(it.trim()) }
        val lat = pin?.groupValues?.get(1)?.toDoubleOrNull() ?: searchCoord?.groupValues?.get(1)?.toDoubleOrNull() ?: at?.groupValues?.get(1)?.toDoubleOrNull()
        val lng = pin?.groupValues?.get(2)?.toDoubleOrNull() ?: searchCoord?.groupValues?.get(2)?.toDoubleOrNull() ?: at?.groupValues?.get(2)?.toDoubleOrNull()
        val name = PLACE.find(raw)?.groupValues?.get(1)?.let { decode(it.replace('+', ' ')) }
            ?: search?.takeIf { searchCoord == null }
            ?: queryParam(raw, "q")?.let { decode(it.replace('+', ' ')) }
        val okLat = lat?.takeIf { it in -90.0..90.0 }
        val okLng = lng?.takeIf { it in -180.0..180.0 }
        return Link(
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            lat = okLat?.takeIf { okLng != null }, lng = okLng?.takeIf { okLat != null },
            featureId = fid, featureKey = key,
        )
    }

    /** The matching half of a place's feature id (`0x<cell>:0x<id>`), or null. */
    fun featureKey(featureId: String?): String? =
        featureId?.substringAfter(':', "")?.removePrefix("0x")?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The hit among [hits] whose feature id carries [key]. */
    fun byKey(hits: List<Place>, key: String?): Place? =
        key?.let { k -> hits.firstOrNull { featureKey(it.featureId) == k } }

    /**
     * The hit a listing row takes. The one carrying [key], from [near] (the search biased where the
     * user is) or [wide] (the unbiased retry for a list made far away); with no key, the one hit in
     * [near] named like [title]. Several named so (a chain's branches) or none give none: a row is
     * never guessed at. A name is never matched in [wide], since the bias is what tells branches apart.
     */
    fun pick(near: List<Place>, wide: List<Place>, key: String?, title: String): Place? {
        // A row that carries a feature id names one place; a same-name hit with another id is a
        // different place, so the name is only trusted when there is no id to check.
        if (key != null) return byKey(near, key) ?: byKey(wide, key)
        return near.filter { it.name.equals(title, ignoreCase = true) }.singleOrNull()
    }

    /** A list's earlier places by [featureKey], for a listing row that can skip its lookup: a
     *  re-import of the same file, or one Android stopped part way (issue #761). */
    fun keyed(previous: List<app.vela.core.model.ListPlace>): Map<String, app.vela.core.model.ListPlace> =
        previous.mapNotNull { p -> featureKey(p.featureId)?.let { it to p } }.toMap()

    /**
     * What an import still running saves: [found] in file order, then every place of the list's
     * [previous] import not found again yet, so an import stopped part way never shrinks the list
     * and holds no place twice. The finished import saves [found] alone.
     */
    fun partial(found: List<app.vela.core.model.ListPlace>, previous: List<app.vela.core.model.ListPlace>): List<app.vela.core.model.ListPlace> {
        val ids = found.mapTo(HashSet()) { it.id }
        val keys = found.mapNotNullTo(HashSet()) { featureKey(it.featureId) }
        return found + previous.filter { p -> p.id !in ids && featureKey(p.featureId).let { it == null || it !in keys } }
    }

    private val FID = Regex("""!1s(0x[0-9a-fA-F]+:0x[0-9a-fA-F]+)""")
    private val PIN = Regex("""!3d(-?\d{1,3}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val AT = Regex("""@(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)""")
    private val COORD = Regex("""(-?\d{1,3}\.\d+),\s*(-?\d{1,3}\.\d+)""")
    private val SEARCH = Regex("""/search/([^/@?]+)""")
    private val PLACE = Regex("""/place/([^/@?]+)""")

    private fun String.toBigIntegerOrNull(): BigInteger? = runCatching { BigInteger(this) }.getOrNull()

    private fun queryParam(raw: String, key: String): String? {
        val q = raw.substringAfter('?', "")
        if (q.isEmpty()) return null
        return q.split('&').firstNotNullOfOrNull { p ->
            val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k.equals(key, ignoreCase = true) && v.isNotEmpty()) v else null
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // --- CSV (RFC 4180: quoted fields, doubled quotes, line breaks inside quotes) ---------------

    private fun firstRecord(text: String): List<String>? = records(text, limit = 1).firstOrNull()

    private fun records(text: String, limit: Int = MAX_ROWS + 1): List<List<String>> {
        val out = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                quoted -> when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
                c == '"' -> quoted = true
                c == ',' -> { fields.add(field.toString()); field.setLength(0) }
                c == '\r' -> {}
                c == '\n' -> {
                    fields.add(field.toString()); field.setLength(0)
                    out.add(fields); fields = ArrayList()
                    if (out.size >= limit) return out
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) { fields.add(field.toString()); out.add(fields) }
        return out
    }
}
