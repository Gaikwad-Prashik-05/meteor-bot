package meteorbot.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private var ids = 1
fun meteor(x: Float, y: Float, vy: Float = 0.0013f, vx: Float = 0f, t: Long = 1000) =
    Track(ids++, Kind.METEOR, Vec(x, y), Vec(vx, vy), 0.04f, 3, t)
fun bomb(x: Float, y: Float, r: Float = 0.085f, vy: Float = 0.0005f, t: Long = 1000) =
    Track(ids++, Kind.BOMB, Vec(x, y), Vec(0f, vy), r, 3, t)

class PlannerTest {
    private val aspect = 2.2f
    private val now = 1000L
    private fun planner() = Planner(Random(1))

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
}
