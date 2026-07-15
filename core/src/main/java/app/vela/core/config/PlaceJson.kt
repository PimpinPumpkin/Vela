package app.vela.core.config

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * The flat JSON contract between the app and a remote `transforms.js`. The JS is
 * handed (and returns) an array of these simple objects; we map them to [Place].
 * Only the core scalar fields are carried - the ones a Google reshape would move
 * and a hot-fix would need to re-extract. Array fields (photos/hours/about) default
 * empty; the compiled path fills those on a normal (non-overridden) response.
 */
object PlaceJson {
    fun encode(places: List<Place>): String = buildJsonArray {
        places.forEach { p ->
            addJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("lat", p.location.lat)
                put("lng", p.location.lng)
                p.category?.let { put("category", it) }
                p.address?.let { put("address", it) }
                p.rating?.let { put("rating", it) }
                p.reviewCount?.let { put("reviewCount", it) }
                p.priceText?.let { put("priceText", it) }
                p.phone?.let { put("phone", it) }
                p.website?.let { put("website", it) }
                p.openNow?.let { put("openNow", it) }
                p.statusText?.let { put("statusText", it) }
                p.featureId?.let { put("featureId", it) }
                p.placeId?.let { put("placeId", it) }
                p.distanceMeters?.let { put("distanceMeters", it) }
            }
        }
    }.toString()

    fun decode(jsonArray: String): List<Place>? = runCatching {
        Json.parseToJsonElement(jsonArray).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
            val name = s("name") ?: return@mapNotNull null
            val lat = d("lat") ?: return@mapNotNull null
            val lng = d("lng") ?: return@mapNotNull null
            Place(
                id = s("id") ?: "js:${name.hashCode()}",
                name = name,
                location = LatLng(lat, lng),
                category = s("category"),
                address = s("address"),
                rating = d("rating"),
                reviewCount = i("reviewCount"),
                priceText = s("priceText"),
                phone = s("phone"),
                website = s("website"),
                openNow = b("openNow"),
                statusText = s("statusText"),
                featureId = s("featureId"),
                placeId = s("placeId"),
                distanceMeters = d("distanceMeters"),
            )
        }
    }.getOrNull()
}
