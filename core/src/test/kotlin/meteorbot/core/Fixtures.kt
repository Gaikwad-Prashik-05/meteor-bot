package meteorbot.core

import javax.imageio.ImageIO

data class Label(val file: String, val state: String, val meteors: List<Vec>, val bombs: List<Vec>)

/** Frames from Slice.mp4 scaled to 360 wide, with hand-checked labels in width units. */
object Fixtures {
    val labels: List<Label> by lazy {
        val rows = javaClass.getResource("/frames/labels.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
            .map { it.split(',') }
        rows.groupBy { it[0] }.map { (file, rs) ->
            fun pts(kind: String) = rs.filter { it[2] == kind }.map { Vec(it[3].toFloat(), it[4].toFloat()) }
            Label(file, rs[0][1], pts("M"), pts("B"))
        }
    }

    fun load(name: String): Triple<IntArray, Int, Int> {
        val img = ImageIO.read(javaClass.getResource("/frames/$name")!!)
        val w = img.width; val h = img.height
        return Triple(img.getRGB(0, 0, w, h, null, 0, w), w, h)
    }
}
