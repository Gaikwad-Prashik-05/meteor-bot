package meteorbot.core

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelsTest {
    @Test fun rowPaddingIgnored() {
        // 2x2 RGBA image with rowStride 16: 8 bytes of padding per row
        val b = ByteBuffer.allocate(32)
        fun px(r: Int, g: Int, bl: Int) { b.put(r.toByte()); b.put(g.toByte()); b.put(bl.toByte()); b.put(0xFF.toByte()) }
        px(255, 0, 0); px(0, 255, 0); repeat(8) { b.put(0x7F) }
        px(0, 0, 255); px(255, 255, 255); repeat(8) { b.put(0x7F) }
        b.rewind()
        val out = IntArray(4)
        rgbaToArgb(b, 2, 2, 16, 4, out)
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), -1), out)
    }

    @Test fun hsvOfPurple() {
        val o = FloatArray(3)
        hsv(0xFF7B3FE4.toInt(), o)
        assertEquals(262f, o[0], 2f)
        assertEquals(0.72f, o[1], 0.02f)
        assertEquals(0.89f, o[2], 0.02f)
    }

    @Test fun fixturesLoad() {
        assertTrue(Fixtures.labels.size >= 12)
        val (px, w, h) = Fixtures.load(Fixtures.labels[0].file)
        assertEquals(360, w)
        assertEquals(w * h, px.size)
    }
}
