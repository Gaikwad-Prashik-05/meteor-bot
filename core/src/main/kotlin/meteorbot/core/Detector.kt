package meteorbot.core

// Thresholds tuned on frames from Slice.mp4 (see DetectorTest).
private const val GRID = 2                    // sample every 2 px of the 1/3-resolution frame
private const val HUD = 0.08f                 // top fraction of height ignored (score pill, close button)
private const val GAME_BAND_TOP = 0.45f       // band that is bright purple only during gameplay
private const val GAME_BAND_BOTTOM = 0.75f
private const val GAME_MIN = 0.6f
private const val PLANET_TOP = 0.8f
private const val MIN_RADIUS = 0.01f          // width units
private const val MIN_BOMB_RADIUS = 0.025f
private const val TAIL_MIN = 0.2f             // fraction of the window above a head that must be tail-coloured
private const val SKULL_V = 0.42f             // skull face is darker than this
private const val MIN_SKULL_RADIUS = 0.02f
private const val MAX_SKULL_RADIUS = 0.08f
private const val SKULL_TO_BOMB = 1.7f        // bomb radius / skull radius
private const val RING_SAMPLES = 16
private const val RING_SCALE = 1.4f           // ring radius / skull radius
private const val RING_MIN = 0.5f             // fraction of ring samples that must be bomb-orange

enum class ScreenState { GAME, OTHER }

data class Blob(val pos: Vec, val radius: Float)

data class Frame(
    val timeMs: Long, val aspect: Float, val state: ScreenState,
    val meteors: List<Blob>, val bombs: List<Blob>, val planetGrey: Float,
)

class Detector {
    private val c = FloatArray(3)
    private var labels = IntArray(0)
    private var stack = IntArray(0)

    fun detect(argb: IntArray, w: Int, h: Int, timeMs: Long, masks: List<Box> = emptyList()): Frame {
        val aspect = h / w.toFloat()
        val state = if (fraction(argb, w, h, GAME_BAND_TOP, GAME_BAND_BOTTOM) { gamePurple() } > GAME_MIN)
            ScreenState.GAME else ScreenState.OTHER
        val grey = fraction(argb, w, h, PLANET_TOP, 1f) { c[1] < 0.12f && c[2] > 0.25f }
        if (state != ScreenState.GAME) return Frame(timeMs, aspect, state, emptyList(), emptyList(), grey)

        val hudBottom = (h * HUD).toInt()
        fun open(x: Int, y: Int) = y >= hudBottom && masks.none { it.contains(Vec(x / w.toFloat(), y / w.toFloat())) }

        val meteors = components(argb, w, h) { x, y -> white() && open(x, y) }
            .map { it.toBlob(w) }
            .filter { it.radius >= MIN_RADIUS && hasTail(argb, w, h, it) }
        val bombs = components(argb, w, h) { x, y -> warm() && open(x, y) }
            .filter { it.toBlob(w).radius >= MIN_BOMB_RADIUS }
            .flatMap { skulls(argb, w, it) }
        return Frame(timeMs, aspect, state, meteors, bombs, grey)
    }

    private fun white() = c[2] > 0.9f && c[1] < 0.2f
    private fun warm() = (c[0] <= 50f || c[0] >= 320f) && c[1] > 0.4f && c[2] > 0.55f
    private fun tail() = (c[0] <= 45f || c[0] >= 320f) && c[1] > 0.35f
    private fun gamePurple() = c[0] in 240f..300f && c[1] > 0.15f && c[2] > 0.5f

    private inline fun fraction(argb: IntArray, w: Int, h: Int, top: Float, bottom: Float, pred: () -> Boolean): Float {
        var hit = 0; var all = 0
        var y = (h * top).toInt()
        while (y < (h * bottom).toInt()) {
            var x = 0
            while (x < w) { hsv(argb[y * w + x], c); all++; if (pred()) hit++; x += 3 }
            y += 3
        }
        return if (all == 0) 0f else hit / all.toFloat()
    }

    private class Comp(var n: Int = 0, var sx: Long = 0, var sy: Long = 0,
                       var x0: Int = Int.MAX_VALUE, var y0: Int = Int.MAX_VALUE, var x1: Int = 0, var y1: Int = 0) {
        fun toBlob(w: Int): Blob {
            val r = (maxOf(x1 - x0, y1 - y0) + GRID) / 2f
            return Blob(Vec((x0 + x1) / 2f / w, (y0 + y1) / 2f / w), r / w)
        }
    }

