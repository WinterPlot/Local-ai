package app.localai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSequenceFilterTest {
    private fun run(stops: List<String>, vararg chunks: String): Pair<String, Boolean> {
        val f = StopSequenceFilter(stops)
        val sb = StringBuilder()
        for (c in chunks) sb.append(f.feed(c))
        sb.append(f.finish())
        return sb.toString() to f.stopped
    }

    @Test fun noStopsPassesThrough() = assertEquals("hello world" to false, run(emptyList(), "hello", " world"))

    @Test fun stopsAtSequenceAndHidesIt() = assertEquals("Hello " to true, run(listOf("User:"), "Hello ", "User:", "ignored"))

    @Test fun stopSequenceSplitAcrossChunks() = assertEquals("Hi" to true, run(listOf("###"), "Hi#", "#", "#more"))

    @Test fun nearMissIsFlushedAtEnd() = assertEquals("a##b" to false, run(listOf("###"), "a#", "#b"))

    @Test fun earliestOfSeveralWins() = assertEquals("x" to true, run(listOf("END", "STOP"), "xSTOPEND"))

    @Test fun heldBackTextIsNotLostWhenStreamEnds() {
        val f = StopSequenceFilter(listOf("abcd"))
        val out = f.feed("abc") + f.finish()
        assertEquals("abc", out)
        assertFalse(f.stopped)
    }

    @Test fun nothingEmittedAfterStop() {
        val f = StopSequenceFilter(listOf("x"))
        f.feed("ax")
        assertTrue(f.stopped)
        assertEquals("", f.feed("more"))
    }
}
