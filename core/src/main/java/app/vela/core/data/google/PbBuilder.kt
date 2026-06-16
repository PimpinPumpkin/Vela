package app.vela.core.data.google

/**
 * Builds Google Maps' `pb` URL parameter: protobuf serialised into a string of
 * `!`-delimited fields, each `!{fieldNumber}{typeCode}{value}`.
 *
 * Type codes (stable): `m` = message/group, where the value is the number of
 * immediate child fields that follow and belong to it; `d` = double, `f` =
 * float, `i` = int, `u` = uint, `e` = enum, `b` = bool (0/1), `s` = string.
 *
 * The GRAMMAR implemented here is correct and stable. The FIELD TREE for any
 * given endpoint — which field number is "destination latitude", which enum
 * selects driving vs walking, where the traffic flag goes — is NOT stable and
 * must be read off a live capture. Build those trees in [GoogleMapsDataSource]
 * and keep the `CALIBRATE:` markers honest.
 *
 * Example — a lat/lng message `!1m2!1d-122.4!2d37.7`:
 * ```
 * PbBuilder().message(1, 2) { double(1, -122.4); double(2, 37.7) }.toString()
 * ```
 */
class PbBuilder {
    private val sb = StringBuilder()

    private fun field(number: Int, type: Char, value: String) = apply {
        sb.append('!').append(number).append(type).append(value)
    }

    /**
     * Open message [number] declaring it has [childCount] immediate children,
     * then emit exactly that many fields inside [block]. The count is the
     * caller's responsibility — pb has no closing delimiter, the child count is
     * how the decoder knows where the message ends.
     */
    fun message(number: Int, childCount: Int, block: PbBuilder.() -> Unit) =
        field(number, 'm', childCount.toString()).apply(block)

    fun double(number: Int, v: Double) = field(number, 'd', v.toString())
    fun float(number: Int, v: Float) = field(number, 'f', v.toString())
    fun int(number: Int, v: Int) = field(number, 'i', v.toString())
    fun uint(number: Int, v: Long) = field(number, 'u', v.toString())
    fun enum(number: Int, v: Int) = field(number, 'e', v.toString())
    fun bool(number: Int, v: Boolean) = field(number, 'b', if (v) "1" else "0")

    /** Caller is responsible for URL-encoding string contents if needed. */
    fun string(number: Int, v: String) = field(number, 's', v)

    /** Splice in a pre-built fragment verbatim. */
    fun raw(fragment: String) = apply { sb.append(fragment) }

    override fun toString(): String = sb.toString()
}
