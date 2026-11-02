package app.localai.engine

/**
 * Rough RAM estimate for loading a model. These are ESTIMATES: real use
 * depends on quantisation, backend buffers and the OS. Labelled as such in UI.
 */
data class MemoryEstimate(
    val weightsBytes: Long,
    val kvCacheBytes: Long,
    val overheadBytes: Long,
) {
    val totalBytes: Long get() = weightsBytes + kvCacheBytes + overheadBytes
}

enum class MemoryVerdict { OK, TIGHT, LIKELY_TO_FAIL, UNKNOWN }

object MemoryEstimator {
    private const val F16_BYTES = 2L
    private const val OVERHEAD_FLOOR = 256L * 1024 * 1024

    /**
     * KV cache (f16): 2 (K and V) * layers * ctx * (embd / heads * kvHeads) * 2 bytes.
     * Returns null when the model did not report the needed dimensions.
     */
    fun kvCacheBytes(info: LoadedModelInfo, contextSize: Int): Long? {
        if (info.nLayer <= 0 || info.nEmbd <= 0 || info.nHead <= 0 || info.nHeadKv <= 0) return null
        val embdKvGqa = info.nEmbd.toLong() / info.nHead * info.nHeadKv
        return 2L * info.nLayer * contextSize * embdKvGqa * F16_BYTES
    }

    /** Estimate from GGUF header data before loading (file size ≈ weights). */
    fun estimate(fileSizeBytes: Long, info: LoadedModelInfo?, contextSize: Int): MemoryEstimate {
        val kv = info?.let { kvCacheBytes(it, contextSize) } ?: 0L
        val overhead = maxOf(OVERHEAD_FLOOR, (fileSizeBytes * 0.05).toLong())
        return MemoryEstimate(fileSizeBytes, kv, overhead)
    }

    fun verdict(estimate: MemoryEstimate, availableBytes: Long): MemoryVerdict = when {
        availableBytes <= 0 -> MemoryVerdict.UNKNOWN
        estimate.totalBytes > availableBytes * 0.9 -> MemoryVerdict.LIKELY_TO_FAIL
        estimate.totalBytes > availableBytes * 0.6 -> MemoryVerdict.TIGHT
        else -> MemoryVerdict.OK
    }
}
