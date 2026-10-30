package meteorbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorTest {
    private val det = Detector()
    private val tol = 0.04f

    private fun detect(file: String, masks: List<Box> = emptyList()): Frame {
        val (px, w, h) = Fixtures.load(file)
        return det.detect(px, w, h, 0L, masks)
    }

    private fun matched(label: Vec, found: List<Blob>, shift: Float = 0f) =
        found.any { (it.pos - Vec(label.x, label.y + shift)).len() < tol }

    @Test fun classifiesEveryFixture() {
        for (l in Fixtures.labels) assertEquals(l.file, l.state, detect(l.file).state.name)
    }

    @Test fun findsAllBombs() {
        for (l in Fixtures.labels) {
            val f = detect(l.file)
            for (b in l.bombs) assertTrue("${l.file} bomb $b not found in ${f.bombs}", matched(b, f.bombs))
        }
    }

    @Test fun findsMostMeteors() {
        var total = 0; var hit = 0
        for (l in Fixtures.labels.filter { it.state == "GAME" }) {
            val f = detect(l.file)
            total += l.meteors.size
            hit += l.meteors.count { matched(it, f.meteors) }
            val falsePos = f.meteors.count { m -> l.meteors.none { (it - m.pos).len() < tol } }
            assertTrue("${l.file}: $falsePos false meteors in ${f.meteors}", falsePos <= 1)
        }
        assertTrue("found $hit of $total meteors", hit >= 0.9 * total)
    }

    @Test fun debrisIsNotAMeteor() {
        // game2 is the t=22 s frame with slice debris (white crescents and dots) mid-screen
        assertEquals(1, detect("game2.png").meteors.size)
    }

    @Test fun tallerAspectStillDetects() {
        val (px, w, h) = Fixtures.load("game1.png")
        val pad = 80
        val tall = IntArray(w * (h + pad))
        for (y in 0 until pad) for (x in 0 until w) tall[y * w + x] = px[x]
        System.arraycopy(px, 0, tall, pad * w, px.size)
        val f = det.detect(tall, w, h + pad, 0L)
        val shift = pad / w.toFloat()
        assertEquals(ScreenState.GAME, f.state)
        val l = Fixtures.labels.first { it.file == "game1.png" }
        for (m in l.meteors) assertTrue("meteor $m", matched(m, f.meteors, shift))
        for (b in l.bombs) assertTrue("bomb $b", matched(b, f.bombs, shift))
    }

    @Test fun maskedRegionIgnored() {
        val target = Vec(0.209f, 0.544f)
        val f = detect("game1.png", listOf(Box(target.x - .05f, target.y - .05f, target.x + .05f, target.y + .05f)))
        assertTrue(f.meteors.none { (it.pos - target).len() < tol })
        assertEquals(2, f.meteors.size)
    }

    @Test fun greyPlanetMeasured() {
        assertTrue(detect("game6.png").planetGrey > 0.3f)
        assertTrue(detect("game1.png").planetGrey < 0.05f)
    }

    @Test fun positionsInWidthUnits() {
        val f = detect("game1.png")
        assertEquals(793f / 360f, f.aspect, 1e-3f)
    }
}
