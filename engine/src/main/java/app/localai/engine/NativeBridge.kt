package app.localai.engine

/**
 * Raw JNI surface of liblocalai-native. Not thread-safe except [nCancel]:
 * [LlamaEngine] serialises every other call on one dedicated thread.
 * Text crosses JNI as UTF-8 byte arrays (never jstring) so emoji survive.
 */
internal object NativeBridge {
    init { System.loadLibrary("localai-native") }

    external fun nInit(nativeLibDir: String)
    external fun nShutdown()
    external fun nSystemInfo(): String
    external fun nLoadModel(path: String, useMmap: Boolean): Int
    external fun nModelInfo(): ByteArray?
    external fun nCreateContext(nCtx: Int, nThreads: Int, nBatch: Int): Int
    external fun nContextSize(): Int
    external fun nTemplateIsExplicit(): Boolean
    external fun nRenderPrompt(roles: Array<String>, contents: Array<ByteArray>): ByteArray?
    external fun nCountTokens(text: ByteArray): Int
    external fun nSetSampling(
        temp: Float, topK: Int, topP: Float, minP: Float,
        repeatPenalty: Float, penaltyLastN: Int, seed: Long,
    ): Int
    external fun nStartCompletion(prompt: ByteArray, maxNew: Int): Int
    external fun nNextToken(): ByteArray?
    external fun nStopReason(): Int
    external fun nCancel()
    external fun nStats(): LongArray
    external fun nResetContext()
    external fun nFreeContext()
    external fun nUnloadModel()
}
