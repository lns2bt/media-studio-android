package dev.bit.media

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class LocalConverterModel(application: Application) : AndroidViewModel(application) {
    var uri by mutableStateOf<Uri?>(null)
        private set
    var info by mutableStateOf<LocalInfo?>(null)
        private set
    var wave by mutableStateOf<List<Float>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var start by mutableLongStateOf(0)
        private set
    var end by mutableLongStateOf(0)
        private set
    var from by mutableStateOf("")
        private set
    var until by mutableStateOf("")
        private set
    var audioOnly by mutableStateOf(false)
        private set
    var format by mutableStateOf(LocalFormat.ORIGINAL)
        private set
    private var backend: Backend? = null
    private var request: Job? = null
    private var generation = 0L

    fun open(source: Uri) {
        val id = ++generation
        request?.cancel()
        backend?.cancel()
        val reader = Backend(getApplication<Application>())
        backend = reader
        uri = source
        info = null
        wave = emptyList()
        loading = true
        error = null
        request = viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    reader.inspectLocal(source, onInfo = { result ->
                        viewModelScope.launch {
                            if (generation == id) {
                                info = result
                                bounds(0, result.durationMs)
                                setTrack(!result.video)
                            }
                        }
                    }) { samples ->
                        val snapshot = samples.toList()
                        viewModelScope.launch { if (generation == id) wave = snapshot }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == id) error = e.localizedMessage ?: "Could not open this file."
            } finally {
                if (generation == id) {
                    loading = false
                    backend = null
                }
            }
        }
    }

    fun setTrack(onlyAudio: Boolean) {
        audioOnly = onlyAudio
        format = ConverterOutput(info?.video == true, onlyAudio).default
    }

    fun choose(value: LocalFormat) { format = value }

    private fun bounds(a: Long, b: Long) {
        start = a
        end = b
        from = LocalTime.format(a)
        until = LocalTime.format(b)
    }

    fun move(isStart: Boolean, ms: Long) {
        val duration = info?.durationMs ?: return
        if (isStart) bounds(ms.coerceIn(0, end - 1), end)
        else bounds(start, ms.coerceIn(start + 1, duration))
    }

    val rangeError: String?
        get() {
            val a = LocalTime.parse(from)
            val b = LocalTime.parse(until)
            return when {
                a == null || b == null -> "Use seconds or m:ss, with up to three decimal places."
                a >= b -> "End must be later than start."
                b > (info?.durationMs ?: 0) -> "End exceeds the file duration."
                else -> null
            }
        }

    fun edit(isStart: Boolean, text: String) {
        if (isStart) from = text else until = text
        if (rangeError == null) {
            start = LocalTime.parse(from)!!
            end = LocalTime.parse(until)!!
        }
    }

    fun job(): LocalJob? {
        val source = uri ?: return null
        val media = info ?: return null
        if (rangeError != null) return null
        return LocalJob(source, media, start, end, format, audioOnly)
    }

    override fun onCleared() {
        ++generation
        backend?.cancel()
        super.onCleared()
    }
}

@Composable
fun LocalConverter() {
    val model: LocalConverterModel = viewModel()
    val context = LocalContext.current
    val status by Jobs.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // The editor is session-scoped. The export service receives its own read grant.
        if (uri != null) model.open(uri)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val info = model.info
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info?.title ?: "Source file", style = MaterialTheme.typography.titleMedium)
                Text(if (info == null) "Choose an audio or video file to trim or convert."
                    else "${if (info.video) "Video" else "Audio"}  •  ${LocalTime.format(info.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) {
                    Text(if (model.uri == null) "Choose file" else "Change file")
                }
                if (model.loading) {
                    StudioProgress(null)
                    Text(if (info == null) "Reading source…" else "Building waveform…")
                }
                model.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.uri?.let(model::open) }) { Text("Try again") }
                }
            }
        }
        if (info != null) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trim selection", style = MaterialTheme.typography.titleMedium)
                    Text("Drag the start and end markers, or enter exact times below.",
                        style = MaterialTheme.typography.bodyMedium)
                    LocalPreview(model, info)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true, false).forEach { isStart ->
                            OutlinedTextField(if (isStart) model.from else model.until,
                                { model.edit(isStart, it) }, Modifier.weight(1f), singleLine = true,
                                isError = model.rangeError != null,
                                label = { Text(if (isStart) "Start" else "End") })
                        }
                    }
                    model.rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        ?: Text("Selected: ${LocalTime.format(model.end - model.start)}",
                            style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Output", style = MaterialTheme.typography.titleMedium)
                    if (info.video && info.audio) {
                        Row(Modifier.fillMaxWidth().toggleable(model.audioOnly, role = Role.Checkbox,
                            onValueChange = model::setTrack).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(model.audioOnly, onCheckedChange = null)
                            Text("Audio only")
                        }
                    }
                    LocalFormatMenu(model)
                    if (info.video && model.format == LocalFormat.ORIGINAL &&
                        (model.start > 0 || model.end < info.durationMs))
                        Text("Original video cuts can begin at a nearby keyframe. Choose a video codec for a precise cut.",
                            style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = model.rangeError == null && !status.running,
                onClick = {
                    model.job()?.let { job ->
                        DownloadService.startLocal(context, job)
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }) { Text("Export selection") }
        }
    }
}

@Composable
private fun LocalFormatMenu(model: LocalConverterModel) {
    var expanded by remember { mutableStateOf(false) }
    val choices = remember(model.audioOnly, model.info?.video) {
        ConverterOutput(model.info?.video == true, model.audioOnly).formats
    }
    fun label(value: LocalFormat) = when (value) {
        LocalFormat.ORIGINAL -> "Keep original codec"
        LocalFormat.H264 -> "H.264 · MP4"
        LocalFormat.VP9 -> "VP9 · WebM"
        LocalFormat.AAC -> "AAC · M4A"
        LocalFormat.OPUS -> "Opus · Ogg"
        else -> value.name
    }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Format: ${label(model.format)}  ▾") }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { format ->
                DropdownMenuItem(text = { Text(label(format)) }, onClick = {
                    model.choose(format)
                    expanded = false
                })
            }
        }
    }
}
