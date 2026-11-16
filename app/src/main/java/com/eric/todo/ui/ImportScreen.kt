package com.eric.todo.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.eric.todo.AppViewModel
import com.eric.todo.Choice
import com.eric.todo.ImportState
import com.eric.todo.R
import com.eric.todo.ReviewItem
import com.eric.todo.data.BUSINESS
import com.eric.todo.data.PERSONAL
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    vm: AppViewModel,
    state: ImportState,
    defaultList: String,
    aiOn: Boolean,
    hasKey: Boolean,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf(defaultList) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state is ImportState.Review) "Check before adding" else "Add notes", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(30.dp)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (state is ImportState.Review) {
                val adding = state.items.count { it.choice != Choice.SKIP }
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().padding(12.dp)) {
                    Button(
                        onClick = { onDone(vm.applyReview()) },
                        enabled = adding > 0,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text(if (adding > 0) "Add $adding to my lists" else "Nothing selected", fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (state) {
                is ImportState.Idle -> InputPage(vm, target, { target = it }, aiOn, hasKey)
                is ImportState.Working -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 6.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(state.message, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                is ImportState.Failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.message, fontSize = 21.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { vm.reset() }, modifier = Modifier.height(56.dp)) { Text("Try again", fontSize = 19.sp) }
                }
                is ImportState.Review -> ReviewPage(vm, state)
            }
        }
    }
}

@Composable
private fun InputPage(vm: AppViewModel, target: String, setTarget: (String) -> Unit, aiOn: Boolean, hasKey: Boolean) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri
        if (ok && u != null) vm.analyzePhotos(listOf(Uri.parse(u)), target)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) vm.analyzePhotos(uris, target)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Tasks without a Business / Personal heading go to:", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(BUSINESS, PERSONAL).forEach { name ->
                ListChip(name, selected = target == name, modifier = Modifier.weight(1f).height(56.dp)) { setTarget(name) }
            }
        }

        BigAction(R.drawable.ic_camera, "Take a picture of a list") {
            val dir = File(context.cacheDir, "photos").apply { mkdirs() }
            val file = File(dir, "notes_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            photoUri = uri.toString()
            camera.launch(uri)
        }
        BigAction(R.drawable.ic_photo, "Choose photos / screenshots") {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        BigAction(R.drawable.ic_paste, "Paste what I copied") {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (pasted.isNotBlank()) vm.analyzeText(pasted, target) else text = text
        }

        Text("Or type / paste here:", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("One task per line, or paste a whole list", fontSize = 19.sp) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.analyzeText(text, target) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(60.dp),
        ) { Text("Find the tasks", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Text(
                when {
                    aiOn -> "AI is ON: it reads handwriting, cleans up wording, sorts Business vs Personal and spots tasks you already have."
                    hasKey -> "AI is turned off in Settings. Text is read on the phone."
                    else -> "AI is off. Printed text and pasted lists work fine. For handwriting and smarter sorting, add a free Gemini key in Settings."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }
        Text(
            "Tip: in Voice Log, tap Share on a to-do list and pick Eric's To-Do. It lands right here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigAction(icon: Int, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ListChip(name: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = listColor(name)
    Box(
        modifier
            .background(if (selected) color else Color.Transparent, RoundedCornerShape(14.dp))
            .border(2.dp, color, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.uppercase(),
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReviewPage(vm: AppViewModel, state: ImportState.Review) {
    val dupes = state.items.count { it.matchId != null && !it.matchDone }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${state.items.size} found" + if (dupes > 0) " · $dupes you already have" else "",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
                state.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (dupes > 0) {
                    Text(
                        "Merge combines a repeat into the task you already have and adds to its count (×2, ×3…).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.setAllDuplicates(Choice.MERGE) }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text("Merge all repeats", fontSize = 16.sp)
                        }
                        OutlinedButton(onClick = { vm.setAllDuplicates(Choice.ADD) }, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text("Keep all as new", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
        items(state.items, key = { it.key }) { item -> ReviewCard(vm, item) }
    }
}

@Composable
private fun ReviewCard(vm: AppViewModel, item: ReviewItem) {
    val skipped = item.choice == Choice.SKIP
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (skipped) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier.border(
            if (item.matchId != null && !skipped) 2.dp else 1.dp,
            if (item.matchId != null && !skipped) Orange else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(18.dp),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListChip(item.list, selected = true) {
                    vm.setList(item.key, if (item.list == BUSINESS) PERSONAL else BUSINESS)
                }
                Box(
                    Modifier
                        .background(if (item.urgent) UrgentRed else Color.Transparent, RoundedCornerShape(14.dp))
                        .border(2.dp, UrgentRed, RoundedCornerShape(14.dp))
                        .clickable { vm.toggleUrgent(item.key) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("URGENT", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (item.urgent) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (item.count > 1) Text("×${item.count} in notes", fontSize = 16.sp, color = Orange, fontWeight = FontWeight.Bold)
            }
            Text("Tap BUSINESS / PERSONAL to switch lists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = item.text,
                onValueChange = { vm.setText(item.key, it) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            if (item.matchId != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        when {
                            item.matchDone -> "You already finished this:"
                            item.sure -> "You already have this task:"
                            else -> "Looks like a task you already have:"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.matchText.orEmpty(), fontSize = 19.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                        CountBadge(item.matchCount)
                    }
                    if (!item.matchDone) {
                        Spacer(Modifier.height(4.dp))
                        Text("OK to combine them?", fontSize = 17.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                ChoiceRow(
                    if (item.matchDone) listOf(Choice.REOPEN to "Reopen", Choice.ADD to "Add new", Choice.SKIP to "Skip")
                    else listOf(Choice.MERGE to "Yes, merge (×${item.matchCount + item.count})", Choice.ADD to "Keep both", Choice.SKIP to "Skip"),
                    item.choice,
                ) { vm.setChoice(item.key, it) }
            } else {
                if (item.checkedOff) Text("Checked off in your notes", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(listOf(Choice.ADD to "Add", Choice.SKIP to "Skip"), item.choice) { vm.setChoice(item.key, it) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(options: List<Pair<Choice, String>>, selected: Choice, onSelect: (Choice) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (c, label) ->
            val on = c == selected
            Box(
                Modifier
                    .weight(if (c == Choice.MERGE) 1.6f else 1f)
                    .heightIn(min = 52.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .clickable { onSelect(c) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
