package meteorbot.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunControllerTest {
    private val strokes = ArrayList<Stroke>()
    private var stops = 0
    private val rc = RunController(Random(1), { strokes += it }, { stops++ })

    private fun feed(file: String, t: Long, now: Long = t) {
        val (px, w, h) = Fixtures.load(file)
        rc.onFrame(px, w, h, t, now)
    }

    private fun game(t: Long, meteors: List<Vec> = emptyList(), bombs: List<Blob> = emptyList()) =
        Frame(t, 2.2f, ScreenState.GAME, meteors.map { Blob(it, 0.04f) }, bombs, 0f)

    @Test fun staleFrameDropped() {
        feed("game6.png", 0, now = 150)
        feed("game6.png", 33, now = 183)
        assertEquals(0, strokes.size)
    }

    @Test fun dispatchesOnGameFixture() {
        feed("game6.png", 0)
        feed("game6.png", 33)
        assertEquals(1, strokes.size)
        assertTrue(rc.strokeBusyUntilMs > 33)
    }

    @Test fun nonGameAfterGameStops() {
        feed("game1.png", 0)
        var t = 33L
        while (t <= 1200) { feed(if (t % 2 == 0L) "lost.png" else "won.png", t); t += 33 }
        assertEquals(1, stops)
    }

    @Test fun otherBeforeGameDoesNotStop() {
        var t = 0L
        while (t <= 5000) { feed("intro1.png", t); t += 100 }
        assertEquals(0, stops)
        assertEquals(0, strokes.size)
    }

    @Test fun newBombAborts() {
        rc.onDetected(game(0, listOf(Vec(.5f, 1f))), 0)
        rc.onDetected(game(33, listOf(Vec(.5f, 1.04f))), 33)
        val first = strokes.single()
        val cut = first.segments[0]
        rc.onDetected(game(66, listOf(Vec(.5f, 1.08f)), listOf(Blob((cut.a + cut.b) * 0.5f, 0.085f))), 66)
        assertEquals(2, strokes.size)
        assertTrue(strokes[1].targetIds.isEmpty())
        assertTrue(rc.strokeBusyUntilMs <= 66 + 60 + 1)
    }

    @Test fun knownBombDoesNotAbort() {
        rc.onDetected(game(0, listOf(Vec(.5f, 1f)), listOf(Blob(Vec(.1f, 1.9f), 0.085f))), 0)
        rc.onDetected(game(33, listOf(Vec(.5f, 1.04f)), listOf(Blob(Vec(.1f, 1.9f), 0.085f))), 33)
        rc.onDetected(game(66, listOf(Vec(.5f, 1.08f)), listOf(Blob(Vec(.1f, 1.9f), 0.085f))), 66)
        assertEquals(1, strokes.size)
    }
}
