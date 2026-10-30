package meteorbot.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTest {
    private val aspect = 2.2f
    private val now = 1000L

    /** Feeds [n] easy isolated meteors, one new meteor per plan call, and returns the planner. */
    private fun run(p: Planner, n: Int, grey: Float = 0f, missed: Int = 0): Planner {
        repeat(n) { p.allowMisses(grey, missed); p.plan(listOf(meteor(.5f, 1f)), now, aspect, emptyList()) }
        return p
    }

    @Test fun reactionDelayInRange() {
        val p = Planner(Random(3))
        val delays = (0 until 1000).map { p.plan(listOf(meteor(.5f, 1f)), now, aspect, emptyList())!!.startMs - now - p.latencyMs }
        assertTrue(delays.all { it in 80L..200L })
        assertEquals(140.0, delays.average(), 10.0)
    }

    @Test fun missBudgetRange() {
        val budgets = (0 until 1000).map { Planner(Random(it)).missBudget }.toSet()
        assertEquals(setOf(1, 2, 3, 4), budgets)
    }

    @Test fun deliberateMissesRespectBudget() {
        for (seed in 0 until 50) {
            val p = run(Planner(Random(seed)), 200)
            assertTrue("seed $seed: ${p.deliberateMisses} of ${p.missBudget}", p.deliberateMisses in 1..p.missBudget)
        }
    }

    @Test fun deliberateMissesAreSpread() {
        // Not all spent on the first few meteors: with p=0.08 per meteor the first miss is rarely in the first 3.
        val firsts = (0 until 200).map { seed ->
            val p = Planner(Random(seed)); var i = 0
            while (p.deliberateMisses == 0 && i < 500) { run(p, 1); i++ }
            i
        }
        assertTrue(firsts.average() > 5)
    }

    @Test fun greyCapStopsMisses() {
        assertEquals(0, run(Planner(Random(5)), 200, grey = 0.2f).deliberateMisses)
    }

    @Test fun forcedMissesCount() {
        val p = Planner(Random(6))
        assertEquals(0, run(p, 200, missed = p.missBudget).deliberateMisses)
    }

    @Test fun ignoredMeteorIsNeverTargeted() {
        val p = Planner(Random(7))
        var m = meteor(.5f, 1f)
        while (p.deliberateMisses == 0) { m = meteor(.5f, 1f); p.allowMisses(0f, 0); p.plan(listOf(m), now, aspect, emptyList()) }
        // The meteor just chosen as a deliberate miss stays ignored on every later frame.
        repeat(20) { assertEquals(null, p.plan(listOf(m), now + it * 33, aspect, emptyList())) }
    }

    @Test fun wobbleWithinLimit() {
        val p = Planner(Random(8))
        var bent = 0
        repeat(500) {
            val seg = p.plan(listOf(meteor(.5f, 1f)), now, aspect, emptyList())!!.segments[0]
            val off = distToSegment(seg.mid, seg.a, seg.b)
            assertTrue(off <= 0.15f * 0.12f + 1e-4f)
            if (off > 0.005f) bent++
            assertTrue(seg.durationMs in 40L..80L)
        }
        assertTrue(bent > 100)
    }

    @Test fun strokesStillSafe() {
        val rng = Random(43)
        var planned = 0
        repeat(5000) { i ->
            val ms = List(rng.nextInt(0, 7)) { meteor(rng.nextFloat(), 0.2f + rng.nextFloat() * 1.8f, vy = rng.nextFloat() * 0.003f) }
            val bs = List(rng.nextInt(0, 9)) { bomb(rng.nextFloat(), 0.2f + rng.nextFloat() * 1.8f, vy = rng.nextFloat() * 0.0015f) }
            val s = Planner(Random(i)).plan(ms + bs, now, aspect, emptyList())
            if (s != null) { planned++; assertTrue(strokeIsSafe(s, bs, aspect)) }
        }
        assertTrue(planned > 500)
    }
}

class ReactionFromSightingTest {
    @Test fun meteorSeenLongAgoIsCutWithoutExtraDelay() {
        // Reaction time counts from when the meteor first appeared, not from when the bot became free.
        val p = Planner(Random(9))
        val m = meteor(.5f, 1f).copy(bornMs = 500)
        val s = p.plan(listOf(m), 1000, 2.2f, emptyList())!!
        assertEquals(1000 + p.latencyMs, s.startMs)
    }

    @Test fun newMeteorStillGetsReactionDelay() {
        val p = Planner(Random(9))
        val m = meteor(.5f, 1f).copy(bornMs = 980)
        val d = p.plan(listOf(m), 1000, 2.2f, emptyList())!!.startMs - (980 + p.latencyMs)
        assertTrue(d in 80L..200L)
    }

    @Test fun trackerRemembersFirstSighting() {
        val tr = Tracker()
        val f = { t: Long, y: Float -> Frame(t, 2.2f, ScreenState.GAME, listOf(Blob(Vec(.5f, y), .04f)), emptyList(), 0f) }
        tr.update(f(100, .5f)); tr.update(f(133, .53f))
        assertEquals(100L, tr.update(f(166, .56f)).single().bornMs)
    }
}
