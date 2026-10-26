package app.vela.core.vehicle

import app.vela.core.model.Maneuver

/**
 * The windshield-HUD road-info frame a BYD head unit's glass reads over the SOME/IP gateway, and
 * the IDs it is published on (SPEC 10.9). This builds the wire bytes; the gateway bind and the
 * Binder transaction live in `:app` (`SomeIpHudTransport`). The field numbers and the frame shape
 * are the documented HUD_ROAD_INFO contract (HudRoadInfoNotifyStruct), expressed here as a small
 * protobuf writer so the frame is byte-exact and testable without a device.
 *
 * The frame is the minimal proven one: an inner message with the counter, a layout byte, an empty
 * lane field, the maneuver picture, the distance, the next road, the guiding status, the ETA and
 * the maneuver id, wrapped as outer field 1. The maneuver id uses [SomeIpHudArrows]; the FCE
 * cluster glyphs ([FceIcons]) are a different vocabulary and are never used here.
 */
object SomeIpHudFrame {
    /** startSomeIpService argument: service 0x010A, instance 0x0001. */
    const val SERVICE_ID = 0x000B010A00010000L
    /** fireEvent topic: HUD_ROAD_INFO event 0x8001. */
    const val TOPIC_ROAD_INFO = 0x0004010A00018001L

    /** A guiding frame: status 2, the arrow picture in field 8, distance, road and ETA. [png] is
     *  the maneuver arrow as PNG bytes (empty when none); distance is floored at 11 m because 0..10
     *  shows a "now" glyph on some clusters; the road name is capped at 200 chars. */
    fun guidance(f28: Int, distanceMeters: Int, road: String, etaHhMm: String, png: ByteArray?, counter: Int = 2): ByteArray {
        val inner = ArrayList<Byte>(256)
        putVarintField(inner, 2, counter.toLong())        // Counter
        putVarintField(inner, 6, 1)                       // CurrentRoadLevel reused as layout: 6 = picture in 7/8
        putLenField(inner, 7, ByteArray(0))               // PermissibleDirection (lane picture): empty
        putLenField(inner, 8, png ?: ByteArray(0))        // RecommendedDrivingDirections picture (the arrow PNG)
        putVarintField(inner, 9, distanceMeters.coerceAtLeast(DIST_FLOOR_M).toLong())
        putLenField(inner, 10, road.take(ROAD_MAX).toByteArray(Charsets.UTF_8))
        putVarintField(inner, 16, 2)                      // NavigatingStatus: guiding
        putLenField(inner, 26, etaHhMm.toByteArray(Charsets.UTF_8)) // ETAInfoTime "HH:mm"
        putVarintField(inner, 28, f28.toLong())           // RecommendedDrivingDirectionsId
        return wrap(inner)
    }

    /** The clear frame: status idle, layout 255. The glass blanks the arrow and the readouts. */
    fun clear(): ByteArray {
        val inner = ArrayList<Byte>(8)
        putVarintField(inner, 2, 2)
        putVarintField(inner, 6, 255)                     // layout: clear
        putVarintField(inner, 16, 1)                      // NavigatingStatus: idle
        return wrap(inner)
    }

    /** The maneuver id for field 28, from the shared table (native-arrow vocabulary, which is what
     *  the proven picture-in-8 senders use; single arrows match the stock table either way). */
    fun maneuverId(m: Maneuver?): Int = SomeIpHudArrows.of(m, SomeIpHudArrows.Table.NATIVE_ARROW)

    const val DIST_FLOOR_M = 11
    const val ROAD_MAX = 200

    // The inner message rides as outer field 1 (length-delimited): 0x0A, varint(len), bytes.
    private fun wrap(inner: List<Byte>): ByteArray {
        val out = ArrayList<Byte>(inner.size + 4)
        putTag(out, 1, WIRE_LEN)
        putVarint(out, inner.size.toLong())
        out.addAll(inner)
        return out.toByteArray()
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LEN = 2

    private fun putTag(out: MutableList<Byte>, field: Int, wire: Int) = putVarint(out, ((field shl 3) or wire).toLong())

    private fun putVarintField(out: MutableList<Byte>, field: Int, value: Long) {
        putTag(out, field, WIRE_VARINT)
        putVarint(out, value)
    }

    private fun putLenField(out: MutableList<Byte>, field: Int, bytes: ByteArray) {
        putTag(out, field, WIRE_LEN)
        putVarint(out, bytes.size.toLong())
        for (b in bytes) out.add(b)
    }

    // Unsigned LEB128, as protobuf writes a varint. Values here are small and non-negative.
    private fun putVarint(out: MutableList<Byte>, value: Long) {
        var x = value
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) out.add((b or 0x80).toByte()) else { out.add(b.toByte()); return }
        }
    }
}
