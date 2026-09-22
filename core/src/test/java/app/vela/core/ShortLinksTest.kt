package app.vela.core

import app.vela.core.data.MapLinkParser
import app.vela.core.data.ShortLinks
import okhttp3.OkHttpClient
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortLinksTest {
    @Test fun `shortener hosts and lists`() {
        assertTrue(ShortLinks.isShortener("https://maps.app.goo.gl/AbC123"))
        assertTrue(ShortLinks.isShortener("goo.gl/maps/AbC123"))
        assertFalse(ShortLinks.isShortener("https://www.google.com/maps/place/Davis+Food+Co-op"))
        assertTrue(ShortLinks.isList("https://www.google.com/maps/placelists/list/AbC?g_ep=x"))
        assertFalse(ShortLinks.isList("https://www.google.com/maps/place/Davis+Food+Co-op/@38.5,-121.7,17z"))
    }

    @Test fun `a consent interstitial is unwrapped, not requested`() {
        val target = "https://www.google.com/maps/place/Davis+Food+Co-op/@38.5486,-121.7431,17z"
        val consent = "https://consent.google.com/m?continue=" + java.net.URLEncoder.encode(target, "UTF-8") + "&gl=DE"
        assertEquals(target, ShortLinks.nextHop(consent))
        assertEquals(target, ShortLinks.nextHop(target))
    }

    @Test fun `a place link prefers its own pin over the map center`() {
        val link = MapLinkParser.parse(
            "https://www.google.com/maps/place/Davis+Food+Co-op/@38.5500,-121.7500,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d38.5486!4d-121.7431",
        )!!
        assertEquals("Davis Food Co-op", link.query)
        assertEquals(38.5486, link.lat!!, 1e-9)
        assertEquals(-121.7431, link.lng!!, 1e-9)
    }

    /** A one-thread local server answering every request with [status] (+ Location), counting
     *  requests and recording whether any carried a cookie. Plain sockets: the Android unit-test
     *  classpath has no JDK HTTP server. */
    private fun serve(status: Int, location: String?, block: (url: String, hits: () -> Int, sawCookie: () -> Boolean) -> Unit) {
        var hits = 0
        var cookie = false
        val server = ServerSocket(0)
        val t = thread(isDaemon = true) {
            runCatching {
                while (true) {
                    server.accept().use { sock ->
                        val reader = sock.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Cookie:", ignoreCase = true)) cookie = true
                        }
                        hits++
                        val head = buildString {
                            append("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n")
                            location?.let { append("Location: $it\r\n") }
                            append("\r\n")
                        }
                        sock.getOutputStream().apply { write(head.toByteArray()); flush() }
                    }
                }
            }
        }
        try { block("http://127.0.0.1:${server.localPort}/AbC123", { hits }, { cookie }) } finally { server.close(); t.join(1000) }
    }

    @Test fun `resolve reads one redirect and stops before the target`() {
        val target = "https://www.google.com/maps/place/Davis+Food+Co-op/@38.5486,-121.7431,17z"
        serve(302, target) { url, hits, sawCookie ->
            assertEquals(target, ShortLinks.resolve(OkHttpClient(), url, "test"))
            assertEquals(1, hits())
            assertFalse(sawCookie())
        }
    }

    @Test fun `no redirect means no answer`() {
        serve(200, null) { url, _, _ -> assertNull(ShortLinks.resolve(OkHttpClient(), url, "test")) }
    }
}
