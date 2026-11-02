package app.localai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryEstimatorTest {
    private val info = LoadedModelInfo(nEmbd = 2048, nHead = 32, nHeadKv = 8, nLayer = 24)

    @Test fun kvCacheFormula() {
        // 2 * 24 layers * 1000 ctx * (2048/32*8 = 512) * 2 bytes
        assertEquals(2L * 24 * 1000 * 512 * 2, MemoryEstimator.kvCacheBytes(info, 1000))
    }

    @Test fun unknownDimensionsGiveNull() = assertNull(MemoryEstimator.kvCacheBytes(LoadedModelInfo(), 1000))

    @Test fun verdictThresholds() {
        val est = MemoryEstimate(weightsBytes = 800, kvCacheBytes = 100, overheadBytes = 100) // 1000
        assertEquals(MemoryVerdict.OK, MemoryEstimator.verdict(est, 2000))
        assertEquals(MemoryVerdict.TIGHT, MemoryEstimator.verdict(est, 1400))
        assertEquals(MemoryVerdict.LIKELY_TO_FAIL, MemoryEstimator.verdict(est, 1000))
        assertEquals(MemoryVerdict.UNKNOWN, MemoryEstimator.verdict(est, 0))
    }
}
