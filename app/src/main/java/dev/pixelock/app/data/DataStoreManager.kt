package dev.pixelock.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.pixelock.app.data.models.Settings
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private object SettingsSerializer : Serializer<Settings> {
    private val gson = Gson()
    override val defaultValue = Settings()

    override suspend fun readFrom(input: InputStream): Settings {
        return try {
            gson.fromJson(input.readBytes().decodeToString(), Settings::class.java)
                ?: throw CorruptionException("Empty settings")
        } catch (e: JsonParseException) {
            throw CorruptionException("Unreadable settings", e)
        }
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray())
    }
}

/** The only door to settings. Owns the one multi process DataStore for the file. */
class DataStoreManager(context: Context) {

    private val store = instance(context.applicationContext)

    val settings: Flow<Settings> get() = store.data

    suspend fun update(transform: (Settings) -> Settings): Settings =
        store.updateData { transform(it) }

    private companion object {
        @Volatile private var INSTANCE: DataStore<Settings>? = null

        fun instance(context: Context): DataStore<Settings> =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiProcessDataStoreFactory.create(
                    serializer = SettingsSerializer,
                    // A broken file falls back to defaults: nothing blocked, nothing stuck.
                    corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
                    produceFile = { File(context.filesDir, "settings.json") }
                ).also { INSTANCE = it }
            }
    }
}
