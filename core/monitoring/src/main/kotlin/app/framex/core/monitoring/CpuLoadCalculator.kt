package app.framex.core.monitoring

/** Pure parser/calculator for the aggregate "cpu" line of /proc/stat. */
class CpuLoadCalculator {
    private var lastTotal = -1L
    private var lastIdle = -1L

    /** Returns load 0..1 versus the previous call, or null on the first call / invalid input. */
    fun update(statLine: String): Float? {
        val parts = statLine.trim().split(Regex("\\s+"))
        if (parts.size < 5 || parts[0] != "cpu") return null
        val v = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + (v.getOrNull(4) ?: 0L)
        val total = v.take(8).sum()
        val result = if (lastTotal >= 0 && total > lastTotal) {
            val dt = total - lastTotal
            val di = idle - lastIdle
            ((dt - di).toFloat() / dt).coerceIn(0f, 1f)
        } else null
        lastTotal = total
        lastIdle = idle
        return result
    }
}
