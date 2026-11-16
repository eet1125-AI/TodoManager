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

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LIST = "open_list"
        const val EXTRA_ADD = "open_add"
    }

    private val vm: AppViewModel by viewModels()
    private val pendingList = mutableStateOf<String?>(null)
    private val pendingImport = mutableStateOf(false)

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
                        "settings" -> SettingsScreen(values, settings, keyCheck, onTestKey = { vm.testKey() }, onBack = { screen = "main" })
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
