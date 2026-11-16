package com.eric.todo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eric.todo.ai.AiException
import com.eric.todo.ai.Gemini
import com.eric.todo.data.ImportAction
import com.eric.todo.data.Settings
import com.eric.todo.data.Task
import com.eric.todo.data.TaskStore
import com.eric.todo.importer.Dupes
import com.eric.todo.importer.Found
import com.eric.todo.importer.Ocr
import com.eric.todo.importer.TextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Choice { ADD, MERGE, REOPEN, SKIP }

/** One incoming task on the review screen. */
data class ReviewItem(
    val key: Int,
    val text: String,
    val list: String,
    val urgent: Boolean,
    val count: Int,             // times it showed up in these notes
    val checkedOff: Boolean,    // shown as done in the notes
    val matchId: String?,       // existing task it duplicates
    val matchText: String?,
    val matchDone: Boolean,
    val matchCount: Int,
    val sure: Boolean,          // exact/near-exact duplicate (vs "looks similar")
    val choice: Choice,
)

sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val message: String) : ImportState
    data class Review(val items: List<ReviewItem>, val note: String?) : ImportState
    data class Failed(val message: String) : ImportState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val settings = Settings.get(app)

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    /** Set when another app shares text/photos in; the UI opens the import screen. */
    private val _openImport = MutableStateFlow(false)
    val openImport: StateFlow<Boolean> = _openImport.asStateFlow()
    fun importOpened() { _openImport.value = false }

    init {
        TaskStore.init(app)
    }

    fun reset() { _import.value = ImportState.Idle }

    fun sharedIn(text: String?, images: List<Uri>, defaultList: String) {
        _openImport.value = true
        when {
            images.isNotEmpty() -> analyzePhotos(images, defaultList)
            !text.isNullOrBlank() -> analyzeText(text, defaultList)
        }
    }

    fun analyzeText(raw: String, defaultList: String, allowAi: Boolean = true) {
        if (raw.isBlank()) return
        viewModelScope.launch {
            val existing = TaskStore.all(ctx)
            var note: String? = null
            if (allowAi && settings.aiReady) {
                _import.value = ImportState.Working("AI is reading your notes…")
                try {
                    val tasks = withContext(Dispatchers.IO) { gemini().extractTasks(raw, emptyList(), existing, defaultList) }
                    _import.value = ImportState.Review(fromAi(tasks, existing), "Sorted by AI. Check it over, then tap Add.")
                    return@launch
                } catch (e: Exception) {
                    note = "AI didn't work (${e.message}). Used basic reading instead."
                }
            }
            _import.value = ImportState.Working("Reading your notes…")
            val found = withContext(Dispatchers.Default) { TextParser.parse(raw) }
            showLocal(found, existing, defaultList, note)
        }
    }

    fun analyzePhotos(uris: List<Uri>, defaultList: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val existing = TaskStore.all(ctx)
            var note: String? = null
            if (settings.aiReady) {
                _import.value = ImportState.Working("AI is reading ${if (uris.size == 1) "the photo" else "${uris.size} photos"}…")
                try {
                    val tasks = withContext(Dispatchers.IO) {
                        val jpegs = uris.map { Ocr.jpegForUpload(ctx, it) }
                        gemini().extractTasks("", jpegs, existing, defaultList)
                    }
                    _import.value = ImportState.Review(fromAi(tasks, existing), "Read by AI. Check it over, then tap Add.")
                    return@launch
                } catch (e: Exception) {
                    note = "AI didn't work (${e.message}). Read the photo on the phone instead."
                }
            }
            _import.value = ImportState.Working("Reading the photo…")
            try {
                val text = uris.map { Ocr.read(ctx, it) }.joinToString("\n\n")
                val found = withContext(Dispatchers.Default) { TextParser.parse(TextParser.joinWrappedLines(text)) }
                if (found.isEmpty()) {
                    _import.value = ImportState.Failed(
                        "No text found in the photo. Try a closer, brighter, straight-on picture." +
                            if (!settings.aiReady) " Handwriting reads much better with AI turned on (Settings)." else ""
                    )
                    return@launch
                }
                showLocal(found, existing, defaultList,
                    note ?: if (settings.values.value.geminiKey.isBlank()) "Read on the phone. For handwriting, add a free AI key in Settings." else null)
            } catch (e: Exception) {
                _import.value = ImportState.Failed("Couldn't read the photo: ${e.message}")
            }
        }
    }

    private fun gemini() = settings.values.value.let { Gemini(it.geminiKey, it.geminiModel) }

    // ---- Building the review list -------------------------------------

    private fun showLocal(found: List<Found>, existing: List<Task>, defaultList: String, note: String?) {
        if (found.isEmpty()) {
            _import.value = ImportState.Failed("No tasks found in that text.")
            return
        }
        // 1) Combine repeats inside the new notes.
        data class Group(val f: Found, var count: Int)
        val groups = mutableListOf<Group>()
        for (f in found) {
            val m = Dupes.bestMatch(f.text, groups.map { it.f.text }, Dupes.SAME)
            if (m != null) {
                val g = groups[m.index]
                g.count++
                if (f.urgent && !g.f.urgent) groups[m.index] = Group(g.f.copy(urgent = true), g.count)
            } else groups += Group(f, 1)
        }
        // 2) Compare with what's already in the app (open tasks first, then done ones).
        val ordered = existing.filter { !it.done } + existing.filter { it.done }
        val items = groups.mapIndexed { i, g ->
            val list = g.f.list ?: defaultList
            val sameList = ordered.filter { it.list == list }
            val other = ordered.filter { it.list != list }
            val m = Dupes.bestMatch(g.f.text, sameList.map { it.text })?.let { sameList[it.index] to it.score }
                ?: Dupes.bestMatch(g.f.text, other.map { it.text }, Dupes.SAME)?.let { other[it.index] to it.score }
            review(i, g.f.text, list, g.f.urgent, g.count, g.f.done, m?.first, (m?.second ?: 0.0) >= Dupes.SAME)
        }
        _import.value = ImportState.Review(items, note)
    }

    private fun fromAi(tasks: List<com.eric.todo.ai.AiTask>, existing: List<Task>): List<ReviewItem> {
        if (tasks.isEmpty()) throw AiException("no tasks found")
        val byId = existing.associateBy { it.id }
        val ordered = existing.filter { !it.done } + existing.filter { it.done }
        // The AI is told to combine repeats, but double-check anything identical.
        val seen = mutableMapOf<String, Int>()
        val out = mutableListOf<ReviewItem>()
        for (t in tasks) {
            val key = Dupes.normalize(t.text)
            val prev = seen[key]
            if (prev != null) {
                out[prev] = out[prev].copy(count = out[prev].count + 1)
                continue
            }
            var match = byId[t.matchId]
            var sure = match != null
            if (match == null) {
                // Back-up check in case the AI missed an obvious repeat.
                Dupes.bestMatch(t.text, ordered.map { it.text })?.let {
                    match = ordered[it.index]
                    sure = it.score >= Dupes.SAME
                }
            }
            seen[key] = out.size
            out += review(out.size, t.text, t.list, t.urgent, 1, t.done, match, sure)
        }
        return out
    }

    private fun review(key: Int, text: String, list: String, urgent: Boolean, count: Int, checked: Boolean, match: Task?, sure: Boolean) =
        ReviewItem(
            key = key, text = text, list = match?.list ?: list, urgent = urgent, count = count,
            checkedOff = checked,
            matchId = match?.id, matchText = match?.text, matchDone = match?.done ?: false,
            matchCount = match?.count ?: 0,
            sure = sure,
            choice = when {
                match != null && match.done -> Choice.SKIP      // already finished it
                match != null -> Choice.MERGE
                checked -> Choice.SKIP                          // checked off in the notes
                else -> Choice.ADD
            },
        )

    // ---- Edits on the review screen -----------------------------------

    private fun updateItem(key: Int, f: (ReviewItem) -> ReviewItem) {
        val s = _import.value as? ImportState.Review ?: return
        _import.value = s.copy(items = s.items.map { if (it.key == key) f(it) else it })
    }

    fun setChoice(key: Int, c: Choice) = updateItem(key) { it.copy(choice = c) }
    fun setText(key: Int, text: String) = updateItem(key) { it.copy(text = text) }
    fun setList(key: Int, list: String) = updateItem(key) { it.copy(list = list) }
    fun toggleUrgent(key: Int) = updateItem(key) { it.copy(urgent = !it.urgent) }

    fun setAllDuplicates(c: Choice) {
        val s = _import.value as? ImportState.Review ?: return
        _import.value = s.copy(items = s.items.map {
            if (it.matchId != null && !it.matchDone) it.copy(choice = c) else it
        })
    }

    /** Saves the reviewed tasks. Returns a short summary for a message. */
    fun applyReview(): String {
        val s = _import.value as? ImportState.Review ?: return ""
        val actions = s.items.mapNotNull {
            when (it.choice) {
                Choice.ADD -> ImportAction.AddNew(it.text, it.list, it.urgent, it.count)
                Choice.MERGE -> it.matchId?.let { id -> ImportAction.Merge(id, it.count, it.urgent, reopen = false) }
                    ?: ImportAction.AddNew(it.text, it.list, it.urgent, it.count)
                Choice.REOPEN -> it.matchId?.let { id -> ImportAction.Merge(id, it.count, it.urgent, reopen = true) }
                Choice.SKIP -> null
            }
        }
        TaskStore.applyImport(ctx, actions)
        val added = s.items.count { it.choice == Choice.ADD || (it.choice == Choice.MERGE && it.matchId == null) }
        val merged = s.items.count { (it.choice == Choice.MERGE && it.matchId != null) || it.choice == Choice.REOPEN }
        _import.value = ImportState.Idle
        return buildList {
            if (added > 0) add("$added added")
            if (merged > 0) add("$merged merged")
        }.joinToString(", ").ifEmpty { "Nothing added" }
    }

    // ---- Settings helpers ----------------------------------------------

    private val _keyCheck = MutableStateFlow<String?>(null)
    val keyCheck: StateFlow<String?> = _keyCheck.asStateFlow()

    fun testKey() {
        _keyCheck.value = "Checking…"
        viewModelScope.launch {
            _keyCheck.value = try {
                withContext(Dispatchers.IO) { gemini().check() }
                "✓ Key works"
            } catch (e: Exception) {
                "✗ ${e.message}"
            }
        }
    }
}
