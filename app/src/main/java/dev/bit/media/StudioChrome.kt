package dev.bit.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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

/** Screen-scoped snackbars, so messages and their actions survive switching destinations. */
internal class Snackbars(val snackbar: SnackbarHostState, private val scope: CoroutineScope) {
    /** Shows "message · Undo". */
    fun offer(message: String, undo: () -> Unit) = show(message, "Undo", SnackbarDuration.Long, undo)

    fun show(message: String, action: String? = null, duration: SnackbarDuration = SnackbarDuration.Short,
             onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, action, duration = duration) == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

internal val LocalSnackbars = staticCompositionLocalOf<Snackbars> { error("No Snackbars") }

/**
 * A failure in plain words. The engine's original text stays one tap away, and failures a
 * newer yt-dlp tends to fix offer the update; [onUpdated] runs after a new release is installed.
 */
@Composable
internal fun ProblemNotice(problem: Problem, onUpdated: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    var details by rememberSaveable(problem.detail) { mutableStateOf(false) }
    val updating by Engine.updating.collectAsState()
    val jobs by Jobs.state.collectAsState()
    val snackbars = LocalSnackbars.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text(problem.message, color = MaterialTheme.colorScheme.error)
    if (details) problem.detail?.let {
        SelectionContainer {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        actions()
        if (problem.update) OutlinedButton(enabled = !updating && !jobs.running, onClick = {
            scope.launch {
                val result = Engine.updateYtDlp(context)
                snackbars.show(result.message)
                if (result.updated) onUpdated?.invoke()
            }
        }) { Text(if (updating) "Updating yt-dlp…" else "Update yt-dlp") }
        if (problem.detail != null) TextButton(onClick = { details = !details }) {
            Text(if (details) "Hide details" else "Details")
        }
    }
}

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

/** App, yt-dlp and FFmpeg versions, measured once per installed build, and the yt-dlp update. */
internal object Engine {
    data class Versions(val app: String, val ytDlp: String?, val ffmpeg: String?) {
        val label get() = listOfNotNull("Media Studio $app", ytDlp?.let { "yt-dlp $it" }, ffmpeg?.let { "FFmpeg $it" })
            .joinToString(" · ")
    }
    class UpdateResult(val updated: Boolean, val message: String)

    private val current = MutableStateFlow<Versions?>(null)
    val versions = current.asStateFlow()
    private val busy = MutableStateFlow(false)
    val updating = busy.asStateFlow()
    private var started = false

    private fun store(context: Context) = context.getSharedPreferences("engine_versions", Context.MODE_PRIVATE)
    private fun buildKey(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0)
        .let { "v2-${it.longVersionCode}-${it.lastUpdateTime}" }

    suspend fun load(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val name = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        val key = buildKey(app)
        val prefs = store(app)
        val cached = prefs.getString(key, null)?.split('\n')?.takeIf { it.size == 2 }
        val (yt, ff) = cached?.let { (y, f) -> y.ifBlank { null } to f.ifBlank { null } } ?: withContext(Dispatchers.IO) {
            runCatching { Backend(app).versions() }.getOrDefault(null to null).also { (y, f) ->
                if (y != null || f != null) prefs.edit().clear().putString(key, "${y.orEmpty()}\n${f.orEmpty()}").apply()
            }
        }
        current.value = Versions(name, yt, ff)
    }

    /** Runs only when asked: there are no automatic checks or updates. */
    suspend fun updateYtDlp(context: Context): UpdateResult {
        if (!busy.compareAndSet(false, true)) return UpdateResult(false, "yt-dlp is already updating.")
        try {
            if (Jobs.state.value.running) return UpdateResult(false, "Wait until the current export has finished.")
            val app = context.applicationContext
            val result = withContext(Dispatchers.IO) { runCatching { Backend(app).updateYtDlp() } }
            return result.fold({ (updated, version) ->
                val versions = current.value
                if (versions != null && version != null) {
                    current.value = versions.copy(ytDlp = version)
                    store(app).edit().clear().putString(buildKey(app), "$version\n${versions.ffmpeg.orEmpty()}").apply()
                }
                when {
                    updated -> UpdateResult(true, "yt-dlp updated${version?.let { " to $it" }.orEmpty()}.")
                    else -> UpdateResult(false, "yt-dlp is up to date${version?.let { " ($it)" }.orEmpty()}.")
                }
            }, { UpdateResult(false, "Could not update yt-dlp. ${Problems.explain(it.localizedMessage).message}") })
        } finally {
            busy.value = false
        }
    }
}

@Composable
internal fun VersionFooter() {
    val context = LocalContext.current
    val versions by Engine.versions.collectAsState()
    val updating by Engine.updating.collectAsState()
    val jobs by Jobs.state.collectAsState()
    val snackbars = LocalSnackbars.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Engine.load(context) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    versions?.let {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(it.label, Modifier.fillMaxWidth().clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/lns2bt/media-studio-android/releases")))
                }
            }.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
            TextButton(enabled = !updating && !jobs.running, onClick = {
                scope.launch { snackbars.show(Engine.updateYtDlp(context).message) }
            }) {
                if (updating) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (updating) "Updating yt-dlp…" else "Update yt-dlp", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Explains why notifications help before Android asks, once. Call the returned function with
 * what should happen afterwards; it runs whether or not permission is granted.
 */
@Composable
internal fun rememberNotificationPrompt(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("prompts", Context.MODE_PRIVATE) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var asking by remember { mutableStateOf(false) }
    fun finish() {
        val next = pending
        pending = null
        next?.invoke()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false; finish() },
        title = { Text("Get notified when it's saved?") },
        text = { Text("Media Studio shows the progress and lets you know when the file is in Downloads, " +
            "so you can leave the app while it works.") },
        confirmButton = {
            TextButton(onClick = {
                asking = false
                prefs.edit().putBoolean("notifications", true).apply()
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = {
                asking = false
                prefs.edit().putBoolean("notifications", true).apply()
                finish()
            }) { Text("Not now") }
        },
    )
    return { then ->
        val needed = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notifications", false)
        if (needed) {
            pending = then
            asking = true
        } else then()
    }
}
