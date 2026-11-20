package dev.bit.media

import android.app.Application
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.Formatter
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val form by lazy { ViewModelProvider(this)[MediaForm::class.java] }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::openMedia)
    }
    private var pasteWhenFocused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pasteWhenFocused = savedInstanceState?.getBoolean("pasteWhenFocused") == true
        if (savedInstanceState == null || !form.initialized) receive(intent, savedInstanceState != null)
        form.initialized = true
        setContent {
            StudioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MediaScreen(this, form, ::openMedia)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pasteWhenFocused", pasteWhenFocused)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pasteWhenFocused) {
            pasteWhenFocused = false
            pasteLink(form)
        }
    }

    private fun openMedia(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            form.notify("Choose a shared audio or video file.")
            return
        }
        form.destination = Destination.Converter
        form.scrollToOpenedFile = true
        ViewModelProvider(this)[LocalConverterModel::class.java].open(uri)
    }

    override fun onStart() {
        super.onStart()
        Jobs.visible = true
    }

    override fun onStop() {
        Jobs.visible = false
        super.onStop()
    }

    private fun receive(intent: Intent, restoring: Boolean = false) {
        if (!restoring) pasteWhenFocused = false
        // Extras come from other apps; a malformed bundle must not crash the activity.
        if (runCatching { intent.getBooleanExtra(DownloadService.FROM_RESULT, false) }.getOrDefault(false))
            getSystemService(android.app.NotificationManager::class.java).cancel(DownloadService.DONE_ID)
        when (intent.action) {
            "dev.bit.media.OPEN_CONVERTER" -> form.destination = Destination.Converter
            "dev.bit.media.PICK_FILE" -> {
                form.destination = Destination.Converter
                if (!restoring) picker.launch(arrayOf("audio/*", "video/*"))
            }
            "dev.bit.media.PASTE_LINK" -> {
                form.destination = Destination.Extractor
                if (!restoring) {
                    if (hasWindowFocus()) pasteLink(form) else pasteWhenFocused = true
                }
            }
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                if (intent.action == Intent.ACTION_SEND && intent.type?.substringBefore(';') == "text/plain") {
                    form.destination = Destination.Extractor
                    val link = firstLink(intent.sharedText())
                    if (link != null) form.editUrl(link) else form.notify("No web link found in the shared text.")
                } else {
                    val uri = if (intent.action == Intent.ACTION_VIEW) intent.data else runCatching {
                        @Suppress("DEPRECATION")
                        (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    }.getOrNull()
                    if (uri != null) openMedia(uri) else form.notify("No media file found.")
                }
            }
        }
    }
}

/** EXTRA_TEXT from another app, or empty when it is missing or cannot be read. */
internal fun Intent.sharedText(): String =
    runCatching { getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull().orEmpty()

private fun Context.pasteLink(form: MediaForm) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    val link = firstLink((item?.text?.toString() ?: item?.uri?.toString()).orEmpty())
    if (link != null) form.editUrl(link) else form.notify("No web link found on the clipboard.")
}

internal fun Mode.available(info: Info?): Boolean = when (this) {
    Mode.BOTH -> info?.video == true && info.audio
    Mode.VIDEO -> info?.video == true
    Mode.AUDIO -> info?.audio == true
}

class MediaForm(application: Application) : AndroidViewModel(application) {
    internal var destination by mutableStateOf(Destination.Extractor)
    internal var scrollToOpenedFile by mutableStateOf(false)
    var initialized = false
    var url by mutableStateOf("")
        private set
    var info by mutableStateOf<Info?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var from by mutableStateOf("0:00")
    var until by mutableStateOf("")
        private set
    var mode by mutableStateOf(Mode.BOTH)
    var convert by mutableStateOf(false)
    var localTrim by mutableStateOf(false)
    /** Highest video height; null means the best available. */
    var height by mutableStateOf<Int?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Start offset read from the link itself, such as `?t=90`. */
    var linkStart by mutableStateOf<Int?>(null)
        private set
    /** File name chosen by the user; null derives it from the title. */
    var name by mutableStateOf<String?>(null)
    private var endEdited = false
    private var generation = 0L
    private var request: Job? = null
    private var reader: Backend? = null
    /** Short messages for the screen's snackbar, kept until the screen shows them. */
    internal val notices = Channel<String>(Channel.BUFFERED)

    fun notify(text: String) {
        notices.trySend(text)
    }

    fun editEnd(value: String) {
        endEdited = true
        until = value
    }

