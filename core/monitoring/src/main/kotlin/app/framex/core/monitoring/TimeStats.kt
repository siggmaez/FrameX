package app.framex.core.monitoring

/**
 * One layer's statistics from `dumpsys SurfaceFlinger --timestats -dump` for the time since the last clear.
 * [frameTimesMs] come from the "present to present" histogram (1 ms resolution); empty if the dump has none.
 */
data class TimeStatsSample(val layer: String, val totalFrames: Int, val averageFps: Float?, val frameTimesMs: List<Float>)

object TimeStatsParser {
    private val BUCKET = Regex("(\\d+)ms=(\\d+)")
    private const val MAX_PER_BUCKET = 400

    private class Block(val name: String) {
        var frames = 0
        var fps: Float? = null
        var pkg: String? = null
        val times = ArrayList<Float>()
    }


    /** Names of the layers in a dump, in order (for diagnostics). */
    fun layerNames(text: String): List<String> {
        val lines = text.lines().map { it.trim() }
        val out = ArrayList<String>()
        lines.forEachIndexed { i, l ->
            val next = lines.getOrNull(i + 1).orEmpty()
            if (l.startsWith("layerName") && '=' in l) out += l.substringAfter('=').trim()
            else if ('=' !in l && l.isNotEmpty() && next.startsWith("totalFrames")) out += l
        }
        return out
    }
    /** The layer of [pkg] that carries the game's frames (a SurfaceView if there is one, otherwise the busiest). */
    fun parse(text: String, pkg: String): TimeStatsSample? {
        val lines = text.lines().map { it.trim() }
        val blocks = ArrayList<Block>()
        var cur: Block? = null
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val next = lines.getOrNull(i + 1).orEmpty()
            when {
                l.startsWith("layerName") && '=' in l -> cur = Block(l.substringAfter('=').trim()).also { blocks += it }
                '=' !in l && l.isNotEmpty() && next.startsWith("totalFrames") -> cur = Block(l).also { blocks += it } // name on its own line
                l.startsWith("packageName") && '=' in l -> cur?.pkg = l.substringAfter('=').trim()
                l.startsWith("totalFrames") && '=' in l -> cur?.frames = l.substringAfter('=').trim().toIntOrNull() ?: 0
                l.startsWith("averageFPS") && '=' in l -> cur?.fps = l.substringAfter('=').trim().toFloatOrNull()
                l.startsWith("presentToPresent") -> {
                    var j = i + 1
                    while (j < lines.size && BUCKET.containsMatchIn(lines[j])) {
                        BUCKET.findAll(lines[j]).forEach { m ->
                            val ms = m.groupValues[1].toInt(); val n = m.groupValues[2].toInt()
                            if (ms > 0) repeat(minOf(n, MAX_PER_BUCKET)) { cur?.times?.add(ms.toFloat()) }
                        }
                        j++
                    }
                    i = j - 1
                }
            }
            i++
        }
        val mine = blocks.filter { it.frames > 0 && (it.name.contains(pkg) || it.pkg == pkg) }
        val best = mine.filter { it.name.startsWith("SurfaceView") }.maxByOrNull { it.frames } ?: mine.maxByOrNull { it.frames } ?: return null
        return TimeStatsSample(best.name, best.frames, best.fps, best.times)
    }
}
