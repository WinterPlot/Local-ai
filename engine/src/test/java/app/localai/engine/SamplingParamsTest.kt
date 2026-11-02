package app.localai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SamplingParamsTest {
    @Test fun defaultsAreValid() = assertNull(SamplingParams().validate())

    @Test fun rejectsOutOfRangeValues() {
        listOf(
            SamplingParams(temperature = -1f), SamplingParams(topP = 0f), SamplingParams(topP = 1.5f),
            SamplingParams(minP = 1f), SamplingParams(repeatPenalty = 0.5f), SamplingParams(maxNewTokens = 0),
            SamplingParams(topK = -1), SamplingParams(stopSequences = listOf("")),
        ).forEach { assertNotNull(it.toString(), it.validate()) }
    }

    @Test fun zeroTemperatureMeansGreedyAndIsValid() = assertNull(SamplingParams(temperature = 0f).validate())

    @Test fun statsComputeRates() {
        val s = GenerationStats(promptTokens = 110, promptTokensReused = 10, promptMs = 500, generatedTokens = 20, generatedMs = 1000)
        assertEquals(20.0, s.tokensPerSecond!!, 0.001)
        assertEquals(200.0, s.promptTokensPerSecond!!, 0.001)
        assertNull(GenerationStats(0, 0, 0, 0, 0).tokensPerSecond)
    }
}
