package app.localai.engine

import kotlinx.serialization.Serializable

enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

data class ChatTurn(val role: Role, val content: String)

/** Facts reported by the runtime after loading. Fields the model does not provide are null/empty. */
@Serializable
data class LoadedModelInfo(
    val desc: String = "",
    val arch: String = "",
    val name: String = "",
    val sizeBytes: Long = 0,
    val nParams: Long = 0,
    val nCtxTrain: Int = 0,
    val nEmbd: Int = 0,
    val nLayer: Int = 0,
    val nHead: Int = 0,
    val nHeadKv: Int = 0,
    val hasChatTemplate: Boolean = false,
    val loadMs: Long = 0,
)

/** Settings that require recreating the llama context. */
data class ContextConfig(
    val contextSize: Int = 4096,
    val threads: Int = defaultThreads(),
    val batchSize: Int = 512,
    val useMmap: Boolean = true,
) {
    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}

/** Settings applied to every generation (no reload needed). */
data class SamplingParams(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Long = -1,
    val maxNewTokens: Int = 512,
    val stopSequences: List<String> = emptyList(),
) {
    /** Returns a human-readable problem, or null when valid. */
    fun validate(): String? = when {
        temperature < 0f || temperature > 5f -> "Temperature must be between 0 and 5"
        topK < 0 -> "Top-k must be 0 (off) or greater"
        topP <= 0f || topP > 1f -> "Top-p must be in (0, 1]"
        minP < 0f || minP >= 1f -> "Min-p must be in [0, 1)"
        repeatPenalty < 1f || repeatPenalty > 2f -> "Repetition penalty must be between 1 and 2"
        repeatLastN < 0 -> "Repeat window must be 0 or greater"
        maxNewTokens < 1 -> "Max new tokens must be at least 1"
        stopSequences.any { it.isEmpty() } -> "Stop sequences cannot be empty"
        else -> null
    }
}

enum class StopReason { END_OF_TEXT, MAX_TOKENS, CONTEXT_FULL, STOP_SEQUENCE, CANCELLED, ERROR }

data class GenerationStats(
    val promptTokens: Int,
    val promptTokensReused: Int,
    val promptMs: Long,
    val generatedTokens: Int,
    val generatedMs: Long,
) {
    val tokensPerSecond: Double? get() = if (generatedMs > 0 && generatedTokens > 0) generatedTokens * 1000.0 / generatedMs else null
    val promptTokensPerSecond: Double?
        get() {
            val evaluated = promptTokens - promptTokensReused
            return if (promptMs > 0 && evaluated > 0) evaluated * 1000.0 / promptMs else null
        }
}

sealed interface GenerationEvent {
    data class Text(val chunk: String) : GenerationEvent
    data class Finished(val reason: StopReason, val stats: GenerationStats) : GenerationEvent
}

sealed interface EngineState {
    data object Idle : EngineState                    // no model
    data object LoadingModel : EngineState
    data class Ready(val info: LoadedModelInfo, val contextSize: Int) : EngineState
    data object Generating : EngineState
    data class Failed(val error: EngineError) : EngineState
}

/** Typed, user-presentable failures. Never contains prompt or response text. */
sealed class EngineError(message: String) : Exception(message) {
    class NoModel : EngineError("No model is loaded.")
    class LoadFailed : EngineError("The model could not be loaded. The file may be corrupt, truncated, or use an architecture this build of llama.cpp does not support.")
    class ContextFailed : EngineError("The context could not be created, most likely because the device ran out of memory. Try a smaller context size.")
    class PromptTooLong : EngineError("The conversation does not fit in the context window.")
    class TemplateFailed : EngineError("The model's chat template could not be applied.")
    class DecodeFailed : EngineError("Inference failed while decoding.")
    class Busy : EngineError("The engine is busy with another operation.")
    class InvalidSettings(detail: String) : EngineError(detail)
}
