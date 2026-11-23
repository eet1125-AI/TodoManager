package com.eric.todo.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.eric.todo.data.Settings
import com.eric.todo.data.SyncMerge
import com.eric.todo.data.SyncState
import com.eric.todo.data.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Sync through one file in Google Drive. Every phone/tablet points at the same
 * file (picked once with Android's file picker, so no Google sign-in setup is
 * needed in this app). Sync = read the file, merge with this device, write the
 * result back to the file and to this device.
 */
object DriveSync {

    const val FILE_NAME = "Eric-ToDo-sync.json"

    data class Result(val added: Int, val updated: Int, val removed: Int) {
        val message: String
            get() = if (added + updated + removed == 0) "Synced – already up to date"
            else buildList {
                if (added > 0) add("$added new")
                if (updated > 0) add("$updated updated")
                if (removed > 0) add("$removed removed")
            }.joinToString(", ", prefix = "Synced: ")
    }

    private val mutex = Mutex()

    fun isSetUp(context: Context) = Settings.get(context).values.value.syncUri.isNotBlank()

    /** Remembers the Drive file and keeps permission to it across restarts. */
    fun connect(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: FILE_NAME
        Settings.get(context).setSync(uri.toString(), name)
    }

    fun disconnect(context: Context) {
        val s = Settings.get(context)
        val uri = s.values.value.syncUri
        if (uri.isNotBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        s.setSync("", "")
    }

    suspend fun sync(context: Context): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = Settings.get(context)
            val uriText = settings.values.value.syncUri
            require(uriText.isNotBlank()) { "Drive sync isn't set up" }
            val uri = Uri.parse(uriText)
            val resolver = context.contentResolver

            val remoteText = try {
                resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
            } catch (e: SecurityException) {
                throw IllegalStateException("Lost access to the Drive file. Settings → Drive sync → Choose file again.")
            } catch (e: FileNotFoundException) {
                throw IllegalStateException("The Drive sync file was moved or deleted. Settings → Drive sync → Choose file again.")
            }
            val remote = if (remoteText.isBlank()) SyncState(emptyList(), emptyMap(), 0)
            else runCatching { TaskStore.parseState(remoteText) }.getOrElse {
                throw IllegalStateException("That Drive file isn't a To-Do sync file. Choose $FILE_NAME.")
            }

            val local = TaskStore.syncState(context)
            val merged = SyncMerge.merge(local, remote)
            val json = TaskStore.stateJson(merged)

            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Couldn't write to the Drive file")
            out.use { it.write(json.toByteArray()) }

            TaskStore.applySync(context, merged)
            settings.setLastSync(System.currentTimeMillis())

            val before = local.tasks.associateBy { it.id }
            val after = merged.tasks.associateBy { it.id }
            Result(
                added = after.keys.count { it !in before },
                updated = after.count { (id, t) -> before[id]?.let { it != t } == true },
                removed = before.keys.count { it !in after },
            )
        }
    }
}
