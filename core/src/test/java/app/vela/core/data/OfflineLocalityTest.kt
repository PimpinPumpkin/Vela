package app.vela.core.data

import app.vela.core.data.OfflineAddressStore.Companion.localityOf
import app.vela.core.data.OfflineAddressStore.Companion.needsLocality
import app.vela.core.data.OfflineAddressStore.Companion.pickLocality
import app.vela.core.data.OfflineAddressStore.Companion.withLocality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLocalityTest {
    @Test fun `the locality is everything after the street line`() {
        assertEquals("Davis, CA 95616", localityOf("620 G St, Davis, CA 95616"))
        assertNull(localityOf("620 G St"))
        assertNull(localityOf(null))
    }

    @Test fun `a street line alone or with a bare town needs a locality`() {
        assertTrue(needsLocality("620 G St"))
        assertTrue(needsLocality("620 G St, Davis"))
        assertFalse(needsLocality("620 G St, Davis, CA 95616"))
        assertFalse(needsLocality("620 G St, Davis 95616"))
    }

    @Test fun `the vote prefers a postcode, then the most common, then the nearest`() {
        val seen = listOf(
            10.0 to "Davis",
            50.0 to "Davis, CA 95616",
            60.0 to "Davis, CA 95618",
            70.0 to "Davis, CA 95616",
        )
        assertEquals("Davis, CA 95616", pickLocality(seen))
        assertEquals("Davis, CA 95618", pickLocality(listOf(20.0 to "Davis, CA 95618", 30.0 to "Davis, CA 95616")))
        assertEquals("Davis", pickLocality(listOf(5.0 to "Davis")))
        assertNull(pickLocality(emptyList()))
    }

    @Test fun `joining never swaps one town for another`() {
        assertEquals("620 G St, Davis, CA 95616", withLocality("620 G St", "Davis, CA 95616"))
        assertEquals("620 G St, Davis, CA 95616", withLocality("620 G St, Davis", "Davis, CA 95616"))
        assertEquals("620 G St, Woodland", withLocality("620 G St, Woodland", "Davis, CA 95616"))
        assertEquals("620 G St", withLocality("620 G St", null))
    }
}
