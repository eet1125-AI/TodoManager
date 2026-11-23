package com.eric.todo.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eric.todo.R
import com.eric.todo.data.BUSINESS
import com.eric.todo.data.PERSONAL
import com.eric.todo.data.Task
import com.eric.todo.data.TaskStore
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tasks: List<Task>,
    currentList: String,
    onSwitchList: (String) -> Unit,
    onImport: () -> Unit,
    onDuplicates: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
    onTextSize: (Int) -> Unit,
    onSync: () -> Unit,
    syncing: Boolean,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val storeOpen = tasks.filter { it.list == currentList && !it.done }
    val done = tasks.filter { it.list == currentList && it.done }
    var open by remember { mutableStateOf(storeOpen) }
    LaunchedEffect(storeOpen) { open = storeOpen }

    var newText by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Task?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Smaller text = tighter rows, so more of the list fits on screen.
    val ts = LocalTextSize.current
    val rowMin = (ts * 3.1f).coerceIn(44f, 76f).dp
    val gap = if (ts < 20) 5.dp else 10.dp
    val handlePad = if (ts < 20) 6.dp else 16.dp

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = open.indexOfFirst { it.id == from.key }
        val toIdx = open.indexOfFirst { it.id == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            open = open.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun submit() {
        if (newText.isNotBlank()) {
            TaskStore.add(context, newText, currentList)
            newText = ""
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    fun complete(task: Task) {
        TaskStore.setDone(context, task.id, true)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Done: ${task.text.take(40)}", actionLabel = "UNDO", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                // Put it back where it was
                val idx = open.indexOfFirst { it.id == task.id }.takeIf { it >= 0 }
                TaskStore.setDone(context, task.id, false)
                val ids = TaskStore.open(context, currentList).map { it.id }.toMutableList()
                ids.remove(task.id)
                val at = (idx ?: storeOpen.indexOfFirst { it.id == task.id }.coerceAtLeast(0)).coerceAtMost(ids.size)
                ids.add(at, task.id)
                TaskStore.reorder(context, currentList, ids)
            }
        }
    }

    fun delete(task: Task) {
        val index = TaskStore.all(context).indexOfFirst { it.id == task.id }
        TaskStore.delete(context, task.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Deleted: ${task.text.take(40)}", actionLabel = "UNDO", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) TaskStore.restore(context, task, index)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Eric's To-Do", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    Button(
                        onClick = onImport,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_import), null, Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add notes", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onSync, enabled = !syncing) {
                        if (syncing) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_sync), "Sync with Google Drive", Modifier.size(28.dp))
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", Modifier.size(30.dp)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            BigMenuItem("Sync with Google Drive") { menu = false; onSync() }
                            BigMenuItem("Find duplicates") { menu = false; onDuplicates() }
                            BigMenuItem("Share this list") { menu = false; onShare() }
                            BigMenuItem("Urgent to the top") {
                                menu = false
                                TaskStore.reorder(context, currentList,
                                    open.filter { it.urgent }.map { it.id } + open.filter { !it.urgent }.map { it.id })
                            }
                            BigMenuItem("Smaller text (see more)") { menu = false; onTextSize(-1) }
                            BigMenuItem("Bigger text") { menu = false; onTextSize(1) }
                            BigMenuItem("Settings") { menu = false; onSettings() }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text("Add to ${currentList.lowercase()}…", fontSize = 20.sp) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(
                    onClick = { submit() },
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = listColor(currentList)),
                ) { Icon(Icons.Default.Add, "Add", Modifier.size(36.dp), tint = Color.Black) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListTabs(
                current = currentList,
                businessCount = tasks.count { it.list == BUSINESS && !it.done },
                personalCount = tasks.count { it.list == PERSONAL && !it.done },
                onSelect = onSwitchList,
            )

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.weight(1f),
            ) {
                if (open.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nothing on your ${currentList.lowercase()} list", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Type a task below, or tap Add notes to paste a list or take a picture of one.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(open, key = { it.id }) { task ->
                    ReorderableItem(reorderState, key = task.id) { dragging ->
                        val elevation by animateDpAsState(if (dragging) 16.dp else 0.dp, label = "lift")
                        Card(
                            Modifier
                                .longPressDraggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { TaskStore.reorder(context, currentList, open.map { it.id }) },
                                )
                                .fillMaxWidth()
                                .shadow(elevation, RoundedCornerShape(18.dp))
                                .border(
                                    width = if (dragging) 3.dp else 1.dp,
                                    color = if (dragging) Orange else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(18.dp),
                                ),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Row(
                                Modifier.heightIn(min = rowMin).padding(start = 4.dp, end = 0.dp, top = if (ts < 20) 2.dp else 6.dp, bottom = if (ts < 20) 2.dp else 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Drag handle: touch and drag right away
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Drag to move",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .draggableHandle(
                                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            onDragStopped = { TaskStore.reorder(context, currentList, open.map { it.id }) },
                                        )
                                        .padding(horizontal = 8.dp, vertical = handlePad)
                                        .size(if (ts < 20) 22.dp else 28.dp),
                                )
                                BigCheck(checked = false, color = listColor(currentList)) { complete(task) }
                                Spacer(Modifier.width(12.dp))
                                TaskLabel(task, Modifier.weight(1f).clickable { editing = task })
                                TaskMenu(task, onEdit = { editing = task }, onDelete = { delete(task) })
                            }
                        }
                    }
                }

                if (open.size > 1) {
                    item(key = "hint") {
                        Text(
                            "Drag ≡ (or press and hold a task) to move it. Tap a task to edit.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }

                if (done.isNotEmpty()) {
                    item(key = "done_header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showDone = !showDone }
                                .padding(top = 16.dp, bottom = 4.dp, start = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (showDone) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(30.dp))
                            Text("Done (${done.size})", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (showDone) {
                                TextButton(onClick = { TaskStore.clearDone(context, currentList) }) {
                                    Text("Clear done", fontSize = 17.sp)
                                }
                            }
                        }
                    }
                    if (showDone) {
                        items(done, key = { it.id }) { task ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(Modifier.width(40.dp))
                                BigCheck(checked = true, color = listColor(currentList)) { TaskStore.setDone(context, task.id, false) }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    task.text,
                                    style = taskTextStyle().copy(fontWeight = FontWeight.Normal, textDecoration = TextDecoration.LineThrough),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TaskMenu(task, onEdit = { editing = task }, onDelete = { delete(task) })
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { t ->
        EditDialog(task = t, onDismiss = { editing = null }, onDelete = { editing = null; delete(t) })
    }
}

@Composable
fun BigMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = 19.sp) }, onClick = onClick, modifier = Modifier.heightIn(min = 52.dp))
}

@Composable
private fun ListTabs(current: String, businessCount: Int, personalCount: Int, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(BUSINESS to businessCount, PERSONAL to personalCount).forEach { (name, count) ->
            val selected = name == current
            val color = listColor(name)
            Box(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .background(if (selected) color else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .border(2.dp, color, RoundedCornerShape(16.dp))
                    .clickable { onSelect(name) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${name.uppercase()}  $count",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun BigCheck(checked: Boolean, color: Color, onClick: () -> Unit) {
    val box = (LocalTextSize.current * 1.8f).coerceIn(30f, 44f)
    Box(
        Modifier
            .size(box.dp)
            .background(if (checked) color else Color.Transparent, CircleShape)
            .border(3.dp, color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Default.Check, "Done", tint = Color.Black, modifier = Modifier.size((box * 0.68f).dp))
    }
}

@Composable
fun CountBadge(count: Int) {
    if (count < 2) return
    Box(
        Modifier
            .background(Orange, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text("×$count", color = Color.Black, fontSize = (LocalTextSize.current * 0.75f).coerceIn(12f, 18f).sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun TaskLabel(task: Task, modifier: Modifier) {
    Column(modifier.padding(vertical = if (LocalTextSize.current < 20) 2.dp else 6.dp)) {
        Text(task.text, style = taskTextStyle(), color = MaterialTheme.colorScheme.onSurface)
        if (task.urgent || task.count > 1) {
            Spacer(Modifier.height(if (LocalTextSize.current < 20) 3.dp else 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.urgent) {
                    Box(Modifier.background(UrgentRed, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                        Text("URGENT", color = Color.Black, fontSize = (LocalTextSize.current * 0.65f).coerceIn(11f, 15f).sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                CountBadge(task.count)
            }
        }
    }
}

@Composable
private fun TaskMenu(task: Task, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val other = if (task.list == BUSINESS) PERSONAL else BUSINESS
    Box {
        val compact = LocalTextSize.current < 20
        IconButton(onClick = { open = true }, modifier = Modifier.size(if (compact) 40.dp else 52.dp)) {
            Icon(Icons.Default.MoreVert, "Options", Modifier.size(if (compact) 24.dp else 30.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BigMenuItem("Edit") { open = false; onEdit() }
            if (!task.done) {
                BigMenuItem("Move to top") { open = false; TaskStore.move(context, task.id, TaskStore.Move.TOP) }
                BigMenuItem("Move to bottom") { open = false; TaskStore.move(context, task.id, TaskStore.Move.BOTTOM) }
                BigMenuItem(if (task.urgent) "Not urgent" else "Mark urgent") {
                    open = false; TaskStore.edit(context, task.id, task.text, !task.urgent, task.count)
                }
            }
            BigMenuItem("Move to ${other.lowercase()} list") { open = false; TaskStore.moveToList(context, task.id, other) }
            HorizontalDivider()
            BigMenuItem("Delete") { open = false; onDelete() }
        }
    }
}

@Composable
private fun EditDialog(task: Task, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var text by remember(task.id) { mutableStateOf(task.text) }
    var urgent by remember(task.id) { mutableStateOf(task.urgent) }
    var count by remember(task.id) { mutableStateOf(task.count) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit task", fontSize = 24.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Urgent", fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Switch(checked = urgent, onCheckedChange = { urgent = it })
                }
                if (count > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Added $count times", fontSize = 20.sp)
                            Text("Merged duplicates", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { count = 1 }) { Text("Reset to 1", fontSize = 17.sp) }
                    }
                }
                TextButton(onClick = onDelete) { Text("Delete task", color = MaterialTheme.colorScheme.error, fontSize = 17.sp) }
            }
        },
        confirmButton = {
            Button(onClick = { TaskStore.edit(context, task.id, text, urgent, count); onDismiss() }) { Text("Save", fontSize = 18.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 18.sp) } },
    )
}
