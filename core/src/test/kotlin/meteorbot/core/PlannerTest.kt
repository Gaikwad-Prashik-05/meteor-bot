package meteorbot.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private var ids = 1
fun meteor(x: Float, y: Float, vy: Float = 0.0013f, vx: Float = 0f, t: Long = 1000) =
    Track(ids++, Kind.METEOR, Vec(x, y), Vec(vx, vy), 0.04f, 3, t, bornMs = t - 100)
fun bomb(x: Float, y: Float, r: Float = 0.085f, vy: Float = 0.0005f, t: Long = 1000) =
    Track(ids++, Kind.BOMB, Vec(x, y), Vec(0f, vy), r, 3, t)

class PlannerTest {
    private val aspect = 2.2f
    private val now = 1000L
    private fun planner() = Planner()

    @Test fun slicesLoneMeteor() {
        val m = meteor(.5f, 1f)
        val s = planner().plan(listOf(m), now, aspect, emptyList())
        assertNotNull(s)
        val seg = s!!.segments[0]
        val p = m.at(s.startMs + seg.durationMs / 2)
        assertTrue(distToSegment(p, seg.a, seg.b) < m.radius)
        assertEquals(listOf(m.id), s.targetIds)
    }

    @Test fun aimsAhead() {
        val m = meteor(.5f, .5f, vy = 0.002f)
        val s = planner().plan(listOf(m), now, aspect, emptyList())!!
        val seg = s.segments[0]
        assertTrue(s.startMs >= now + 60)
        assertTrue(distToSegment(m.pos, seg.a, seg.b) > 0.1f)
        assertTrue(distToSegment(m.at(s.startMs + seg.durationMs / 2), seg.a, seg.b) < m.radius)
    }

    @Test fun meteorHuggingBombSkipped() {
        val s = planner().plan(listOf(meteor(.5f, 1f), bomb(.5f, 1.03f, r = 0.03f)), now, aspect, emptyList())
        assertNull(s)
    }

    @Test fun picksLowestFirst() {
        val high = meteor(.3f, .6f); val low = meteor(.7f, 1.5f)
        val s = planner().plan(listOf(high, low), now, aspect, emptyList())!!
        assertEquals(low.id, s.targetIds[0])
    }

    @Test fun chainsUpTo3() {
        val ms = (0 until 5).map { meteor(.3f + .1f * it, 1f + .02f * it) }
        val s = planner().plan(ms, now, aspect, emptyList())!!
        assertTrue(s.targetIds.size in 2..3)
        assertTrue(strokeIsSafe(s, emptyList(), aspect))
        for (i in 1 until s.segments.size) assertEquals(s.segments[i - 1].b, s.segments[i].a)
    }

    @Test fun strokeAvoidsMask() {
        val m = meteor(.5f, 1f)
        assertNull(planner().plan(listOf(m), now, aspect, listOf(Box(.3f, .8f, .7f, 1.2f))))
    }

    @Test fun ignoresUnconfirmedMeteors() {
        val m = meteor(.5f, 1f).copy(seen = 1)
        assertNull(planner().plan(listOf(m), now, aspect, emptyList()))
    }

    @Test fun neverUnsafeProperty() {
        val rng = Random(42)
        var planned = 0
        repeat(5000) {
            val ms = List(rng.nextInt(0, 7)) {
                meteor(rng.nextFloat(), 0.2f + rng.nextFloat() * 1.8f, vy = rng.nextFloat() * 0.003f, vx = (rng.nextFloat() - .5f) * 0.001f)
            }
            val bs = List(rng.nextInt(0, 9)) {
                bomb(rng.nextFloat(), 0.2f + rng.nextFloat() * 1.8f, vy = rng.nextFloat() * 0.0015f)
            }
            val s = planner().plan(ms + bs, now, aspect, emptyList())
            if (s != null) { planned++; assertTrue("unsafe stroke $s", strokeIsSafe(s, bs, aspect)) }
        }
        assertTrue("planner almost never acts: $planned", planned > 500)
    }

    @Test fun newBombAssumedFalling() {
        // A bomb seen once has no velocity yet; it may be falling fast, so a cut just below it is unsafe.
        val m = meteor(.5f, 1.3f, vy = 0f)
        val fresh = bomb(.5f, 0.95f, vy = 0f).copy(seen = 1)
        assertNull(planner().plan(listOf(m, fresh), now, aspect, emptyList()))
        assertNotNull(planner().plan(listOf(m, fresh.copy(seen = 3)), now, aspect, emptyList()))
    }

