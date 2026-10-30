package meteorbot.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val SEGMENT_LEN = 0.12f
private const val SEGMENT_MS = 60L                   // average cut duration; each cut takes 40..80 ms
private val CUT_MS = 40L..80L
private const val WOBBLE = 0.15f                     // max sideways bend, × segment length
private const val MISS_CHANCE = 0.08f               // per new isolated meteor, while misses are allowed
private const val ISOLATION = 0.2f
private const val GREY_CAP = 0.15f
private const val SPEED = SEGMENT_LEN / SEGMENT_MS   // width units per ms, also used for connectors
private const val ANGLE_STEP = 20.0                  // degrees
private const val CHAIN_MAX = 3
private const val CHAIN_REACH = 0.25f
private const val BOMB_CLEARANCE = 1.5f * 2f         // × bomb radius, i.e. 1.5 bomb diameters from its centre
private const val SAMPLE_MS = 5L
private const val HUD = 0.08f

/** A quadratic curve from a to b with control point mid. */
data class Segment(val a: Vec, val mid: Vec, val b: Vec, val durationMs: Long) {
    fun point(u: Float): Vec = a * ((1 - u) * (1 - u)) + mid * (2 * u * (1 - u)) + b * (u * u)
}

/** startMs is when the finger touches the screen; dispatch it [Planner.latencyMs] earlier. */
data class Stroke(val startMs: Long, val segments: List<Segment>, val targetIds: List<Int>)

/** True when every point of the stroke, at the moment the finger is there, keeps clear of every bomb. */
fun strokeIsSafe(s: Stroke, bombs: List<Track>, aspect: Float, masks: List<Box> = emptyList()): Boolean {
    var t0 = s.startMs
    for (seg in s.segments) {
        var dt = 0L
        while (true) {
            val p = seg.point(if (seg.durationMs == 0L) 1f else dt.toFloat() / seg.durationMs)
            if (p.x !in 0f..1f || p.y !in HUD * aspect..aspect) return false
            if (masks.any { it.contains(p) }) return false
            for (b in bombs) if ((p - b.at(t0 + dt)).len() <= BOMB_CLEARANCE * b.radius) return false
            if (dt >= seg.durationMs) break
            dt = minOf(dt + SAMPLE_MS, seg.durationMs)
        }
        t0 += seg.durationMs
    }
    return true
}

class Planner(private val rng: Random, val latencyMs: Long = 60) {
    var reactionMs: IntRange = 80..200
    var wobble = true
    /** Meteors this run may let through on purpose, forced misses included. */
    val missBudget: Int = rng.nextInt(1, 5)
    val deliberateMisses get() = ignored.size

    private val ignored = HashSet<Int>()
    private val decided = HashSet<Int>()
    private var missesAllowed = false

    /** Call once per frame: no deliberate misses once the planet greys or the budget is spent. */
    fun allowMisses(planetGrey: Float, missedSoFar: Int) {
        missesAllowed = planetGrey <= GREY_CAP && ignored.size + missedSoFar < missBudget
    }

    private fun isolated(m: Track, tracks: List<Track>) =
        tracks.none { it.id != m.id && (it.pos - m.pos).len() < ISOLATION }

    fun plan(tracks: List<Track>, nowMs: Long, aspect: Float, masks: List<Box>): Stroke? {
        val bombs = tracks.filter { it.kind == Kind.BOMB }
        val candidates = tracks.filter { it.kind == Kind.METEOR && it.seen >= 2 && it.id !in ignored }
            .sortedByDescending { it.pos.y }
        val start = nowMs + latencyMs + rng.nextInt(reactionMs.first, reactionMs.last + 1)
        for (first in candidates) {
            // Each meteor gets one chance to become a deliberate miss, the first time it is the lead target.
            if (decided.add(first.id) && missesAllowed && isolated(first, tracks) && rng.nextFloat() < MISS_CHANCE) {
                ignored += first.id
                missesAllowed = false
                continue
            }
            val cut = cutThrough(first, start, null, bombs, aspect, masks) ?: continue
            var stroke = Stroke(start, listOf(cut), listOf(first.id))
            for (next in candidates) {
                if (stroke.targetIds.size >= CHAIN_MAX) break
                if (next.id in stroke.targetIds || next.id in ignored) continue
                stroke = extend(stroke, next, bombs, aspect, masks) ?: stroke
            }
            return stroke
        }
        return null
    }

    private fun end(s: Stroke) = s.startMs + s.segments.sumOf { it.durationMs }

    /** Adds a straight connector from the stroke's end plus a cut through [m], if both are safe. */
    private fun extend(s: Stroke, m: Track, bombs: List<Track>, aspect: Float, masks: List<Box>): Stroke? {
        val from = s.segments.last().b
        if ((m.pos - from).len() > CHAIN_REACH * 2) return null
        val connMs = ((m.pos - from).len() / SPEED).toLong()
        val cut = cutThrough(m, end(s) + connMs, from, bombs, aspect, masks) ?: return null
        if ((cut.a - from).len() > CHAIN_REACH) return null
        // The connector takes exactly connMs so the cut lands when it was aimed.
        val conn = Segment(from, (from + cut.a) * 0.5f, cut.a, connMs.coerceAtLeast(1))
        val out = Stroke(s.startMs, s.segments + conn + cut, s.targetIds + m.id)
        return out.takeIf { strokeIsSafe(it, bombs, aspect, masks) }
    }

    /**
     * A segment centred on where [m] will be halfway through the cut, crossing its path. Orientations
     * are tried outward from perpendicular in [ANGLE_STEP] steps; the first safe one wins.
     */
    private fun cutThrough(m: Track, startMs: Long, from: Vec?, bombs: List<Track>, aspect: Float, masks: List<Box>): Segment? {
        val ms = rng.nextLong(CUT_MS.first, CUT_MS.last + 1)
        val bend = if (wobble) (rng.nextFloat() * 2 - 1) * WOBBLE * SEGMENT_LEN else 0f
        val p = m.at(startMs + ms / 2)
        val base = if (m.vel.len() < 1e-6f) 0.0 else atan2(m.vel.y.toDouble(), m.vel.x.toDouble()) + Math.PI / 2
        for (k in 0 until (180 / ANGLE_STEP).toInt()) {
            val off = Math.toRadians(ANGLE_STEP * ((k + 1) / 2) * if (k % 2 == 0) 1 else -1)
            val dir = Vec(cos(base + off).toFloat(), sin(base + off).toFloat()) * (SEGMENT_LEN / 2)
            // When chaining, start the cut from the end nearer the previous stroke.
            var a = p - dir; var b = p + dir
            if (from != null && (b - from).len() < (a - from).len()) { val t = a; a = b; b = t }
            val perp = Vec(-(b - a).y, (b - a).x) * (bend / SEGMENT_LEN)
            val seg = Segment(a, (a + b) * 0.5f + perp, b, ms)
            if (strokeIsSafe(Stroke(startMs, listOf(seg), emptyList()), bombs, aspect, masks)) return seg
        }
        return null
    }
}
