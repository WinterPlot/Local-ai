package app.localai.data

import app.localai.engine.ChatTurn
import app.localai.engine.Role
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(private val conversations: ConversationDao, private val messages: MessageDao) {
    fun conversations(query: String): Flow<List<ConversationEntity>> =
        if (query.isBlank()) conversations.observeAll() else conversations.search(escapeLike(query.trim()))

    fun observeMessages(cid: String) = messages.observe(cid)
    fun observeConversation(cid: String) = conversations.observe(cid)

    suspend fun create(title: String = "New conversation"): ConversationEntity {
        val now = System.currentTimeMillis()
        return ConversationEntity(UUID.randomUUID().toString(), title, now, now).also { conversations.upsert(it) }
    }

    suspend fun rename(id: String, title: String) = conversations.rename(id, title.trim().ifEmpty { "Untitled" }, System.currentTimeMillis())
    suspend fun delete(id: String) = conversations.delete(id)
    suspend fun deleteAll() = conversations.deleteAll()

    suspend fun addMessage(m: MessageEntity): Long {
        val id = messages.insert(m)
        conversations.touch(m.conversationId, System.currentTimeMillis())
        return id
    }

    /** Deletes the message with [fromId] and everything after it. */
    suspend fun deleteFrom(cid: String, fromId: Long) = messages.deleteFrom(cid, fromId)

    suspend fun history(cid: String): List<MessageEntity> = messages.list(cid)

    suspend fun title(cid: String): String? = conversations.get(cid)?.title

    companion object {
        /** Escapes LIKE wildcards for the `ESCAPE '\'` clause in the DAO. */
        fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun toTurns(history: List<MessageEntity>): List<ChatTurn> = history.mapNotNull {
            when (it.role) {
                "user" -> ChatTurn(Role.USER, it.content)
                "assistant" -> ChatTurn(Role.ASSISTANT, it.content)
                else -> null
            }
        }

        fun exportMarkdown(title: String, history: List<MessageEntity>): String = buildString {
            append("# ").append(title).append("\n\n")
            for (m in history) {
                append(if (m.role == "user") "**You**" else "**Assistant**").append("\n\n")
                append(m.content.trim()).append("\n\n")
            }
        }
    }
}
