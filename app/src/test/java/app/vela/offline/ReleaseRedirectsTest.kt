package app.vela.offline

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRedirectsTest {
    private val now = java.time.Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

    @Test fun keptUntilAMinuteBeforeTheSignedAddressEnds() {
        val u = "https://release-assets.githubusercontent.com/a/b?sp=r&se=2026-10-05T12%3A10%3A00Z&sig=x".toHttpUrl()
        assertEquals(now + 9 * 60_000L, ReleaseRedirects.expiry(u, now))
    }

    @Test fun neverKeptLongerThanHalfAnHour() {
        val u = "https://release-assets.githubusercontent.com/a/b?se=2026-10-05T18%3A00%3A00Z".toHttpUrl()
        assertEquals(now + 30 * 60_000L, ReleaseRedirects.expiry(u, now))
    }

    @Test fun anAddressAlreadyEndingIsNotKept() {
        val u = "https://release-assets.githubusercontent.com/a/b?se=2026-10-05T12%3A00%3A30Z".toHttpUrl()
        assertTrue(ReleaseRedirects.expiry(u, now) <= now)
    }

    @Test fun theOlderSigningFormAndAnUnknownOne() {
        assertEquals(now + 240_000L, ReleaseRedirects.expiry("https://objects.githubusercontent.com/a?X-Amz-Expires=300".toHttpUrl(), now))
        assertEquals(now + 180_000L, ReleaseRedirects.expiry("https://example.org/a".toHttpUrl(), now))
    }
}
