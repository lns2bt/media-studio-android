package dev.bit.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows "message · Undo" from screen scope, so the action survives switching destinations. */
internal class UndoHost(val snackbar: SnackbarHostState, private val scope: CoroutineScope) {
    fun offer(message: String, undo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) undo()
        }
    }
}

internal val LocalUndo = staticCompositionLocalOf<UndoHost> { error("No UndoHost") }

/**
 * The item's title with an edit action. Editing chooses the export's file name; [preview]
 * shows the name the next export will receive.
 */
@Composable
internal fun EditableTitle(title: String, custom: String?, preview: String,
                           style: androidx.compose.ui.text.TextStyle, onRename: (String?) -> Unit) {
    var editing by rememberSaveable(title) { mutableStateOf(false) }
    if (!editing) {
        Row(verticalAlignment = Alignment.Top) {
            Text(custom ?: title, Modifier.weight(1f).padding(top = 6.dp), style = style, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { editing = true }) { Icon(EditSymbol, contentDescription = "Rename file") }
        }
        Hint("Saves as $preview")
        return
    }
    val initial = custom ?: ExportName.base(title, null, "")
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val problem = ExportName.split("").problem(field.text)
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Leaving the editor closes the keyboard instead of moving focus to the next field.
    fun finish(name: String?) {
        onRename(name)
        focusManager.clearFocus()
        editing = false
    }
    fun save() {
        if (problem != null) return
        finish(field.text.takeUnless { custom == null && it == initial })
    }
    OutlinedTextField(field, { field = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
        label = { Text("File name") }, isError = problem != null,
        supportingText = { Text(problem ?: "The extension is added when saving.") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = ::save, enabled = problem == null) { Text("Save name") }
        TextButton(onClick = { finish(custom) }) { Text("Cancel") }
        if (custom != null) TextButton(onClick = { finish(null) }) { Text("Use title") }
    }
}

/** App, yt-dlp and FFmpeg versions; engine versions are measured once per installed build. */
internal object EngineVersions {
    private val current = MutableStateFlow<String?>(null)
    val state = current.asStateFlow()
    private var started = false

    suspend fun load(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val key = "${info.longVersionCode}-${info.lastUpdateTime}"
        val prefs = app.getSharedPreferences("engine_versions", Context.MODE_PRIVATE)
        val cached = prefs.getString(key, null)
        val engines = cached ?: withContext(Dispatchers.IO) {
            runCatching { Backend(app).versions() }.getOrNull()?.let { (yt, ff) ->
                listOfNotNull(yt?.let { "yt-dlp $it" }, ff?.let { "FFmpeg $it" }).joinToString(" · ")
                    .also { prefs.edit().clear().putString(key, it).apply() }
            }
        }
        current.value = listOfNotNull("Media Studio ${info.versionName}", engines?.ifBlank { null }).joinToString(" · ")
    }
}

@Composable
internal fun VersionFooter() {
    val context = LocalContext.current
    val text by EngineVersions.state.collectAsState()
    LaunchedEffect(Unit) { EngineVersions.load(context) }
    text?.let {
        Text(it, Modifier.fillMaxWidth().clickable {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/lns2bt/media-studio-android/releases")))
            }
        }.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}
