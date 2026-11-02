package app.localai.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend-agnostic inference contract. [LlamaEngine] is the llama.cpp
 * implementation; [FakeInferenceEngine] exists for UI tests. Future backends
 * (other runtimes, remote-free alternatives) implement the same interface.
 */
interface InferenceEngine {
    val state: StateFlow<EngineState>

    /** Loads a GGUF file from an absolute path and creates a context. Throws [EngineError]. */
    suspend fun loadModel(path: String, config: ContextConfig)

    suspend fun unloadModel()

    /**
     * Renders [turns] with the model's own chat template and counts tokens.
     * Returns null if no model is loaded or the template cannot be applied.
     */
    suspend fun countPromptTokens(turns: List<ChatTurn>): Int?

    suspend fun countTextTokens(text: String): Int?

    /** True when the GGUF carries its own chat template (false = generic fallback is used). */
    suspend fun hasModelChatTemplate(): Boolean

    /**
     * Streams a reply to [turns]. Cancelling the collector stops native
     * decoding. Only one generation can run at a time.
     */
    fun generate(turns: List<ChatTurn>, params: SamplingParams): Flow<GenerationEvent>

    /** Requests the running generation to stop as soon as possible. */
    fun cancelGeneration()

    fun systemInfo(): String

    fun release()
}
