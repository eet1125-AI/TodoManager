package com.eric.todo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eric.todo.data.BUSINESS
import com.eric.todo.data.PERSONAL
import com.eric.todo.data.Settings
import com.eric.todo.data.TaskStore
import com.eric.todo.ui.DuplicatesScreen
import com.eric.todo.ui.ImportScreen
import com.eric.todo.ui.MainScreen
import com.eric.todo.ui.SettingsScreen
import com.eric.todo.ui.TodoTheme
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eric.todo.sync.DriveSync

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LIST = "open_list"
        const val EXTRA_ADD = "open_add"
    }

    private val vm: AppViewModel by viewModels()
    private val pendingList = mutableStateOf<String?>(null)
    private val pendingImport = mutableStateOf(false)
    private val openedTick = mutableStateOf(0)
    private var lastAutoSync = 0L

    override fun onStart() {
        super.onStart()
        openedTick.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TaskStore.init(this)
        val settings = Settings.get(this)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val values by settings.values.collectAsStateWithLifecycle()
            val tasks by TaskStore.tasks.collectAsStateWithLifecycle()
            val importState by vm.importState.collectAsStateWithLifecycle()
            val openImport by vm.openImport.collectAsStateWithLifecycle()
            val keyCheck by vm.keyCheck.collectAsStateWithLifecycle()
            var screen by rememberSaveable { mutableStateOf("main") }
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val currentList = values.lastList
            var syncing by remember { mutableStateOf(false) }
            var showSyncSetup by remember { mutableStateOf(false) }

            fun runSync(quiet: Boolean = false) {
                if (syncing) return
                syncing = true
                scope.launch {
                    val result = runCatching { DriveSync.sync(this@MainActivity) }
                    syncing = false
                    result.onSuccess { r ->
                        if (!quiet || r.added + r.updated + r.removed > 0) {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(r.message)
                        }
                    }.onFailure { e ->
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar("Sync failed: ${e.message ?: "no connection"}")
                    }
                }
            }
            fun syncOrSetUp() { if (DriveSync.isSetUp(this@MainActivity)) runSync() else showSyncSetup = true }

            val createSyncFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) { DriveSync.connect(this@MainActivity, uri); runSync() }
            }
            val openSyncFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) { DriveSync.connect(this@MainActivity, uri); runSync() }
            }

            // Sync automatically when the app is opened (at most once a minute).
            val opened by openedTick
            LaunchedEffect(opened) {
                val now = System.currentTimeMillis()
                if (values.autoSync && DriveSync.isSetUp(this@MainActivity) && now - lastAutoSync > 60_000) {
                    lastAutoSync = now
                    runSync(quiet = true)
                }
            }

            if (showSyncSetup) {
                AlertDialog(
                    onDismissRequest = { showSyncSetup = false },
                    title = { Text("Sync with Google Drive") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Your list is kept in one file in your Google Drive. Every phone and tablet uses the same file. Tap Sync on any device to send and get changes.")
                            Text("On the next screen, tap ☰ (top left) and choose Drive, then save or pick the file there.")
                            Button(
                                onClick = { showSyncSetup = false; createSyncFile.launch(DriveSync.FILE_NAME) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("First device: create the sync file") }
                            OutlinedButton(
                                onClick = { showSyncSetup = false; openSyncFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Already set up on another device: choose the file") }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showSyncSetup = false }) { Text("Cancel") } },
                )
            }

            LaunchedEffect(openImport) {
                if (openImport) { screen = "import"; vm.importOpened() }
            }
            val wantList by pendingList
            LaunchedEffect(wantList) {
                wantList?.let { settings.setLastList(it); screen = "main"; pendingList.value = null }
            }
            val wantImport by pendingImport
            LaunchedEffect(wantImport) {
                if (wantImport) { screen = "import"; pendingImport.value = false }
            }

            TodoTheme(light = values.lightTheme, textSize = values.textSize) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BackHandler(enabled = screen != "main") {
                        if (screen == "import") vm.reset()
                        screen = "main"
                    }
                    when (screen) {
                        "import" -> ImportScreen(
                            vm = vm,
                            state = importState,
                            defaultList = currentList,
                            aiOn = settings.aiReady,
                            hasKey = values.geminiKey.isNotBlank(),
                            onBack = { vm.reset(); screen = "main" },
                            onDone = { msg ->
                                screen = "main"
                                scope.launch { snackbar.showSnackbar(msg) }
                            },
                        )
                        "duplicates" -> DuplicatesScreen(tasks, currentList, onBack = { screen = "main" })
                        "settings" -> SettingsScreen(
                            values, settings, keyCheck,
                            onTestKey = { vm.testKey() },
                            onSyncNow = { syncOrSetUp() },
                            onSyncSetup = { showSyncSetup = true },
                            onSyncStop = { DriveSync.disconnect(this@MainActivity) },
                            syncing = syncing,
                            onBack = { screen = "main" },
                        )
                        else -> MainScreen(
                            tasks = tasks,
                            currentList = currentList,
                            onSwitchList = { settings.setLastList(it) },
                            onImport = { vm.reset(); screen = "import" },
                            onDuplicates = { screen = "duplicates" },
                            onSettings = { screen = "settings" },
                            onShare = {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, TaskStore.shareText(currentList))
                                startActivity(Intent.createChooser(send, "Share list"))
                            },
                            onTextSize = { step ->
                                val sizes = Settings.TEXT_SIZES.map { it.first }
                                val i = sizes.indexOf(values.textSize).let { if (it < 0) sizes.indexOf(24) else it }
                                val next = (i + step).coerceIn(0, sizes.lastIndex)
                                settings.setTextSize(sizes[next])
                                val label = Settings.TEXT_SIZES[next].second
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar(if (next == i) "Already at $label" else "Text size: $label")
                                }
                            },
                            onSync = { syncOrSetUp() },
                            syncing = syncing,
                            snackbar = snackbar,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val defaultList = Settings.get(this).values.value.lastList
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val image = uriExtra(intent)
                if (image != null && intent.type?.startsWith("image/") == true) vm.sharedIn(null, listOf(image), defaultList)
                else if (!text.isNullOrBlank()) vm.sharedIn(text, emptyList(), defaultList)
                else if (image != null) vm.sharedIn(readText(image), emptyList(), defaultList)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = urisExtra(intent)
                if (uris.isNotEmpty()) vm.sharedIn(null, uris, defaultList)
            }
            else -> {
                intent.getStringExtra(EXTRA_LIST)?.let { if (it == BUSINESS || it == PERSONAL) pendingList.value = it }
                if (intent.getBooleanExtra(EXTRA_ADD, false)) pendingImport.value = true
            }
        }
    }

    /** A shared .txt file instead of text. */
    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun uriExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun urisExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
         else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.toList() ?: emptyList()
}
