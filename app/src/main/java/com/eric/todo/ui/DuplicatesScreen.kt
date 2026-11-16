package com.eric.todo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eric.todo.data.Task
import com.eric.todo.data.TaskStore
import com.eric.todo.importer.Dupes

/** Scans one list for tasks that look like the same thing and asks before merging. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(tasks: List<Task>, listName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(setOf<Pair<String, String>>()) }

    val open = tasks.filter { it.list == listName && !it.done }
    val pairs = remember(open, ignored) {
        Dupes.pairs(open.map { it.text })
            .map { (i, j, s) -> Triple(open[i], open[j], s) }
            .filter { (a, b, _) -> (a.id to b.id) !in ignored }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicates: ${listName.lowercase()}", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(30.dp)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pairs.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No duplicates found", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Your ${listName.lowercase()} list is clean.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                item {
                    Text(
                        "${pairs.size} possible repeat${if (pairs.size == 1) "" else "s"}. Pick the wording to keep, then merge. " +
                            "The counts add up (×2, ×3…).",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(pairs, key = { (a, b, _) -> a.id + b.id }) { (a, b, _) ->
                var keepFirst by remember(a.id, b.id) { mutableStateOf(true) }
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    modifier = Modifier.border(2.dp, Orange, RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Same task?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Orange)
                        PickRow(a, keepFirst) { keepFirst = true }
                        PickRow(b, !keepFirst) { keepFirst = false }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val keepWording = if (keepFirst) a.text else b.text
                                    // Keep whichever sits higher in the list, with the chosen wording
                                    val keepId = if (open.indexOf(a) <= open.indexOf(b)) a.id else b.id
                                    val dropId = if (keepId == a.id) b.id else a.id
                                    TaskStore.merge(context, keepId, dropId, keepText = keepWording)
                                },
                                modifier = Modifier.weight(1.4f).height(56.dp),
                            ) { Text("Merge (×${a.count + b.count})", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { ignored = ignored + (a.id to b.id) },
                                modifier = Modifier.weight(1f).height(56.dp),
                            ) { Text("Not the same", fontSize = 16.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(task: Task, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(task.text, style = taskTextStyle().copy(fontSize = 21.sp), modifier = Modifier.weight(1f))
        CountBadge(task.count)
    }
}