    fun editUrl(value: String) {
        if (url == value) return
        url = value
        info = null
        thumbnail = null
        linkStart = LinkTime.start(value)
        from = linkStart?.let(FormTime::format) ?: "0:00"
        until = ""
        endEdited = false
        mode = Mode.BOTH
        convert = false
        localTrim = false
        height = null
        name = null
        inspect()
    }

    fun inspect() {
        val id = ++generation
        stopReading()
        val link = normalizeLink(url)
        error = null
        loading = validLink(link)
        if (!loading) return
        request = viewModelScope.launch {
            try {
                delay(450)
                // Each read gets its own engine so a superseded one can be stopped, not just ignored.
                val backend = Backend(getApplication<Application>())
                reader = backend
                val result = runInterruptible(Dispatchers.IO) { backend.inspect(link) }
                if (generation != id) return@launch
                info = result
                mode = Mode.values().firstOrNull { it.available(result) } ?: Mode.BOTH
                if (!endEdited) until = result.duration?.let(FormTime::format).orEmpty()
                result.thumbnail?.let { link ->
                    launch {
                        val image = withContext(Dispatchers.IO) { Thumbnails.load(link, 960) }
                        if (generation == id && image != null) thumbnail = image.asImageBitmap()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation == id) error = e.localizedMessage ?: "Could not read this link."
            } finally {
                if (generation == id) loading = false
            }
        }
    }

    /** Stops a metadata read in flight, including its yt-dlp process. */
    private fun stopReading() {
        request?.cancel()
        request = null
        reader?.cancel()
        reader = null
    }

    override fun onCleared() {
        ++generation
        stopReading()
        super.onCleared()
    }

    internal val range get() = FormTime.range(from, until, info?.duration)

    internal class Snapshot(
        val url: String, val info: Info?, val thumbnail: ImageBitmap?, val error: String?, val loading: Boolean,
        val from: String, val until: String, val endEdited: Boolean, val mode: Mode, val convert: Boolean,
        val localTrim: Boolean, val height: Int?, val name: String?, val linkStart: Int?,
    )

    /** Clears the form. A running download is not affected. Returns the state for [restore]. */
    internal fun reset(): Snapshot {
        val saved = Snapshot(url, info, thumbnail, error, loading, from, until, endEdited, mode, convert,
            localTrim, height, name, linkStart)
        ++generation
        stopReading()
        url = ""
        info = null
        thumbnail = null
        error = null
        loading = false
        from = "0:00"
        until = ""
        endEdited = false
        mode = Mode.BOTH
        convert = false
        localTrim = false
        height = null
        name = null
        linkStart = null
        return saved
    }

    internal fun restore(saved: Snapshot) {
        ++generation
        stopReading()
        url = saved.url
        info = saved.info
        thumbnail = saved.thumbnail
        error = saved.error
        loading = false
        from = saved.from
        until = saved.until
        endEdited = saved.endEdited
        mode = saved.mode
        convert = saved.convert
        localTrim = saved.localTrim
        height = saved.height
        name = saved.name
        linkStart = saved.linkStart
        // Metadata that was still loading is fetched again.
        if (saved.loading || (saved.info == null && saved.error == null)) inspect()
    }

    /** The file name the next export receives; the extension is known only for conversions. */
    fun preview(): String? {
        val source = info ?: return null
        val bounds = range as? FormRange.Valid
        val range = if (bounds != null && (bounds.start > 0 || bounds.end != null))
            clipLabel(bounds.start * 1000L, (bounds.end ?: source.duration)?.times(1000L)) else ""
        val ext = if (!convert) "" else if (mode == Mode.AUDIO) ".mp3" else ".mp4"
        return ExportName.base(source.title, name, range) + ext
    }

    /** Selects a chapter, or the whole item for null. */
    fun selectChapter(chapter: Chapter?) {
        val duration = info?.duration
        from = FormTime.format(chapter?.start ?: 0)
        editEnd((chapter?.end ?: duration)?.let(FormTime::format).orEmpty())
    }

    fun isSelected(chapter: Chapter?): Boolean {
        val bounds = range as? FormRange.Valid ?: return false
        return if (chapter == null) bounds.start == 0 && bounds.end == null
        else bounds.start == chapter.start && (bounds.end ?: info?.duration) == chapter.end
    }

    /** Approximate transfer size for the current choices, or null when unknown. */
    fun estimate(): Long? {
        val source = info ?: return null
        val bounds = range as? FormRange.Valid ?: return null
        val duration = source.duration
        val fraction = if (localTrim || duration == null) 1.0
            else ((bounds.end ?: duration) - bounds.start).toDouble() / duration
        return MediaMeta.estimate(source.formats, mode, height.takeIf { mode != Mode.AUDIO }, fraction)
    }

    fun spec(): JobSpec? {
        val source = info ?: return null
        val bounds = range as? FormRange.Valid ?: return null
        if (!mode.available(source)) return null
        return JobSpec(normalizeLink(url), source, mode, convert, bounds.start, bounds.end,
            localTrim && (bounds.start > 0 || bounds.end != null), height.takeIf { mode != Mode.AUDIO }, name)
    }
}

@Composable
private fun MediaScreen(context: Context, form: MediaForm, onEdit: (Uri) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val current = form.destination
    val job by Jobs.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = remember(snackbar, scope) { Snackbars(snackbar, scope) }
    LaunchedEffect(form) { for (notice in form.notices) undo.show(notice) }
    CompositionLocalProvider(LocalSnackbars provides undo) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp && maxHeight >= 480.dp
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.weight(1f)) {
                if (rail) NavigationRail(windowInsets = WindowInsets(0)) {
                    Spacer(Modifier.height(16.dp))
                    Destination.entries.forEach { item ->
                        NavigationRailItem(selected = item == current, onClick = { form.destination = item },
                            icon = { DestinationIcon(item) }, label = { Text(item.name) })
                    }
                }
                holder.SaveableStateProvider(current.name) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(current, form.scrollToOpenedFile) {
                        if (current == Destination.Converter && form.scrollToOpenedFile) {
                            scroll.scrollTo(0)
                            form.scrollToOpenedFile = false
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()
                             .verticalScroll(scroll).padding(horizontal = if (rail) 32.dp else 16.dp)
                            .padding(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Media Studio", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(current.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                                Hint(if (current == Destination.Extractor) "Save video or audio from a link."
                                    else "Change the format of a file on your device.")
                            }
                            if (current == Destination.Extractor) Extractor(context, form) else LocalConverter()
                            if (!job.running) JobPanel(context, onEdit)
                            VersionFooter()
                        }
                    }
                }
            }
            SnackbarHost(snackbar)
            if (job.running) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(job.stage, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            job.percent?.let { Text("$it%", style = MaterialTheme.typography.labelLarge) }
                            TextButton(onClick = { DownloadService.cancel(context) }) { Text("Cancel") }
                        }
                        StudioProgress(job.percent)
                    }
                }
            }
            if (!rail) NavigationBar(windowInsets = WindowInsets(0)) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(selected = item == current, onClick = { form.destination = item },
                        icon = { DestinationIcon(item) }, label = { Text(item.name) })
                }
            }
        }
    }
    }
}

