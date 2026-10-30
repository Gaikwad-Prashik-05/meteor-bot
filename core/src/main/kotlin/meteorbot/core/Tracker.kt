package meteorbot.core

private const val MATCH_RADIUS = 0.1f   // width units
private const val FIRST_MATCH_RADIUS = 0.25f // no velocity yet: a fast meteor or a dropped frame can move this far
private const val FIRST_MATCH_RISE = 0.03f   // ...but objects only fall, so never match far upward
private const val DROP_AFTER_MS = 150L
private const val HIT_PLANET = 0.85f    // fraction of aspect below which a vanished meteor counts as missed

enum class Kind { METEOR, BOMB }

data class Track(
    val id: Int, val kind: Kind, val pos: Vec, val vel: Vec /* units per ms */,
    val radius: Float, val seen: Int, val lastSeenMs: Long,
) {
    fun at(tMs: Long): Vec = pos + vel * (tMs - lastSeenMs).toFloat()
}

class Tracker {
    private var tracks = listOf<Track>()
    private var nextId = 1
    var missed = 0
        private set

    fun update(f: Frame): List<Track> {
        val next = ArrayList<Track>()
        val unmatched = tracks.toMutableList()
        for ((kind, blobs) in listOf(Kind.METEOR to f.meteors, Kind.BOMB to f.bombs)) {
            // Greedy nearest-first matching against each track's predicted position.
            val pairs = blobs.indices.flatMap { b ->
                unmatched.filter { it.kind == kind }.map { t -> Triple(b, t, (t.at(f.timeMs) - blobs[b].pos).len()) }
            }.filter { (b, t, d) ->
                if (t.seen > 1) d < MATCH_RADIUS
                else d < FIRST_MATCH_RADIUS && blobs[b].pos.y - t.pos.y > -FIRST_MATCH_RISE
            }.sortedBy { it.third }
            val used = HashSet<Int>()
            for ((b, t, _) in pairs) {
                if (b in used || t !in unmatched) continue
                used += b; unmatched -= t
                val dt = (f.timeMs - t.lastSeenMs).coerceAtLeast(1).toFloat()
                val inst = (blobs[b].pos - t.pos) * (1f / dt)
                val vel = if (t.seen == 1) inst else inst * 0.6f + t.vel * 0.4f
                next += t.copy(pos = blobs[b].pos, vel = vel, radius = blobs[b].radius, seen = t.seen + 1, lastSeenMs = f.timeMs)
            }
            for (b in blobs.indices) if (b !in used) {
                next += Track(nextId++, kind, blobs[b].pos, Vec(0f, 0f), blobs[b].radius, 1, f.timeMs)
            }
        }
        for (t in unmatched) {
            if (f.timeMs - t.lastSeenMs <= DROP_AFTER_MS) next += t
            else if (t.kind == Kind.METEOR && t.pos.y > HIT_PLANET * f.aspect) missed++
        }
        tracks = next
        return next
    }
}
