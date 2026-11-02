package app.localai.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {
    // 1 token per character: easy to reason about.
    private val count: suspend (List<ChatTurn>) -> Int = { turns -> turns.sumOf { it.content.length } }
    private fun u(s: String) = ChatTurn(Role.USER, s)
    private fun a(s: String) = ChatTurn(Role.ASSISTANT, s)
    private val system = ChatTurn(Role.SYSTEM, "sys")

    @Test fun keepsEverythingWhenItFits() = runTest {
        val r = PromptBudget.fit(system, listOf(u("aaaa"), a("bbbb"), u("cc")), 100, 20, count)
        assertEquals(0, r.omittedTurns)
        assertEquals(4, r.turns.size)
        assertFalse(r.overflow)
    }

    @Test fun dropsOldestTurnsFirstAndKeepsSystem() = runTest {
        val history = listOf(u("1111111111"), a("2222222222"), u("3333"))
        // budget 25 - system(3) leaves 22: needs to drop the first turn (10) -> 3+10+4=17
        val r = PromptBudget.fit(system, history, 35, 10, count)
        assertEquals(1, r.omittedTurns)
        assertEquals(system, r.turns.first())
        assertEquals("3333", r.turns.last().content)
        assertTrue(r.promptTokens <= r.budgetTokens)
    }

    @Test fun alwaysKeepsNewestMessageAndFlagsOverflow() = runTest {
        val r = PromptBudget.fit(system, listOf(u("old"), u("x".repeat(500))), 100, 10, count)
        assertTrue(r.overflow)
        assertEquals("x".repeat(500), r.turns.last().content)
        assertEquals(1, r.omittedTurns)
    }

    @Test fun reserveForReplyShrinksBudget() = runTest {
        val r = PromptBudget.fit(null, listOf(u("a".repeat(60)), u("b".repeat(30))), 100, 50, count)
        assertEquals(50, r.budgetTokens)
        assertEquals(1, r.omittedTurns)
    }
}
