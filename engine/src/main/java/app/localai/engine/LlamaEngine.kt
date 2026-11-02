package app.localai.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp implementation of [InferenceEngine].
 *
 * Every native call except cancellation runs on ONE dedicated thread, so the
 * single native session can never be touched concurrently, and the main
 * thread is never blocked. Cancellation is a lock-free flag that the native
 * decode loop and llama.cpp's abort callback both honour.
 */
class LlamaEngine(private val nativeLibDir: String) : InferenceEngine {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "localai-native").apply { priority = Thread.NORM_PRIORITY } }
    private val nativeDispatcher = executor.asCoroutineDispatcher()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true }

    private fun ensureInit() {
        if (!initialized) {
            NativeBridge.nInit(nativeLibDir)
            initialized = true
        }
    }

    override suspend fun loadModel(path: String, config: ContextConfig) {
        if (!busy.compareAndSet(false, true)) throw EngineError.Busy()
        try {
            _state.value = EngineState.LoadingModel
            val ready = withContext(nativeDispatcher) {
                ensureInit()
                NativeBridge.nFreeContext()
                NativeBridge.nUnloadModel()
                if (NativeBridge.nLoadModel(path, config.useMmap) != 0) throw EngineError.LoadFailed()

                val info = NativeBridge.nModelInfo()?.let {
                    json.decodeFromString(LoadedModelInfo.serializer(), String(it, Charsets.UTF_8))
                } ?: run { NativeBridge.nUnloadModel(); throw EngineError.LoadFailed() }

                val ctx = if (info.nCtxTrain > 0) minOf(config.contextSize, info.nCtxTrain) else config.contextSize
                if (NativeBridge.nCreateContext(ctx, config.threads, config.batchSize) != 0) {
                    NativeBridge.nUnloadModel()
                    throw EngineError.ContextFailed()
                }
                EngineState.Ready(info, NativeBridge.nContextSize())
            }
            _state.value = ready
        } catch (e: EngineError) {
            _state.value = EngineState.Failed(e)
            throw e
        } finally {
            busy.set(false)
        }
    }

    override suspend fun unloadModel() {
        if (!busy.compareAndSet(false, true)) throw EngineError.Busy()
        try {
            withContext(nativeDispatcher) {
                NativeBridge.nFreeContext()
                NativeBridge.nUnloadModel()
            }
            _state.value = EngineState.Idle
        } finally {
            busy.set(false)
        }
    }

    private fun isLoaded() = _state.value is EngineState.Ready || _state.value is EngineState.Generating

    private fun render(turns: List<ChatTurn>): ByteArray? {
        val roles = Array(turns.size) { turns[it].role.wire }
        val contents = Array(turns.size) { turns[it].content.toByteArray(Charsets.UTF_8) }
        return NativeBridge.nRenderPrompt(roles, contents)
    }

    override suspend fun countPromptTokens(turns: List<ChatTurn>): Int? {
        if (!isLoaded()) return null
        return withContext(nativeDispatcher) {
            val prompt = render(turns) ?: return@withContext null
            val n = NativeBridge.nCountTokens(prompt)
            if (n < 0) null else n + 1 // + BOS, which the native side may prepend
        }
    }

    override suspend fun countTextTokens(text: String): Int? {
        if (!isLoaded()) return null
        return withContext(nativeDispatcher) {
            val n = NativeBridge.nCountTokens(text.toByteArray(Charsets.UTF_8))
            if (n < 0) null else n
        }
    }

    override suspend fun hasModelChatTemplate(): Boolean {
        val s = _state.value
        return (s as? EngineState.Ready)?.info?.hasChatTemplate ?: false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun generate(turns: List<ChatTurn>, params: SamplingParams): Flow<GenerationEvent> = flow {
        params.validate()?.let { throw EngineError.InvalidSettings(it) }
        val current = _state.value
        if (current is EngineState.Generating || current is EngineState.LoadingModel) throw EngineError.Busy()
        val ready = current as? EngineState.Ready ?: throw EngineError.NoModel()
        if (!busy.compareAndSet(false, true)) throw EngineError.Busy()

        val finished = AtomicBoolean(false)
        try {
            _state.value = EngineState.Generating
            coroutineScope {
                // Watcher: if the collector goes away mid-decode (screen closed,
                // new request), stop native decoding right away. It runs on a
                // different thread because the native thread is blocked decoding.
                val watcher = launch(Dispatchers.Default) {
                    try { awaitCancellation() } finally { if (!finished.get()) NativeBridge.nCancel() }
                }
                try {
                    runGeneration(turns, params) { emit(it) }
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
        } finally {
            _state.value = ready
            busy.set(false)
        }
    }.flowOn(nativeDispatcher)

    private suspend fun runGeneration(
        turns: List<ChatTurn>,
        params: SamplingParams,
        emit: suspend (GenerationEvent) -> Unit,
    ) {
        ensureInit()
        val prompt = render(turns) ?: throw EngineError.TemplateFailed()
        val samplerRc = NativeBridge.nSetSampling(
            params.temperature, params.topK, params.topP, params.minP,
            params.repeatPenalty, params.repeatLastN, params.seed,
        )
        if (samplerRc != 0) throw EngineError.DecodeFailed()

        val rc = NativeBridge.nStartCompletion(prompt, params.maxNewTokens)
        when (rc) {
            0 -> Unit
            -3 -> throw EngineError.PromptTooLong()
            -6 -> { emit(GenerationEvent.Finished(StopReason.CANCELLED, readStats())); return }
            -4 -> throw EngineError.TemplateFailed()
            else -> throw EngineError.DecodeFailed()
        }

        val filter = StopSequenceFilter(params.stopSequences)
        var stopSeq = false
        while (true) {
            val bytes = NativeBridge.nNextToken() ?: break
            if (bytes.isEmpty()) continue
            val safe = filter.feed(String(bytes, Charsets.UTF_8))
            if (safe.isNotEmpty()) emit(GenerationEvent.Text(safe))
            if (filter.stopped) { stopSeq = true; break }
        }

        val reason = when {
            stopSeq -> StopReason.STOP_SEQUENCE
            else -> when (NativeBridge.nStopReason()) {
                1 -> StopReason.END_OF_TEXT
                2 -> StopReason.MAX_TOKENS
                3 -> StopReason.CONTEXT_FULL
                4 -> StopReason.CANCELLED
                else -> StopReason.ERROR
            }
        }
        if (!stopSeq) filter.finish().takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Text(it)) }
        if (reason == StopReason.ERROR) throw EngineError.DecodeFailed()
        emit(GenerationEvent.Finished(reason, readStats()))
    }

    private fun readStats(): GenerationStats {
        val s = NativeBridge.nStats()
        return GenerationStats(
            promptTokens = s[0].toInt(), promptTokensReused = s[1].toInt(),
            promptMs = s[3], generatedTokens = s[4].toInt(), generatedMs = s[5],
        )
    }

    override fun cancelGeneration() {
        if (busy.get()) NativeBridge.nCancel()
    }

    override fun systemInfo(): String = try {
        NativeBridge.nSystemInfo()
    } catch (e: UnsatisfiedLinkError) {
        Log.e("LlamaEngine", "native library unavailable", e)
        "native library unavailable"
    }

    override fun release() {
        executor.execute {
            try {
                NativeBridge.nFreeContext()
                NativeBridge.nUnloadModel()
                if (initialized) NativeBridge.nShutdown()
            } catch (t: Throwable) {
                Log.e("LlamaEngine", "release failed", t)
            }
        }
        executor.shutdown()
        _state.value = EngineState.Idle
    }
}
