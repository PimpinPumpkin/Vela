package app.vela.core.util

import java.nio.ByteBuffer

/**
 * Pre-API-34 stand-ins for the JDK-13 absolute-bulk ByteBuffer methods. The build's ASM artifact
 * transform (buildSrc/src/main/kotlin/GraphHopperByteBufferPatch.kt) rewrites GraphHopper's
 * `ByteBuffer.get(int, byte[], int, int)` / `put(int, byte[], int, int)` call sites to these -
 * ART only has the real ones from API 34, and MMapDataAccess uses them to read/write graph
 * segments, so offline graph loading died on every older device.
 *
 * duplicate() carries its own position, so like the real absolute methods these never touch the
 * shared buffer's position - byte-identical semantics, thread-safe, API 1.
 *
 * (Contributed by ars18 in the vela-dpad fork.)
 */
object ByteBufferCompat {

    @JvmStatic
    fun get(bb: ByteBuffer, index: Int, dst: ByteArray, offset: Int, length: Int): ByteBuffer {
        val d = bb.duplicate()
        d.position(index)
        d.get(dst, offset, length)
        return bb
    }

    @JvmStatic
    fun put(bb: ByteBuffer, index: Int, src: ByteArray, offset: Int, length: Int): ByteBuffer {
        val d = bb.duplicate()
        d.position(index)
        d.put(src, offset, length)
        return bb
    }
}
