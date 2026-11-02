package app.localai.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deterministic engine for unit and UI tests. It does NOT run a model: it
 * replays [script] word by word. Never wired into release builds.
 */
class FakeInferenceEngine(
    private val script: String = "This is a scripted test reply.",
    private val tokenDelayMs: Long = 0,
    private val info: LoadedModelInfo = LoadedModelInfo(arch = "fake", name = "fake", nCtxTrain = 4096, hasChatTemplate = true),
) : InferenceEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    var failNextLoad = false
    var lastTurns: List<ChatTurn> = emptyList()
        private set

    override suspend fun loadModel(path: String, config: ContextConfig) {
        _state.value = EngineState.LoadingModel
        if (failNextLoad) { failNextLoad = false; val e = EngineError.LoadFailed(); _state.value = EngineState.Failed(e); throw e }
        _state.value = EngineState.Ready(info, config.contextSize)
    }

    override suspend fun unloadModel() { _state.value = EngineState.Idle }

    override suspend fun countPromptTokens(turns: List<ChatTurn>): Int? =
        if (_state.value is EngineState.Idle) null else turns.sumOf { PromptBudget.estimateTokens(it.content) + 4 }

    override suspend fun countTextTokens(text: String): Int? =
        if (_state.value is EngineState.Idle) null else PromptBudget.estimateTokens(text)

    override suspend fun hasModelChatTemplate(): Boolean = info.hasChatTemplate

    override fun generate(turns: List<ChatTurn>, params: SamplingParams): Flow<GenerationEvent> = flow {
        val ready = _state.value as? EngineState.Ready ?: throw EngineError.NoModel()
        lastTurns = turns
        cancelled.set(false)
        _state.value = EngineState.Generating
        var reason = StopReason.END_OF_TEXT
        var count = 0
        try {
            val filter = StopSequenceFilter(params.stopSequences)
            for (word in script.split(" ")) {
                if (cancelled.get()) { reason = StopReason.CANCELLED; break }
                if (count >= params.maxNewTokens) { reason = StopReason.MAX_TOKENS; break }
                if (tokenDelayMs > 0) delay(tokenDelayMs)
                val out = filter.feed(if (count == 0) word else " $word")
                if (out.isNotEmpty()) emit(GenerationEvent.Text(out))
                count++
                if (filter.stopped) { reason = StopReason.STOP_SEQUENCE; break }
            }
            if (reason == StopReason.END_OF_TEXT) filter.finish().takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
            emit(GenerationEvent.Finished(reason, GenerationStats(0, 0, 0, count, count.toLong())))
        } finally {
            _state.value = ready
        }
    }

    override fun cancelGeneration() { cancelled.set(true) }
    override fun systemInfo(): String = "fake engine"
    override fun release() { _state.value = EngineState.Idle }
}
