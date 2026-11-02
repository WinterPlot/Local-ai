package app.localai.engine

/** Result of fitting a conversation into the context window. */
data class BudgetResult(
    val turns: List<ChatTurn>,
    /** Number of oldest non-system turns left out. */
    val omittedTurns: Int,
    val promptTokens: Int,
    val budgetTokens: Int,
    /** True when even the system prompt plus the newest message do not fit. */
    val overflow: Boolean,
)

/**
 * Deliberate context-management strategy: keep the system prompt and as many
 * of the most recent turns as fit in (contextSize - reservedForReply). Older
 * turns are dropped whole, oldest first, and the caller is told how many so
 * the UI can say so. The newest turn is always kept.
 */
object PromptBudget {
    /**
     * @param countTokens exact token count of a rendered conversation (the
     *   engine's chat template + tokenizer), or a heuristic in tests.
     */
    suspend fun fit(
        system: ChatTurn?,
        history: List<ChatTurn>,
        contextSize: Int,
        reservedForReply: Int,
        countTokens: suspend (List<ChatTurn>) -> Int,
    ): BudgetResult {
        require(history.isNotEmpty()) { "history must contain at least the newest message" }
        val budget = (contextSize - reservedForReply).coerceAtLeast(0)
        var start = 0
        while (true) {
            val candidate = buildList {
                if (system != null) add(system)
                addAll(history.subList(start, history.size))
            }
            val tokens = countTokens(candidate)
            if (tokens <= budget) {
                return BudgetResult(candidate, start, tokens, budget, overflow = false)
            }
            if (start >= history.size - 1) {
                return BudgetResult(candidate, start, tokens, budget, overflow = true)
            }
            start++
        }
    }

    /** Rough fallback (≈4 chars/token) used only for warnings before a model is loaded. */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4
}
