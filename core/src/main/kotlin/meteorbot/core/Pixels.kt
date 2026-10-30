package meteorbot.core

import java.nio.ByteBuffer

/** Copies an Android ImageReader RGBA_8888 plane into packed ARGB, skipping row padding. */
fun rgbaToArgb(src: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, out: IntArray) {
    for (y in 0 until h) {
        var i = y * rowStride
        for (x in 0 until w) {
            val r = src.get(i).toInt() and 0xFF
            val g = src.get(i + 1).toInt() and 0xFF
            val b = src.get(i + 2).toInt() and 0xFF
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            i += pixelStride
        }
    }
}

/** out = [hue 0..360, saturation 0..1, value 0..1] */
fun hsv(argb: Int, out: FloatArray) {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val d = max - minOf(r, g, b)
    var hue = when {
        d == 0f -> 0f
        max == r -> 60f * ((g - b) / d)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (hue < 0f) hue += 360f
    out[0] = hue
    out[1] = if (max == 0f) 0f else d / max
    out[2] = max
}
