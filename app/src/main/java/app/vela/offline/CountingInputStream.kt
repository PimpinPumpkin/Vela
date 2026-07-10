package app.vela.offline

import java.io.InputStream

/** Counts bytes pulled from the network so download progress can be reported. Shared by the
 * offline asset stores (routing graphs, place packs) instead of each keeping its own copy. */
internal class CountingInputStream(
    private val wrapped: InputStream,
    private val onRead: (Long) -> Unit,
) : InputStream() {
    private var count = 0L
    override fun read(): Int = wrapped.read().also { if (it >= 0) onRead(++count) }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        wrapped.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    override fun available(): Int = wrapped.available()
    override fun close() = wrapped.close()
}
