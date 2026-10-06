package app.vela.core

import app.vela.core.model.SavedPlace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which saved places are a label on a point (reopened as saved, no listing lookup). */
class SavedPlacePointTest {
    private fun sp(id: String, name: String, address: String? = null, bare: Boolean = false) =
        SavedPlace(id, name, 38.5449, -121.7405, address, bare = bare)

    @Test fun aSavedAddressIsAPoint() {
        assertTrue(sp("g:1", "1451 W Covell Blvd", "1451 W Covell Blvd, Davis, CA 95616").isPoint)
    }

    @Test fun aDroppedPinIsAPoint() {
        assertTrue(sp("pin:38.5449,-121.7405", "Dropped pin").isPoint)
    }

    @Test fun aMarkedPlaceIsAPointWhateverItIsCalled() {
        assertTrue(sp("g:1", "Trailhead parking", "1451 W Covell Blvd, Davis, CA 95616", bare = true).isPoint)
    }

    @Test fun aBusinessIsNot() {
        assertFalse(sp("g:2", "Davis Food Co-op", "620 G St, Davis, CA 95616").isPoint)
        assertFalse(sp("g:3", "Davis Food Co-op").isPoint)
    }
}
