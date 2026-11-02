package app.localai.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.localai.engine.ContextConfig
import app.localai.engine.SamplingParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val sampling: SamplingParams = SamplingParams(),
    val contextSize: Int = 4096,
    val threads: Int = ContextConfig.defaultThreads(),
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val activeModelId: String? = null,
) {
    fun contextConfig() = ContextConfig(contextSize = contextSize, threads = threads)

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful, honest assistant. Answer clearly and concisely."
        val CONTEXT_CHOICES = listOf(1024, 2048, 4096, 8192)
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val temperature = doublePreferencesKey("temperature")
        val topK = intPreferencesKey("top_k")
        val topP = doublePreferencesKey("top_p")
        val minP = doublePreferencesKey("min_p")
        val repeat = doublePreferencesKey("repeat_penalty")
        val repeatLastN = intPreferencesKey("repeat_last_n")
        val seed = longPreferencesKey("seed")
        val maxNew = intPreferencesKey("max_new_tokens")
        val stops = stringPreferencesKey("stop_sequences")
        val ctx = intPreferencesKey("context_size")
        val threads = intPreferencesKey("threads")
        val system = stringPreferencesKey("system_prompt")
        val activeModel = stringPreferencesKey("active_model_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            sampling = SamplingParams(
                temperature = p[K.temperature]?.toFloat() ?: d.sampling.temperature,
                topK = p[K.topK] ?: d.sampling.topK,
                topP = p[K.topP]?.toFloat() ?: d.sampling.topP,
                minP = p[K.minP]?.toFloat() ?: d.sampling.minP,
                repeatPenalty = p[K.repeat]?.toFloat() ?: d.sampling.repeatPenalty,
                repeatLastN = p[K.repeatLastN] ?: d.sampling.repeatLastN,
                seed = p[K.seed] ?: d.sampling.seed,
                maxNewTokens = p[K.maxNew] ?: d.sampling.maxNewTokens,
                stopSequences = p[K.stops]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList(),
            ),
            contextSize = p[K.ctx] ?: d.contextSize,
            threads = p[K.threads] ?: d.threads,
            systemPrompt = p[K.system] ?: d.systemPrompt,
            activeModelId = p[K.activeModel],
        )
    }

    /** Persists [s]. Callers must validate first ([SamplingParams.validate]). */
    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[K.temperature] = s.sampling.temperature.toDouble()
            p[K.topK] = s.sampling.topK
            p[K.topP] = s.sampling.topP.toDouble()
            p[K.minP] = s.sampling.minP.toDouble()
            p[K.repeat] = s.sampling.repeatPenalty.toDouble()
            p[K.repeatLastN] = s.sampling.repeatLastN
            p[K.seed] = s.sampling.seed
            p[K.maxNew] = s.sampling.maxNewTokens
            p[K.stops] = s.sampling.stopSequences.joinToString("\n")
            p[K.ctx] = s.contextSize
            p[K.threads] = s.threads
            p[K.system] = s.systemPrompt
            setOrRemove(p, K.activeModel, s.activeModelId)
        }
    }

    suspend fun setActiveModel(id: String?) {
        context.dataStore.edit { setOrRemove(it, K.activeModel, id) }
    }

    private fun setOrRemove(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, v: String?) {
        if (v == null) p.remove(key) else p[key] = v
    }
}
