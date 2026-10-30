package meteorbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun distToSegment() {
        assertEquals(1f, distToSegment(Vec(0f, 1f), Vec(-1f, 0f), Vec(1f, 0f)), 1e-5f)
        assertEquals(1f, distToSegment(Vec(2f, 0f), Vec(-1f, 0f), Vec(1f, 0f)), 1e-5f) // past the endpoint
        assertEquals(1f, distToSegment(Vec(0f, 1f), Vec(0f, 0f), Vec(0f, 0f)), 1e-5f) // degenerate segment
    }

    @Test fun boxContains() {
        assertTrue(Box(0f, 0f, 1f, 1f).contains(Vec(.5f, .5f)))
        assertFalse(Box(0f, 0f, 1f, 1f).contains(Vec(1.5f, .5f)))
    }
}
