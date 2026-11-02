package app.localai

import app.localai.ui.splitCodeFences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFenceTest {
    @Test fun splitsPlainAndCode() {
        val s = splitCodeFences("Intro\n```kotlin\nval x = 1\n```\nOutro")
        assertEquals(3, s.size)
        assertEquals("Intro", s[0].text)
        assertTrue(s[1].isCode); assertEquals("kotlin", s[1].language); assertEquals("val x = 1", s[1].text)
        assertEquals("Outro", s[2].text)
    }
    @Test fun unterminatedFenceWhileStreamingIsCode() {
        val s = splitCodeFences("```py\nprint(1)")
        assertEquals(1, s.size); assertTrue(s[0].isCode)
    }
    @Test fun htmlStaysInertText() {
        val s = splitCodeFences("<script>alert(1)</script>")
        assertEquals("<script>alert(1)</script>", s[0].text)
    }
}