    /** Connected components of pixels matching [pred], on a grid sampled every [GRID] px. */
    private inline fun components(argb: IntArray, w: Int, h: Int, pred: (Int, Int) -> Boolean): List<Comp> {
        val gw = (w + GRID - 1) / GRID; val gh = (h + GRID - 1) / GRID
        if (labels.size < gw * gh) { labels = IntArray(gw * gh); stack = IntArray(gw * gh) }
        // 0 = unvisited, 1 = rejected, 2 = accepted
        java.util.Arrays.fill(labels, 0, gw * gh, 0)
        val out = ArrayList<Comp>()
        for (start in 0 until gw * gh) {
            if (labels[start] != 0) continue
            val sx = start % gw * GRID; val sy = start / gw * GRID
            hsv(argb[sy * w + sx], c)
            if (!pred(sx, sy)) { labels[start] = 1; continue }
            labels[start] = 2
            val comp = Comp()
            var top = 0; stack[top++] = start
            while (top > 0) {
                val i = stack[--top]
                val gx = i % gw; val gy = i / gw
                val px = gx * GRID; val py = gy * GRID
                comp.n++; comp.sx += px; comp.sy += py
                if (px < comp.x0) comp.x0 = px; if (px > comp.x1) comp.x1 = px
                if (py < comp.y0) comp.y0 = py; if (py > comp.y1) comp.y1 = py
                for (d in 0 until 4) {
                    val nx = gx + (if (d == 0) 1 else if (d == 1) -1 else 0)
                    val ny = gy + (if (d == 2) 1 else if (d == 3) -1 else 0)
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val j = ny * gw + nx
                    if (labels[j] != 0) continue
                    hsv(argb[ny * GRID * w + nx * GRID], c)
                    if (pred(nx * GRID, ny * GRID)) { labels[j] = 2; stack[top++] = j } else labels[j] = 1
                }
            }
            out += comp
        }
        return out
    }

    /** A meteor head has its orange-to-pink tail directly above it; slice debris does not. */
    private fun hasTail(argb: IntArray, w: Int, h: Int, b: Blob): Boolean {
        val r = b.radius * w; val cx = b.pos.x * w; val top = b.pos.y * w - r
        var hit = 0; var all = 0
        var y = (top - 4 * r).toInt().coerceAtLeast(0)
        while (y < top.toInt()) {
            var x = (cx - 2 * r).toInt().coerceAtLeast(0)
            while (x <= (cx + 2 * r).toInt().coerceAtMost(w - 1)) {
                if (y < h) { hsv(argb[y * w + x], c); all++; if (tail()) hit++ }
                x++
            }
            y++
        }
        return all > 0 && hit / all.toFloat() >= TAIL_MIN
    }

    /**
     * A bomb's orange body surrounds a dark skull face. Each dark patch fully inside an orange blob's
     * bounding box is one bomb, so touching bombs that merge into one orange blob are still separated.
     * Dark patches reaching the box edge are background sky, not skulls.
     */
    private fun skulls(argb: IntArray, w: Int, k: Comp): List<Blob> {
        val bw = k.x1 - k.x0 + 1; val bh = k.y1 - k.y0 + 1
        val seen = BooleanArray(bw * bh)
        val out = ArrayList<Blob>()
        val st = IntArray(bw * bh)
        for (s in 0 until bw * bh) {
            if (seen[s]) continue
            seen[s] = true
            hsv(argb[(k.y0 + s / bw) * w + k.x0 + s % bw], c)
            if (c[2] >= SKULL_V) continue
            var top = 0; st[top++] = s
            var x0 = bw; var x1 = 0; var y0 = bh; var y1 = 0
            while (top > 0) {
                val i = st[--top]; val x = i % bw; val y = i / bw
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (d in 0 until 4) {
                    val nx = x + (if (d == 0) 1 else if (d == 1) -1 else 0)
                    val ny = y + (if (d == 2) 1 else if (d == 3) -1 else 0)
                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue
                    val j = ny * bw + nx
                    if (seen[j]) continue
                    seen[j] = true
                    hsv(argb[(k.y0 + ny) * w + k.x0 + nx], c)
                    if (c[2] < SKULL_V) st[top++] = j
                }
            }
            val r = maxOf(x1 - x0, y1 - y0) / 2f
            val cx = k.x0 + (x0 + x1) / 2f; val cy = k.y0 + (y0 + y1) / 2f
            if (r / w in MIN_SKULL_RADIUS..MAX_SKULL_RADIUS && warmAround(argb, w, argb.size / w, cx, cy, r)) {
                out += Blob(Vec(cx / w, cy / w), r * SKULL_TO_BOMB / w)
            }
        }
        return out
    }

    /** True when most of a ring just outside a dark patch is bomb-orange (sky patches fail this). */
    private fun warmAround(argb: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float): Boolean {
        var hit = 0
        for (i in 0 until RING_SAMPLES) {
            val a = i * 2 * Math.PI / RING_SAMPLES
            val x = (cx + RING_SCALE * r * Math.cos(a)).toInt().coerceIn(0, w - 1)
            val y = (cy + RING_SCALE * r * Math.sin(a)).toInt().coerceIn(0, h - 1)
            hsv(argb[y * w + x], c)
            if (warm()) hit++
        }
        return hit >= RING_SAMPLES * RING_MIN
    }
}
