package com.eric.todo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eric.todo.BuildConfig
import com.eric.todo.data.Settings
import com.eric.todo.data.TaskStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    values: Settings.Values,
    settings: Settings,
    keyCheck: String?,
    onTestKey: () -> Unit,
    onSyncNow: () -> Unit,
    onSyncSetup: () -> Unit,
    onSyncStop: () -> Unit,
    syncing: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var key by remember { mutableStateOf(values.geminiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(values.geminiModel) }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(TaskStore.backupJson().toByteArray()) }
            }.isSuccess
            scope.launch { snackbar.showSnackbar(if (ok) "Backup saved" else "Couldn't save the backup") }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val tasks = TaskStore.parseBackup(text)
                if (tasks.isEmpty()) error("empty")
                TaskStore.replaceAll(context, tasks)
                tasks.size
            }
            scope.launch {
                snackbar.showSnackbar(result.fold({ "Restored $it tasks" }, { "That file isn't a To-Do backup" }))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(30.dp)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Text size")
            Settings.TEXT_SIZES.forEach { (size, label) ->
                val on = values.textSize == size
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(14.dp))
                        .border(2.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable { settings.setTextSize(size) }
                        .padding(14.dp),
                ) { Text("$label – Call Eddie", fontSize = size.sp, fontWeight = FontWeight.SemiBold) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Light background", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(checked = values.lightTheme, onCheckedChange = { settings.setLightTheme(it) })
            }

            HorizontalDivider()
            Section("AI helper (Google Gemini, optional)")
            Text(
                "With a key, the AI reads handwriting in photos, cleans up wording, puts tasks in Business or Personal, " +
                    "and spots tasks you already have. Without one, pasted lists and printed photos still work.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Free key: aistudio.google.com/apikey → Create API key → copy. You can use the same key as Voice Log.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Open Google AI Studio", fontSize = 17.sp) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { androidx.compose.material3.TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { settings.setGeminiKey(key); settings.setGeminiModel(model); onTestKey() },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Save & test", fontSize = 18.sp) }
            }
            keyCheck?.let { Text(it, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use AI when adding notes", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(checked = values.useAi, onCheckedChange = { settings.setUseAi(it) })
            }

            Text("AI model", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            listOf(Settings.DEFAULT_MODEL to "Flash (best)", Settings.LITE_MODEL to "Flash-Lite (faster, higher free limit)").forEach { (m, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { model = m; settings.setGeminiModel(m) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = model == m, onClick = { model = m; settings.setGeminiModel(m) })
                    Text(label, fontSize = 18.sp)
                }
            }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; settings.setGeminiModel(it) },
                label = { Text("Model name (advanced)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Section("Google Drive sync")
            if (values.syncUri.isBlank()) {
                Text(
                    "Keep the same list on your phone and tablet. The list is saved in one file in your Google Drive; " +
                        "tap Sync on any device to send and get changes.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onSyncSetup, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Set up Drive sync", fontSize = 17.sp)
                }
            } else {
                Text(
                    "Syncing with: ${values.syncName.ifBlank { "Drive file" }}\n" +
                        "Last synced: " + (if (values.lastSync == 0L) "never"
                        else SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(values.lastSync))),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onSyncNow, enabled = !syncing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (syncing) "Syncing…" else "Sync now", fontSize = 17.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Sync when the app opens", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    androidx.compose.material3.Switch(checked = values.autoSync, onCheckedChange = { settings.setAutoSync(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onSyncSetup, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Choose file again", fontSize = 15.sp)
                    }
                    OutlinedButton(onClick = onSyncStop, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Stop syncing", fontSize = 15.sp)
                    }
                }
                Text(
                    "Tip: tap Sync on the device you just used, then on the other one. Your list stays on this device too, " +
                        "so it works with no signal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Section("App updates")
            Text(
                "Version " + (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?") +
                    ". The app checks for updates each time it opens.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                onClick = { (context as? android.app.Activity)?.let { com.eric.todo.update.AppUpdater.checkNow(it) } },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Check for updates", fontSize = 17.sp) }

            HorizontalDivider()
            Section("Backup")
            Text(
                "Tasks live only on this phone. Uninstalling the app deletes them, so save a backup now and then.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        backup.launch("ToDo-backup-$stamp.json")
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Save backup", fontSize = 17.sp) }
                OutlinedButton(
                    onClick = { restore.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("Restore", fontSize = 17.sp) }
            }

            HorizontalDivider()
            Section("Home-screen widget")
            Text(
                "Long-press an empty spot on your home screen → Widgets → Eric's To-Do. Drag the widget's edges to fill the whole screen. " +
                    "Tap BUSINESS / PERSONAL at the top of the widget to switch lists. Tap a circle to check a task off.",
                style = MaterialTheme.typography.bodyLarge,
            )

            HorizontalDivider()
            Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
}
