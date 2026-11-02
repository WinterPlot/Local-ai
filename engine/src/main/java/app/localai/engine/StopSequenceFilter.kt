package app.localai.engine

/**
 * Detects user-defined stop sequences in a text stream. Text that could still
 * turn out to be the start of a stop sequence is held back until it is
 * resolved, so a stop sequence is never partially shown.
 */
class StopSequenceFilter(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }
    private val hold = (this.stops.maxOfOrNull { it.length } ?: 1) - 1
    private val buffer = StringBuilder()

    var stopped: Boolean = false
        private set

    /** Feeds a chunk; returns text that is safe to display. */
    fun feed(chunk: String): String {
        if (stopped) return ""
        if (stops.isEmpty()) return chunk
        buffer.append(chunk)
        var earliest = -1
        for (s in stops) {
            val i = buffer.indexOf(s)
            if (i >= 0 && (earliest < 0 || i < earliest)) earliest = i
        }
        if (earliest >= 0) {
            val out = buffer.substring(0, earliest)
            buffer.clear()
            stopped = true
            return out
        }
        val safe = buffer.length - hold
        if (safe <= 0) return ""
        val out = buffer.substring(0, safe)
        buffer.delete(0, safe)
        return out
    }

    /** Flushes held-back text when the stream ends without a stop sequence. */
    fun finish(): String {
        if (stopped) return ""
        val out = buffer.toString()
        buffer.clear()
        return out
    }
}
