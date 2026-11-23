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
    val updatedAt: Long = 0,   // last change on any device (for Drive sync); 0 = never changed since created
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
    private var deleted: Map<String, Long> = emptyMap()   // task id -> when it was deleted (so sync removes it everywhere)
    private var orderChangedAt: Long = 0                   // last drag/move (so sync keeps the newest order)
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "tasks.json")
        file = f
        val state = runCatching { parseState(f.readText()) }.getOrNull()
        _tasks.value = state?.tasks ?: emptyList()
        deleted = state?.deleted ?: emptyMap()
        orderChangedAt = state?.orderChangedAt ?: 0
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
        val prev = _tasks.value
        val next = transform(prev)
        val now = System.currentTimeMillis()

        // Stamp every task that changed, remember deletions and order changes, for Drive sync.
        val prevById = prev.associateBy { it.id }
        val stamped = next.map { t ->
            val p = prevById[t.id]
            if (p == null || p.copy(updatedAt = 0) != t.copy(updatedAt = 0)) t.copy(updatedAt = now) else t
        }
        val nextIds = stamped.map { it.id }.toSet()
        val removed = prev.filter { it.id !in nextIds }.map { it.id }
        deleted = SyncMerge.prune((deleted - nextIds) + removed.associateWith { now }, now)

        // Open tasks keep their order; done tasks go after, most recently done first.
        val ordered = stamped.filter { !it.done } + stamped.filter { it.done }.sortedByDescending { it.doneAt }
        val prevOrder = prev.filter { !it.done && it.id in nextIds }.map { it.id }
        val nextOrder = ordered.filter { !it.done && it.id in prevById }.map { it.id }
        if (prevOrder != nextOrder) orderChangedAt = now

        save(ordered)
        _tasks.value = ordered
        runCatching { TaskWidget.refresh(context.applicationContext) }
    }

    private fun save(list: List<Task>) {
        val f = file!!
        val json = toJson(list, deleted, orderChangedAt)
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) {
            f.writeText(json)
            tmp.delete()
        }
    }

    // ---- Drive sync -------------------------------------------------------

    @Synchronized
    fun syncState(context: Context): SyncState {
        init(context)
        return SyncState(_tasks.value, deleted, orderChangedAt)
    }

    /** Saves the merged result of a sync as-is (no new change stamps). */
    @Synchronized
    fun applySync(context: Context, state: SyncState) {
        init(context)
        val ordered = state.tasks.filter { !it.done } + state.tasks.filter { it.done }.sortedByDescending { it.doneAt }
        deleted = state.deleted
        orderChangedAt = state.orderChangedAt
        save(ordered)
        _tasks.value = ordered
        runCatching { TaskWidget.refresh(context.applicationContext) }
    }

    fun stateJson(state: SyncState): String = toJson(state.tasks, state.deleted, state.orderChangedAt)

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

    fun backupJson(): String = toJson(_tasks.value, deleted, orderChangedAt)

    fun parseBackup(text: String): List<Task> = parseState(text).tasks

    // ---------------------------------------------------------------------

    private fun newId() = UUID.randomUUID().toString()

    fun cleanText(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /** Reads tasks.json, a backup, or the Drive sync file (all the same format). */
    fun parseState(text: String): SyncState {
        val root = JSONObject(text)
        require(root.has("tasks")) { "Not a To-Do file" }
        val del = root.optJSONObject("deleted")
        val deletedMap = buildMap {
            del?.keys()?.forEach { k -> put(k, del.optLong(k)) }
        }
        return SyncState(parse(root), deletedMap, root.optLong("order_changed_at"))
    }

    private fun parse(root: JSONObject): List<Task> {
        val arr = root.optJSONArray("tasks") ?: JSONArray()
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
                updatedAt = o.optLong("updated_at"),
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun toJson(list: List<Task>, deleted: Map<String, Long>, orderChangedAt: Long): String = JSONObject()
        .put("app", "Eric's To-Do")
        .put("version", 2)
        .put("order_changed_at", orderChangedAt)
        .put("deleted", JSONObject().apply { deleted.forEach { (k, v) -> put(k, v) } })
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
                    .put("done_at", it.doneAt)
                    .put("updated_at", it.updatedAt))
            }
        }).toString(2)
}
