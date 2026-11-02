package app.localai

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import app.localai.data.AppDatabase
import app.localai.data.ChatRepository
import app.localai.data.ModelRepository
import app.localai.data.SettingsRepository
import app.localai.engine.InferenceEngine
import app.localai.engine.LlamaEngine
import java.io.File

/** Hand-wired dependencies: small enough that a DI framework would add more than it saves. */
class AppContainer(private val app: Application) {
    val database: AppDatabase by lazy { Room.databaseBuilder(app, AppDatabase::class.java, "localai.db").build() }
    val settings by lazy { SettingsRepository(app) }
    val models by lazy { ModelRepository(app.contentResolver, database.models(), File(app.filesDir, "models")) }
    val chats by lazy { ChatRepository(database.conversations(), database.messages()) }
    val engine: InferenceEngine by lazy { LlamaEngine(app.applicationInfo.nativeLibraryDir) }

    fun availableMemoryBytes(): Long {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }
}

class LocalAIApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
