package app.localai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.localai.AppContainer
import app.localai.data.AppSettings
import app.localai.data.ConversationEntity
import app.localai.data.ImportEvent
import app.localai.data.ModelEntity
import app.localai.data.ModelImportException
import app.localai.engine.EngineError
import app.localai.engine.EngineState
import app.localai.engine.LoadedModelInfo
import app.localai.engine.MemoryEstimate
import app.localai.engine.MemoryEstimator
import app.localai.engine.MemoryVerdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUi(val active: Boolean = false, val copied: Long = 0, val total: Long = 0)

data class LoadAdvice(val model: ModelEntity, val estimate: MemoryEstimate, val verdict: MemoryVerdict, val kvKnown: Boolean, val availableBytes: Long)

class AppViewModel(private val c: AppContainer) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationEntity>> =
        _query.flatMapLatest { c.chats.conversations(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val models: StateFlow<List<ModelEntity>> =
        c.models.models.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val engineState: StateFlow<EngineState> = c.engine.state

    private val _import = MutableStateFlow(ImportUi())
    val importUi: StateFlow<ImportUi> = _import.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private var importJob: Job? = null

    fun setQuery(q: String) { _query.value = q }

    fun systemInfo(): String = c.engine.systemInfo()

    suspend fun newConversation(): String = c.chats.create().id

    fun renameConversation(id: String, title: String) = viewModelScope.launch { c.chats.rename(id, title) }
    fun deleteConversation(id: String) = viewModelScope.launch { c.chats.delete(id) }
    fun deleteAllConversations() = viewModelScope.launch { c.chats.deleteAll(); _messages.tryEmit("All conversations deleted") }

    fun importModel(uri: android.net.Uri) {
        if (_import.value.active) return
        importJob = viewModelScope.launch {
            _import.value = ImportUi(active = true)
            try {
                c.models.import(uri).collect { ev ->
                    when (ev) {
                        is ImportEvent.Progress -> _import.update { it.copy(copied = ev.copiedBytes, total = ev.totalBytes) }
                        is ImportEvent.Done -> _messages.tryEmit("Imported ${ev.model.displayName}")
                    }
                }
            } catch (e: ModelImportException) {
                _messages.tryEmit(e.message ?: "Import failed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _messages.tryEmit("Import cancelled"); throw e
            } finally {
                _import.value = ImportUi()
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    /** Builds the pre-load memory advice (all numbers are estimates). */
    fun adviceFor(m: ModelEntity): LoadAdvice {
        val ctx = minOf(settings.value.contextSize, (m.contextTrain ?: Long.MAX_VALUE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val info = LoadedModelInfo(
            nEmbd = m.embeddingLength?.toInt() ?: 0, nHead = m.headCount?.toInt() ?: 0,
            nHeadKv = m.headCountKv?.toInt() ?: 0, nLayer = m.blockCount?.toInt() ?: 0,
        )
        val est = MemoryEstimator.estimate(m.sizeBytes, info, ctx)
        val avail = c.availableMemoryBytes()
        return LoadAdvice(m, est, MemoryEstimator.verdict(est, avail), MemoryEstimator.kvCacheBytes(info, ctx) != null, avail)
    }

    fun loadModel(m: ModelEntity) {
        viewModelScope.launch {
            if (!c.models.isAvailable(m)) {
                _messages.tryEmit("The model file is missing from app storage. Remove it and import it again.")
                return@launch
            }
            try {
                c.engine.loadModel(m.path, settings.value.contextConfig())
                c.settings.setActiveModel(m.id)
                _messages.tryEmit("Loaded ${m.displayName}")
            } catch (e: EngineError) {
                _messages.tryEmit(e.message ?: "Loading failed")
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            try { c.engine.unloadModel() } catch (e: EngineError) { _messages.tryEmit(e.message ?: "Unload failed") }
        }
    }

    fun deleteModel(m: ModelEntity) {
        viewModelScope.launch {
            val s = c.engine.state.value
            if (s is EngineState.Ready && settings.value.activeModelId == m.id) {
                try { c.engine.unloadModel() } catch (e: EngineError) { _messages.tryEmit("Stop generation before removing the model"); return@launch }
            }
            if (settings.value.activeModelId == m.id) c.settings.setActiveModel(null)
            c.models.remove(m)
            _messages.tryEmit("Removed ${m.displayName} from the app. Your original file was not touched.")
        }
    }

    fun deleteAllModels() {
        viewModelScope.launch {
            try { c.engine.unloadModel() } catch (_: EngineError) {}
            c.settings.setActiveModel(null)
            c.models.removeAll()
            _messages.tryEmit("App-managed model copies deleted")
        }
    }

    /** Validates then saves. Returns an error text, or null on success. */
    suspend fun saveSettings(s: AppSettings): String? {
        s.sampling.validate()?.let { return it }
        if (s.contextSize < 256) return "Context size must be at least 256"
        if (s.threads !in 1..16) return "Threads must be between 1 and 16"
        c.settings.save(s)
        return null
    }
}
