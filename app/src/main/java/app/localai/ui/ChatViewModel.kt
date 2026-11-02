package app.localai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.localai.AppContainer
import app.localai.data.ChatRepository
import app.localai.data.MessageEntity
import app.localai.engine.ChatTurn
import app.localai.engine.EngineError
import app.localai.engine.EngineState
import app.localai.engine.GenerationEvent
import app.localai.engine.GenerationStats
import app.localai.engine.PromptBudget
import app.localai.engine.Role
import app.localai.engine.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val generating: Boolean = false,
    val streaming: String = "",
    val error: String? = null,
    /** Shown when older messages were left out of the prompt to fit the context window. */
    val notice: String? = null,
    val lastStats: GenerationStats? = null,
    val contextUsed: Int? = null,
    val contextSize: Int? = null,
)

class ChatViewModel(private val c: AppContainer, val conversationId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> =
        c.chats.observeMessages(conversationId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val title: StateFlow<String?> =
        c.chats.observeConversation(conversationId).map { it?.title }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val engineState: StateFlow<EngineState> = c.engine.state

    private var job: Job? = null

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _ui.value.generating) return
        _ui.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            c.chats.addMessage(MessageEntity(conversationId = conversationId, role = "user", content = t, createdAt = System.currentTimeMillis()))
            if (c.chats.title(conversationId) == "New conversation") c.chats.rename(conversationId, t.take(40))
            startGeneration()
        }
    }

    /** Replaces the last assistant reply with a fresh one. */
    fun regenerate() {
        if (_ui.value.generating) return
        _ui.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            val history = c.chats.history(conversationId)
            history.lastOrNull()?.takeIf { it.role == "assistant" }?.let { c.chats.deleteFrom(conversationId, it.id) }
            if (c.chats.history(conversationId).isEmpty()) { _ui.update { it.copy(generating = false) }; return@launch }
            startGeneration()
        }
    }

    /** Replaces message [id] and everything after it, then generates a new reply. */
    fun editAndResend(id: Long, newText: String) {
        val t = newText.trim()
        if (t.isEmpty() || _ui.value.generating) return
        _ui.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            c.chats.deleteFrom(conversationId, id)
            c.chats.addMessage(MessageEntity(conversationId = conversationId, role = "user", content = t, createdAt = System.currentTimeMillis()))
            startGeneration()
        }
    }

    fun stop() = c.engine.cancelGeneration()

    fun dismissError() = _ui.update { it.copy(error = null) }

    suspend fun exportMarkdown(): String =
        ChatRepository.exportMarkdown(c.chats.title(conversationId) ?: "Conversation", c.chats.history(conversationId))

    suspend fun clearConversation() {
        c.chats.history(conversationId).firstOrNull()?.let { c.chats.deleteFrom(conversationId, it.id) }
    }

    private fun startGeneration() {
        job = viewModelScope.launch {
            val sb = StringBuilder()
            var reason: StopReason? = null
            var stats: GenerationStats? = null
            var failure: String? = null
            _ui.update { it.copy(generating = true, streaming = "", notice = null, error = null) }
            try {
                val settings = c.settings.settings.first()
                val ready = c.engine.state.value as? EngineState.Ready ?: throw EngineError.NoModel()
                val history = ChatRepository.toTurns(c.chats.history(conversationId))
                if (history.isEmpty()) return@launch
                val system = settings.systemPrompt.trim().takeIf { it.isNotEmpty() }?.let { ChatTurn(Role.SYSTEM, it) }
                val reserve = settings.sampling.maxNewTokens.coerceAtMost(ready.contextSize / 2)

                val fit = PromptBudget.fit(system, history, ready.contextSize, reserve) { turns ->
                    c.engine.countPromptTokens(turns) ?: throw EngineError.TemplateFailed()
                }
                if (fit.overflow) throw EngineError.PromptTooLong()
                _ui.update {
                    it.copy(
                        notice = if (fit.omittedTurns > 0) "${fit.omittedTurns} older message(s) were left out of the prompt to fit the ${ready.contextSize}-token context window." else null,
                        contextUsed = fit.promptTokens, contextSize = ready.contextSize,
                    )
                }
                val room = (ready.contextSize - fit.promptTokens - 8).coerceAtLeast(16)
                val params = settings.sampling.copy(maxNewTokens = minOf(settings.sampling.maxNewTokens, room))

                c.engine.generate(fit.turns, params).collect { ev ->
                    when (ev) {
                        is GenerationEvent.Text -> { sb.append(ev.chunk); _ui.update { it.copy(streaming = sb.toString()) } }
                        is GenerationEvent.Finished -> { reason = ev.reason; stats = ev.stats }
                    }
                }
            } catch (e: CancellationException) {
                reason = StopReason.CANCELLED
                throw e
            } catch (e: EngineError) {
                failure = e.message
                reason = StopReason.ERROR
            } catch (e: Exception) {
                failure = "Unexpected error (${e.javaClass.simpleName})"
                reason = StopReason.ERROR
            } finally {
                withContext(NonCancellable) {
                    // Partial text is kept even after Stop, an error, or leaving the screen.
                    if (sb.isNotEmpty()) {
                        c.chats.addMessage(
                            MessageEntity(
                                conversationId = conversationId, role = "assistant", content = sb.toString(),
                                createdAt = System.currentTimeMillis(), stopReason = (reason ?: StopReason.CANCELLED).name,
                                generatedTokens = stats?.generatedTokens, tokensPerSecond = stats?.tokensPerSecond,
                            ),
                        )
                    }
                    _ui.update { it.copy(generating = false, streaming = "", error = failure, lastStats = stats ?: it.lastStats) }
                }
            }
        }
    }
}
