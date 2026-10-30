package meteorbot.core

import kotlin.math.sqrt

/** A point or vector in width units: x in [0,1], y in [0, aspect]. */
data class Vec(val x: Float, val y: Float) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec(x * k, y * k)
    fun len() = sqrt(x * x + y * y)
}

data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun contains(p: Vec) = p.x in l..r && p.y in t..b
}

fun distToSegment(p: Vec, a: Vec, b: Vec): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return (p - a).len()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).len()
}
