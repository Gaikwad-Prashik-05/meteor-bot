package meteorbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private val aspect = 2.2f

    private fun frame(t: Long, meteors: List<Vec> = emptyList(), bombs: List<Vec> = emptyList()) =
        Frame(t, aspect, ScreenState.GAME, meteors.map { Blob(it, 0.04f) }, bombs.map { Blob(it, 0.09f) }, 0f)

    @Test fun keepsIdAndEstimatesVelocity() {
        val tr = Tracker()
        val ids = (0 until 5).map { i -> tr.update(frame(i * 33L, listOf(Vec(0.5f, 0.3f + 0.01f * i)))).single().id }
        assertEquals(1, ids.toSet().size)
        val t = tr.update(frame(5 * 33L, listOf(Vec(0.5f, 0.35f)))).single()
        assertEquals(0.01f / 33f, t.vel.y, 0.2f * 0.01f / 33f)
        assertEquals(6, t.seen)
    }

    @Test fun youngTrackVelocityIgnoresJitter() {
        // True fall 0.0009/ms with +-0.01 detection jitter: frame-to-frame speed would be off by ~70%.
        val tr = Tracker()
        val jitter = listOf(0f, .01f, -.01f, .01f)
        val t = (0 until 4).map { i -> tr.update(frame(i * 33L, listOf(Vec(.5f, .3f + .0009f * 33 * i + jitter[i])))).single() }.last()
        assertEquals(0.0009f, t.vel.y, 0.00015f)
    }

    @Test fun separateKindsNeverMatch() {
        val tr = Tracker()
        tr.update(frame(0, listOf(Vec(.5f, .5f))))
        val tracks = tr.update(frame(33, bombs = listOf(Vec(.5f, .5f))))
        val bomb = tracks.single { it.kind == Kind.BOMB }
        val meteor = tracks.single { it.kind == Kind.METEOR }
        assertNotEquals(bomb.id, meteor.id)
    }

    @Test fun dropsAfter150ms() {
        val tr = Tracker()
        tr.update(frame(0, listOf(Vec(.5f, .5f))))
        assertEquals(1, tr.update(frame(100)).size)
        assertEquals(0, tr.update(frame(200)).size)
    }

    @Test fun matchRadius() {
        val tr = Tracker()
        tr.update(frame(0, listOf(Vec(.5f, .5f))))
        val a = tr.update(frame(33, listOf(Vec(.5f, .52f)))).single().id
        val b = tr.update(frame(66, listOf(Vec(.5f, .69f)))).first { it.lastSeenMs == 66L }.id
        assertNotEquals(a, b)
    }

    @Test fun firstMatchAllowsFastFall() {
        // ~0.2 widths in one frame (fast meteor or a dropped frame) still keeps the track
        val tr = Tracker()
        val a = tr.update(frame(0, listOf(Vec(.5f, .5f)))).single().id
        assertEquals(a, tr.update(frame(66, listOf(Vec(.52f, .7f)))).single().id)
    }

    @Test fun firstMatchNeverJumpsUp() {
        val tr = Tracker()
        val a = tr.update(frame(0, listOf(Vec(.5f, .5f)))).single().id
        assertNotEquals(a, tr.update(frame(33, listOf(Vec(.5f, .35f)))).first { it.lastSeenMs == 33L }.id)
    }

    @Test fun countsMissAtBottom() {
        val tr = Tracker()
        tr.update(frame(0, listOf(Vec(.3f, 0.9f * aspect), Vec(.7f, 0.8f))))
        tr.update(frame(200))
        assertEquals(1, tr.missed)
    }

    @Test fun atPredicts() {
        val tr = Tracker()
        tr.update(frame(0, listOf(Vec(.5f, .3f))))
        val t = tr.update(frame(33, listOf(Vec(.5f, .33f)))).single()
        val p = t.at(133)
        assertEquals(t.pos.y + t.vel.y * 100, p.y, 1e-5f)
        assertTrue(p.y > t.pos.y)
    }
}