    private val flat = Stroke(1060, listOf(Segment(Vec(.44f, 1f), Vec(.5f, 1f), Vec(.56f, 1f), 60)), emptyList())

    @Test fun lateStrokeStillClear() {
        // Safety holds even if the gesture runs up to 80 ms late: a bomb that only reaches the cut
        // after its planned time must still block it.
        val b = bomb(.5f, 0.6f, vy = 0.0015f)  // 0.22 clear on time, 0.10 if 80 ms late
        assertEquals(Block.BOMB, blockReason(flat, listOf(b), aspect))
        assertNull(blockReason(flat, listOf(b.copy(vel = Vec(0f, 0.0008f))), aspect))
    }

    @Test fun strokeKeepsClearOfMaskedArea() {
        // a bomb could be hidden under the button, so cuts stay a margin away from it
        assertEquals(Block.MASK, blockReason(flat, emptyList(), aspect, listOf(Box(.6f, .9f, .7f, 1.1f))))
        assertNull(blockReason(flat, emptyList(), aspect, listOf(Box(.64f, .9f, .7f, 1.1f))))
    }

    @Test fun controlPointMustBeOnScreen() {
        val seg = Segment(Vec(.005f, 1f), Vec(-.01f, 1.06f), Vec(.005f, 1.12f), 60)
        assertTrue(!strokeIsSafe(Stroke(now, listOf(seg), emptyList()), emptyList(), aspect))
    }

    @Test fun pastPartIgnoredWhenRechecking() {
        val seg = Segment(Vec(.4f, 1f), Vec(.5f, 1f), Vec(.6f, 1f), 60)
        val s = Stroke(now, listOf(seg, Segment(Vec(.6f, 1f), Vec(.7f, 1f), Vec(.8f, 1f), 60)), emptyList())
        val b = bomb(.3f, 1f, vy = 0f, t = now)
        assertTrue(!strokeIsSafe(s, listOf(b), aspect))
        assertTrue(strokeIsSafe(s, listOf(b), aspect, fromMs = now + 60))
    }

    @Test fun ignoresMeteorNotInLatestFrame() {
        // A sliced meteor lingers as a track; only meteors seen in the newest frame are targets.
        val gone = meteor(.5f, 1f, t = 967)
        assertNull(planner().plan(listOf(gone, bomb(.1f, 1.9f)), now, aspect, emptyList()))
    }

    @Test fun ignoresBrandNewMeteor() {
        assertNull(planner().plan(listOf(meteor(.5f, 1f).copy(bornMs = 980)), now, aspect, emptyList()))
    }

    @Test fun bombTrailAboveCounts() {
        // A swipe just above a bomb crosses its flame trail.
        val b = bomb(.5f, 1.2f, vy = 0f)
        assertEquals(Block.BOMB, blockReason(flat, listOf(b), aspect))
    }

    @Test fun fatalSwipeFromRun20261102IsBlocked() {
        // Real numbers from logs/run-2026-11-02_170331.txt: this swipe passed just above bomb b35 and lost the run.
        val b35 = Track(35, Kind.BOMB, Vec(.52f, .74f), Vec(0f, .0008f), .092f, 20, 761)
        val s = Stroke(846, listOf(Segment(Vec(.60f, .66f), Vec(.54f, .65f), Vec(.48f, .64f), 60)), listOf(41))
        assertEquals(Block.BOMB, blockReason(s, listOf(b35), aspect))
    }

    @Test fun reportsWhyNothingWasPlanned() {
        val p = planner()
        assertNull(p.plan(listOf(meteor(.5f, 1f), bomb(.5f, 1.03f, r = 0.03f)), now, aspect, emptyList()))
        assertTrue(p.blocked[Block.BOMB.ordinal] > 0)
    }
}

class TrackerSightingTest {
    @Test fun trackerRemembersFirstSighting() {
        val tr = Tracker()
        val f = { t: Long, y: Float -> Frame(t, 2.2f, ScreenState.GAME, listOf(Blob(Vec(.5f, y), .04f)), emptyList(), 0f) }
        tr.update(f(100, .5f)); tr.update(f(133, .53f))
        assertEquals(100L, tr.update(f(166, .56f)).single().bornMs)
    }
}
