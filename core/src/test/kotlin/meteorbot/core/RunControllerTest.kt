package meteorbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunControllerTest {
    private val strokes = ArrayList<Stroke>()
    private var stops = 0
    private var cancels = 0
    private val rc = RunController({ strokes += it }, { stops++ }, { cancels++ })

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
        feed("game6.png", 66)
        assertEquals(1, strokes.size)
        assertTrue(rc.strokeBusyUntilMs > 66)
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
        rc.onDetected(game(-33, listOf(Vec(.5f, .96f))), -33)
        rc.onDetected(game(0, listOf(Vec(.5f, 1f))), 0)
        rc.onDetected(game(33, listOf(Vec(.5f, 1.04f))), 33)
        val first = strokes.single()
        val cut = first.segments[0]
        rc.onDetected(game(66, listOf(Vec(.5f, 1.08f)), listOf(Blob((cut.a + cut.b) * 0.5f, 0.085f))), 66)
        assertEquals(2, strokes.size)
        assertTrue(strokes[1].targetIds.isEmpty())
        assertTrue(rc.strokeBusyUntilMs <= 66 + 60 + 1)
        // the abort tap goes in a bottom corner, away from the game's close button at top left
        assertTrue(strokes[1].segments[0].a.y > 1.1f)
    }

    @Test fun knownBombDoesNotAbort() {
        rc.onDetected(game(0, listOf(Vec(.5f, 1f)), listOf(Blob(Vec(.1f, 1.9f), 0.085f))), 0)
        rc.onDetected(game(33, listOf(Vec(.5f, 1.04f)), listOf(Blob(Vec(.1f, 1.9f), 0.085f))), 33)
        rc.onDetected(game(66, listOf(Vec(.5f, 1.08f)), listOf(Blob(Vec(.1f, 1.9f), 0.085f))), 66)
        assertEquals(1, strokes.size)
    }

    @Test fun leavingGameCancelsPendingSwipesAtOnce() {
        rc.onDetected(game(-33, listOf(Vec(.5f, .96f))), -33)
        rc.onDetected(game(0, listOf(Vec(.5f, 1f))), 0)
        rc.onDetected(game(33, listOf(Vec(.5f, 1.04f))), 33)
        assertEquals(1, strokes.size)
        rc.onDetected(Frame(66, 2.2f, ScreenState.OTHER, emptyList(), emptyList(), 0f), 66)
        assertEquals(1, cancels)
        assertEquals(0L, rc.strokeBusyUntilMs)
        rc.onDetected(Frame(99, 2.2f, ScreenState.OTHER, emptyList(), emptyList(), 0f), 99)
        assertEquals(1, cancels)
        assertEquals(1, strokes.size)
    }

    @Test fun inFlightRecheckedWithKnownBombVelocity() {
        // A known bomb whose measured velocity carries it onto the planned cut aborts the stroke.
        rc.onDetected(game(-33, listOf(Vec(.5f, .96f))), -33)
        rc.onDetected(game(0, listOf(Vec(.5f, 1f))), 0)
        rc.onDetected(game(33, listOf(Vec(.5f, 1.04f))), 33)
        val cut = strokes.single().segments[0]
        val target = (cut.a + cut.b) * 0.5f
        // First sighting is off to the side: clear even if it falls straight down fast.
        val side = Vec(target.x - .5f, target.y - .1f)
        rc.onDetected(game(40, listOf(Vec(.5f, 1.05f)), listOf(Blob(side, 0.085f))), 40)
        assertEquals(1, strokes.size)
        // Second sighting reveals it drifting sideways toward the cut.
        rc.onDetected(game(73, listOf(Vec(.5f, 1.09f)), listOf(Blob(side + Vec(.1f, .02f), 0.085f))), 73)
        assertEquals(2, strokes.size)
        assertTrue(strokes[1].targetIds.isEmpty())
    }
}
