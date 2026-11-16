package com.eric.todo.data

import android.content.Context
import com.eric.todo.widget.TaskWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val BUSINESS = "Business"
const val PERSONAL = "Personal"
val LISTS = listOf(BUSINESS, PERSONAL)

data class Task(
    val id: String,
    val text: String,
    val list: String,          // BUSINESS or PERSONAL
    val done: Boolean = false,
    val count: Int = 1,        // how many times this task has been added (duplicates merged)
    val urgent: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val doneAt: Long = 0,
)

/** What an import should do with one incoming task. */
sealed interface ImportAction {
    data class AddNew(val text: String, val list: String, val urgent: Boolean, val count: Int) : ImportAction
    data class Merge(val intoId: String, val count: Int, val urgent: Boolean, val reopen: Boolean) : ImportAction
}

/**
 * All tasks, both lists, saved as tasks.json in the app's private storage.
 * List order = the order you see on screen (top = first).
 */
object TaskStore {

    private var file: File? = null
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "tasks.json")
        file = f
        _tasks.value = runCatching { parse(f.readText()) }.getOrDefault(emptyList())
    }

    fun all(context: Context): List<Task> {
        init(context)
        return _tasks.value
    }

    fun open(context: Context, list: String): List<Task> =
        all(context).filter { it.list == list && !it.done }

    @Synchronized
    private fun update(context: Context, transform: (List<Task>) -> List<Task>) {
        init(context)
        val next = transform(_tasks.value)
        // Open tasks keep their order; done tasks go after, most recently done first.
        val ordered = next.filter { !it.done } + next.filter { it.done }.sortedByDescending { it.doneAt }
        val f = file!!
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(toJson(ordered))
        if (!tmp.renameTo(f)) {
            f.writeText(toJson(ordered))
            tmp.delete()
        }
        _tasks.value = ordered
        runCatching { TaskWidget.refresh(context.applicationContext) }
    }

    fun add(context: Context, text: String, list: String, urgent: Boolean = false) {
        val t = cleanText(text)
        if (t.isEmpty()) return
        update(context) { listOf(Task(newId(), t, list, urgent = urgent)) + it }   // new ones go on top
    }

    /** Applies the reviewed import. New tasks go to the top of their list, in the order given. */
    fun applyImport(context: Context, actions: List<ImportAction>) = update(context) { current ->
        var list = current
        for (a in actions) {
            if (a is ImportAction.Merge) {
                list = list.map {
                    if (it.id != a.intoId) it
                    else it.copy(
                        count = it.count + a.count,
                        urgent = it.urgent || a.urgent,
                        done = if (a.reopen) false else it.done,
                        doneAt = if (a.reopen) 0 else it.doneAt,
                    )
                }
            }
        }
        val added = actions.filterIsInstance<ImportAction.AddNew>()
            .map { Task(newId(), cleanText(it.text), it.list, urgent = it.urgent, count = it.count) }
            .filter { it.text.isNotEmpty() }
        added + list
    }

    fun setDone(context: Context, id: String, done: Boolean) = update(context) { list ->
        val item = list.firstOrNull { it.id == id } ?: return@update list
        val rest = list.filter { it.id != id }
        if (done) rest + item.copy(done = true, doneAt = System.currentTimeMillis())
        else {
            // Back to the bottom of its open list
            val reopened = item.copy(done = false, doneAt = 0)
            rest.filter { !it.done } + reopened + rest.filter { it.done }
        }
    }

    fun toggle(context: Context, id: String) {
        val t = all(context).firstOrNull { it.id == id } ?: return
        setDone(context, id, !t.done)
    }

    fun edit(context: Context, id: String, text: String, urgent: Boolean, count: Int) = update(context) { list ->
        list.map {
            if (it.id == id) it.copy(text = cleanText(text).ifEmpty { it.text }, urgent = urgent, count = count.coerceAtLeast(1))
            else it
        }
    }

    fun moveToList(context: Context, id: String, newList: String) = update(context) { list ->
        val item = list.firstOrNull { it.id == id } ?: return@update list
        listOf(item.copy(list = newList)) + list.filter { it.id != id }   // top of the other list
    }

    fun delete(context: Context, id: String) = update(context) { list -> list.filter { it.id != id } }

    fun restore(context: Context, task: Task, index: Int) = update(context) { list ->
        list.toMutableList().apply { add(index.coerceIn(0, size), task) }
    }

    fun clearDone(context: Context, list: String) = update(context) { all -> all.filter { !(it.done && it.list == list) } }

    /** Merges task [dropId] into [keepId]: counts add up, the text of [keepId] stays. */
    fun merge(context: Context, keepId: String, dropId: String, keepText: String? = null) = update(context) { list ->
        val drop = list.firstOrNull { it.id == dropId } ?: return@update list
        list.filter { it.id != dropId }.map {
            if (it.id == keepId) it.copy(
                count = it.count + drop.count,
                urgent = it.urgent || drop.urgent,
                text = keepText?.let(::cleanText)?.ifEmpty { null } ?: it.text,
            ) else it
        }
    }

    /** Sets the order of one list's open tasks (from drag and drop). */
    fun reorder(context: Context, listName: String, openIds: List<String>) = update(context) { all ->
        val byId = all.associateBy { it.id }
        val ordered = openIds.mapNotNull { byId[it] }.filter { !it.done && it.list == listName }
        val missing = all.filter { !it.done && it.list == listName && it.id !in openIds }
        val others = all.filter { it.done || it.list != listName }
        ordered + missing + others
    }

    enum class Move { TOP, BOTTOM }

    fun move(context: Context, id: String, move: Move) = update(context) { all ->
        val item = all.firstOrNull { it.id == id } ?: return@update all
        val rest = all.filter { it.id != id }
        when (move) {
            Move.TOP -> listOf(item) + rest
            Move.BOTTOM -> rest.filter { !it.done } + item + rest.filter { it.done }
        }
    }

    fun replaceAll(context: Context, tasks: List<Task>) = update(context) { tasks }

    fun shareText(listName: String): String = buildString {
        val open = _tasks.value.filter { it.list == listName && !it.done }
        append(listName.uppercase()).append(" TO-DO\n")
        open.forEachIndexed { i, t ->
            append("${i + 1}. ☐ ").append(t.text)
            if (t.urgent) append(" – URGENT")
            append("\n")
        }
    }

    // ---- Backup ---------------------------------------------------------

    fun backupJson(): String = toJson(_tasks.value)

    fun parseBackup(text: String): List<Task> = parse(text)

    // ---------------------------------------------------------------------

    private fun newId() = UUID.randomUUID().toString()

    fun cleanText(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun parse(text: String): List<Task> {
        val arr = JSONObject(text).optJSONArray("tasks") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                id = o.optString("id").ifEmpty { newId() },
                text = o.optString("text"),
                list = o.optString("list").let { if (it == PERSONAL) PERSONAL else BUSINESS },
                done = o.optBoolean("done"),
                count = o.optInt("count", 1).coerceAtLeast(1),
                urgent = o.optBoolean("urgent"),
                createdAt = o.optLong("created_at"),
                doneAt = o.optLong("done_at"),
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun toJson(list: List<Task>): String = JSONObject()
        .put("app", "Eric's To-Do")
        .put("version", 1)
        .put("tasks", JSONArray().apply {
            list.forEach {
                put(JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("list", it.list)
                    .put("done", it.done)
                    .put("count", it.count)
                    .put("urgent", it.urgent)
                    .put("created_at", it.createdAt)
                    .put("done_at", it.doneAt))
            }
        }).toString(2)
}
