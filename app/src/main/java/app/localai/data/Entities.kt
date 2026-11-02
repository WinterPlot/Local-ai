package app.localai.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** "user" or "assistant" */
    val role: String,
    val content: String,
    val createdAt: Long,
    /** StopReason name for assistant messages, null otherwise. */
    val stopReason: String? = null,
    val generatedTokens: Int? = null,
    val tokensPerSecond: Double? = null,
)

/** A model file copied into app-managed storage. The user's original file is never touched. */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val originalFileName: String,
    /** Absolute path inside the app's private storage. */
    val path: String,
    val sizeBytes: Long,
    val architecture: String?,
    val quantization: String?,
    val contextTrain: Long?,
    val blockCount: Long?,
    val embeddingLength: Long?,
    val headCount: Long?,
    val headCountKv: Long?,
    val hasChatTemplate: Boolean,
    val addedAt: Long,
)
