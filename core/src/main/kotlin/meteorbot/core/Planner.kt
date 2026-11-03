package meteorbot.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val SEGMENT_LEN = 0.12f
private const val SEGMENT_MS = 60L
private const val SPEED = SEGMENT_LEN / SEGMENT_MS   // width units per ms, also used for connectors
private const val ANGLE_STEP = 20.0                  // degrees
private const val CHAIN_MAX = 3
private const val CHAIN_REACH = 0.25f
private const val BOMB_CLEARANCE = 1.5f              // × bomb radius, from its centre
private const val SAMPLE_MS = 5L
private const val LATE_SLACK_MS = 80L                // gestures may run this late; bombs are checked over the window
private const val EARLY_SLACK_MS = 60L               // ...or land as soon as they are dispatched (latencyMs early)
private const val MIN_TRACK_MS = 50L                 // a meteor must be tracked this long before its velocity is trusted
private const val BOMB_MAX_FALL = 0.003f             // width units per ms; assumed for a bomb seen only once
private const val MASK_MARGIN = 0.06f                // a bomb may hide under the button; keep cuts this far away
private const val HUD = 0.08f

/** A quadratic curve from a to b with control point mid. */
data class Segment(val a: Vec, val mid: Vec, val b: Vec, val durationMs: Long) {
    fun point(u: Float): Vec = a * ((1 - u) * (1 - u)) + mid * (2 * u * (1 - u)) + b * (u * u)
}

/** startMs is when the finger touches the screen; dispatch it [Planner.latencyMs] earlier. */
data class Stroke(val startMs: Long, val segments: List<Segment>, val targetIds: List<Int>)

/**
 * True when every point of the stroke from [fromMs] on keeps clear of every bomb, even if the finger
 * gets there up to [LATE_SLACK_MS] late. A bomb seen only once is assumed to be falling as fast as anything does.
 */
fun strokeIsSafe(s: Stroke, bombs: List<Track>, aspect: Float, masks: List<Box> = emptyList(), fromMs: Long = Long.MIN_VALUE) =
    blockReason(s, bombs, aspect, masks, fromMs) == null

/** Why a stroke is unsafe: [Block.EDGE], [Block.MASK] or [Block.BOMB]; null when it is safe. */
fun blockReason(s: Stroke, bombs: List<Track>, aspect: Float, masks: List<Box> = emptyList(), fromMs: Long = Long.MIN_VALUE): Block? {
    val keepOut = masks.map { Box(it.l - MASK_MARGIN, it.t - MASK_MARGIN, it.r + MASK_MARGIN, it.b + MASK_MARGIN) }
    var t0 = s.startMs
    for (seg in s.segments) {
        // The path's control point counts toward gesture bounds, so it must be on screen too.
        if (seg.mid.x !in 0f..1f || seg.mid.y !in 0f..aspect) return Block.EDGE
        var dt = 0L
        while (true) {
            val t = t0 + dt
            val p = seg.point(if (seg.durationMs == 0L) 1f else dt.toFloat() / seg.durationMs)
            if (t >= fromMs) {
                if (p.x !in 0f..1f || p.y !in HUD * aspect..aspect) return Block.EDGE
                if (keepOut.any { it.contains(p) }) return Block.MASK
                for (b in bombs) {
                    // The bomb's flame trail above it counts as bomb too: a swipe through it lost a run.
                    val from = b.at(t - EARLY_SLACK_MS) - Vec(0f, b.radius)
                    val to = if (b.seen > 1) b.at(t + LATE_SLACK_MS)
                    else b.pos + Vec(0f, BOMB_MAX_FALL * (t + LATE_SLACK_MS - b.lastSeenMs).coerceAtLeast(0))
                    if (distToSegment(p, from, to) <= BOMB_CLEARANCE * b.radius) return Block.BOMB
                }
            }
            if (dt >= seg.durationMs) break
            dt = minOf(dt + SAMPLE_MS, seg.durationMs)
        }
        t0 += seg.durationMs
    }
    return null
}

enum class Block { EDGE, MASK, BOMB }

class Planner(val latencyMs: Long = 60) {
    /** Rejected cut attempts in the last [plan] call, by reason; for the run log. */
    val blocked = IntArray(Block.entries.size)

    fun plan(tracks: List<Track>, nowMs: Long, aspect: Float, masks: List<Box>): Stroke? {
        blocked.fill(0)
        val bombs = tracks.filter { it.kind == Kind.BOMB }
        // Only meteors in the latest frame: a sliced one lingers as a track for a while and was being swiped twice.
        val latest = tracks.maxOfOrNull { it.lastSeenMs } ?: return null
        val candidates = tracks.filter {
            it.kind == Kind.METEOR && it.seen >= 2 && it.lastSeenMs == latest && it.lastSeenMs - it.bornMs >= MIN_TRACK_MS
        }.sortedByDescending { it.pos.y }
        val start = nowMs + latencyMs
        for (first in candidates) {
            val cut = cutThrough(first, start, null, bombs, aspect, masks) ?: continue
            var stroke = Stroke(start, listOf(cut), listOf(first.id))
            for (next in candidates) {
                if (stroke.targetIds.size >= CHAIN_MAX) break
                if (next.id in stroke.targetIds) continue
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
        val ms = SEGMENT_MS
        val p = m.at(startMs + ms / 2)
        val base = if (m.vel.len() < 1e-6f) 0.0 else atan2(m.vel.y.toDouble(), m.vel.x.toDouble()) + Math.PI / 2
        for (k in 0 until (180 / ANGLE_STEP).toInt()) {
            val off = Math.toRadians(ANGLE_STEP * ((k + 1) / 2) * if (k % 2 == 0) 1 else -1)
            val dir = Vec(cos(base + off).toFloat(), sin(base + off).toFloat()) * (SEGMENT_LEN / 2)
            // When chaining, start the cut from the end nearer the previous stroke.
            var a = p - dir; var b = p + dir
            if (from != null && (b - from).len() < (a - from).len()) { val t = a; a = b; b = t }
            val seg = Segment(a, (a + b) * 0.5f, b, ms)
            val why = blockReason(Stroke(startMs, listOf(seg), emptyList()), bombs, aspect, masks) ?: return seg
            blocked[why.ordinal]++
        }
        return null
    }
}