@Composable
private fun Extractor(context: Context, form: MediaForm) {
    val status by Jobs.state.collectAsStateWithLifecycle()
    val notificationPrompt = rememberNotificationPrompt()
    val info = form.info
    val rangeError = (form.range as? FormRange.Invalid)?.message
    val undo = LocalSnackbars.current
    StudioSection("Source") {
        OutlinedTextField(form.url, form::editUrl, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Media link") }, placeholder = { Text("https://…") },
            trailingIcon = {
                if (form.url.isEmpty()) TextButton(onClick = { context.pasteLink(form) }) { Text("Paste") }
                else IconButton(onClick = {
                    val saved = form.reset()
                    val result = Jobs.dismissResult()
                    undo.offer("Extractor cleared") {
                        form.restore(saved)
                        result?.let(Jobs::restoreResult)
                    }
                }) { Icon(CloseSymbol, contentDescription = "Clear link and reset Extractor") }
            })
        when {
            form.loading -> {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest))
                Hint("Reading metadata…")
                StudioProgress(null)
            }
            form.error != null -> {
                Text("Could not read this link", style = MaterialTheme.typography.titleMedium)
                ProblemNotice(Problems.explain(form.error), onUpdated = form::inspect) {
                    OutlinedButton(onClick = form::inspect) { Text("Retry") }
                }
            }
            info != null -> {
                SourceSummary(form, info, form.thumbnail)
                if (!info.video && !info.audio) Text("No downloadable tracks found.")
            }
            form.url.isNotBlank() -> Text("Enter a complete HTTP or HTTPS link.")
            else -> Hint("You can also share a link directly to Media Studio.")
        }
    }
    StudioSection("Time range") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.from, { form.from = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("From") })
            OutlinedTextField(form.until, form::editEnd, Modifier.weight(1f), singleLine = true,
                label = { Text("To") }, placeholder = { Text("Until the end") })
        }
        if (rangeError != null) Text(rangeError, color = MaterialTheme.colorScheme.error)
        form.linkStart?.let { if (form.from == FormTime.format(it)) Hint("Start time taken from the link.") }
        if (info != null && info.chapters.isNotEmpty()) {
            Hint("Chapters")
            ChipRow {
                FilterChip(form.isSelected(null), { form.selectChapter(null) }, label = { Text("Full length") })
                info.chapters.forEach { chapter ->
                    FilterChip(form.isSelected(chapter), {
                        form.selectChapter(chapter.takeUnless { form.isSelected(it) })
                    }, modifier = Modifier.widthIn(max = 260.dp), label = {
                        Text("${FormTime.format(chapter.start)}  ${chapter.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                }
            }
        }
        if (form.range.let { it is FormRange.Valid && (it.start > 0 || it.end != null) }) {
            Row(Modifier.fillMaxWidth().toggleable(form.localTrim, role = Role.Checkbox) { form.localTrim = it }
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(form.localTrim, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text("Download full source and trim locally")
            }
        }
        if (!form.convert && form.mode != Mode.AUDIO) Hint("Original video cuts may start at a nearby keyframe.")
    }
    StudioSection("Output") {
        Hint("Tracks")
        Column(Modifier.selectableGroup()) {
            listOf(Mode.BOTH to "Video with audio", Mode.VIDEO to "Video only", Mode.AUDIO to "Audio only")
                .forEach { (mode, label) -> Choice(label, form.mode == mode, mode.available(info)) { form.mode = mode } }
        }
        val qualities = info?.qualities.orEmpty()
        if (form.mode != Mode.AUDIO && qualities.size > 1) {
            HorizontalDivider()
            Hint("Quality")
            ChipRow {
                FilterChip(form.height == null, { form.height = null }, label = { Text("Best · ${qualities.first()}p") })
                qualities.drop(1).forEach { h ->
                    FilterChip(form.height == h, { form.height = h }, label = { Text("${h}p") })
                }
            }
        }
        HorizontalDivider()
        Hint("Format")
        Column(Modifier.selectableGroup()) {
            Choice("Keep original", !form.convert) { form.convert = false }
            Choice("Convert to ${if (form.mode == Mode.AUDIO) "MP3" else "MP4"}", form.convert) { form.convert = true }
        }
        form.estimate()?.let { Hint("About ${Formatter.formatShortFileSize(context, it)} to download") }
        Button(modifier = Modifier.fillMaxWidth(), enabled = !status.running && form.spec() != null, onClick = {
            form.spec()?.let {
                DownloadService.start(context, it)
                notificationPrompt {}
            }
        }) { Text("Start download") }
    }
}

@Composable
private fun SourceSummary(form: MediaForm, info: Info, thumbnail: ImageBitmap?) {
    if (info.thumbnail != null) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Crossfade(thumbnail, label = "Thumbnail") { image ->
                if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            }
            info.duration?.let {
                Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White) {
                    Text(FormTime.format(it), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    EditableTitle(info.title, form.name, form.preview().orEmpty(), MaterialTheme.typography.titleLarge) { form.name = it }
    val tracks = when {
        info.video && info.audio -> "Video and audio"
        info.video -> "Video"
        info.audio -> "Audio"
        else -> null
    }
    Hint(listOfNotNull(info.uploader, info.duration?.let(FormTime::format) ?: "Unknown duration", tracks)
        .joinToString("  •  "))
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun JobPanel(context: Context, onEdit: (Uri) -> Unit) {
    val status by Jobs.state.collectAsStateWithLifecycle()
    StudioSection("Activity") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status.stage.ifBlank { "Ready" }, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge)
            if (status.running) status.percent?.let { Text("${it.coerceIn(0, 100)}%", style = MaterialTheme.typography.titleMedium) }
        }
        if (status.running) {
            StudioProgress(status.percent)
            OutlinedButton(onClick = { DownloadService.cancel(context) }) { Text("Cancel") }
        }
        status.error?.let { error ->
            val snackbars = LocalSnackbars.current
            ProblemNotice(Problems.explain(error), onUpdated = { snackbars.show("yt-dlp updated. Start the export again.") })
        }
        status.uri?.let { uri -> ExportResultCard(context, uri, onEdit) }
    }
}

@Composable
internal fun StudioSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = click).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Text(label, color = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

