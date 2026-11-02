package app.localai.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.localai.engine.GgufHeader
import app.localai.engine.GgufSummary
import app.localai.engine.NotGgufException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface ImportEvent {
    data class Progress(val copiedBytes: Long, val totalBytes: Long) : ImportEvent
    data class Done(val model: ModelEntity) : ImportEvent
}

class ModelImportException(message: String) : IOException(message)

class ModelRepository(
    private val resolver: ContentResolver,
    private val dao: ModelDao,
    private val modelsDir: File,
) {
    val models: Flow<List<ModelEntity>> = dao.observeAll()

    suspend fun get(id: String): ModelEntity? = dao.get(id)

    /** True when the app-managed copy still exists (it can vanish if the user clears storage). */
    fun isAvailable(m: ModelEntity): Boolean = File(m.path).let { it.isFile && it.length() == m.sizeBytes }

    /**
     * Validates the GGUF header WITHOUT copying, then streams the file into
     * app-private storage with progress. Never buffers the model in memory.
     * Cancelling the collector deletes the partial copy.
     */
    fun import(uri: Uri): Flow<ImportEvent> = flow {
        val (name, size) = queryNameAndSize(uri)

        val summary: GgufSummary = try {
            (resolver.openInputStream(uri) ?: throw ModelImportException("The file could not be opened.")).use { GgufHeader.read(it) }
        } catch (e: NotGgufException) {
            throw ModelImportException(e.message ?: "Not a valid GGUF file.")
        } catch (e: SecurityException) {
            throw ModelImportException("Permission to read this file was denied. Pick it again.")
        }

        modelsDir.mkdirs()
        if (size > 0 && modelsDir.usableSpace < size + 64L * 1024 * 1024) {
            throw ModelImportException("Not enough free storage: this model needs ${size / (1024 * 1024)} MB.")
        }

        val id = UUID.randomUUID().toString()
        val target = File(modelsDir, "$id.gguf")
        var copied = 0L
        try {
            (resolver.openInputStream(uri) ?: throw ModelImportException("The file could not be opened.")).use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var lastEmit = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastEmit >= 8L shl 20) { lastEmit = copied; emit(ImportEvent.Progress(copied, size)) }
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            if (t is IOException && t !is ModelImportException) throw ModelImportException("Copying failed: ${t.message ?: "storage error"}")
            throw t
        }

        val entity = ModelEntity(
            id = id,
            displayName = summary.name?.takeIf { it.isNotBlank() } ?: name.removeSuffix(".gguf"),
            originalFileName = name,
            path = target.absolutePath,
            sizeBytes = copied,
            architecture = summary.architecture,
            quantization = summary.quantization,
            contextTrain = summary.contextLength,
            blockCount = summary.blockCount,
            embeddingLength = summary.embeddingLength,
            headCount = summary.headCount,
            headCountKv = summary.headCountKv,
            hasChatTemplate = summary.hasChatTemplate,
            addedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        emit(ImportEvent.Done(entity))
    }.flowOn(Dispatchers.IO)

    /** Removes the library entry and the app-managed copy only. */
    suspend fun remove(m: ModelEntity) {
        File(m.path).delete()
        dao.delete(m)
    }

    suspend fun removeAll() {
        dao.list().forEach { remove(it) }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        return name to size
    }
}
