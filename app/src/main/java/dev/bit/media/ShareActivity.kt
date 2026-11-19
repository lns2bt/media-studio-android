package dev.bit.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** One-tap choices offered when a link is shared to Media Studio. */
internal enum class Preset { AUDIO, VIDEO }

/** Video quick downloads stay phone-sized; the full editor still offers every height. */
private const val QUICK_HEIGHT = 1080

/**
 * A bottom sheet over the sending app: shows what the link is, then starts an audio or
 * video download with one tap. Detailed choices hand off to [MainActivity].
 */
class ShareActivity : ComponentActivity() {
    private val form by lazy { ViewModelProvider(this)[MediaForm::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            val link = firstLink(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
            if (link == null) {
                Toast.makeText(this, "No web link found in the shared text.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            form.editUrl(link)
        }
        setContent {
            StudioTheme { ShareSheet(form, onMore = ::openEditor, onClose = ::finish) }
        }
    }

    private fun openEditor() {
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, form.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(form: MediaForm, onMore: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("share_sheet", Context.MODE_PRIVATE) }
    var last by remember { mutableStateOf(Preset.entries.firstOrNull { it.name == prefs.getString("preset", null) }) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val status by Jobs.state.collectAsStateWithLifecycle()
    var useLinkStart by rememberSaveable { mutableStateOf(true) }
    var pending by remember { mutableStateOf<JobSpec?>(null) }

    fun close(then: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { then() }
    }
    fun launch(spec: JobSpec) {
        DownloadService.start(context, spec)
        Toast.makeText(context, "Download started. A notification appears when it's saved.", Toast.LENGTH_SHORT).show()
        close(onClose)
    }
    // The sheet closes right away, so the notification is the only feedback: ask before the first start.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let(::launch)
        pending = null
    }
    fun start(preset: Preset, spec: JobSpec) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        prefs.edit().putString("preset", preset.name).apply()
        last = preset
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pending = spec
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launch(spec)
    }

    val info = form.info
    val duration = info?.duration
    val linkStart = form.linkStart
    val start = linkStart?.takeIf { useLinkStart && (duration == null || it < duration) } ?: 0
    val fraction = if (duration != null && duration > 0) (duration - start).toDouble() / duration else 1.0
    val cap = info?.qualities?.takeIf { q -> q.any { it > QUICK_HEIGHT } }?.let { QUICK_HEIGHT }
    fun size(mode: Mode, height: Int?) = info?.let { MediaMeta.estimate(it.formats, mode, height, fraction) }
        ?.let { Formatter.formatShortFileSize(context, it) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Media Studio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SourceHeader(form)
            if (linkStart != null && (duration == null || linkStart < duration)) {
                FilterChip(useLinkStart, { useLinkStart = !useLinkStart },
                    label = { Text("Start at ${FormTime.format(linkStart)}") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val ready = info != null && !status.running
                val audioMode = Mode.AUDIO
                val videoMode = if (info?.audio == false) Mode.VIDEO else Mode.BOTH
                PresetTile(AudioSymbol, "Audio", listOfNotNull("MP3", size(audioMode, null)).joinToString(" · "),
                    highlighted = last == Preset.AUDIO, enabled = ready && audioMode.available(info)) {
                    info?.let { start(Preset.AUDIO, JobSpec(form.url.trim(), it, audioMode, true, start, null)) }
                }
                val shown = info?.qualities?.firstOrNull { it <= (cap ?: Int.MAX_VALUE) }
                PresetTile(VideoSymbol, "Video", listOfNotNull(shown?.let { "${it}p" } ?: "Best",
                    size(videoMode, cap)).joinToString(" · "),
                    highlighted = last == Preset.VIDEO, enabled = ready && videoMode.available(info)) {
                    info?.let { start(Preset.VIDEO, JobSpec(form.url.trim(), it, videoMode, false, start, null, height = cap)) }
                }
            }
            if (status.running) Hint("Another export is running (${status.stage.lowercase()}). Start this one when it has finished.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { close(onMore) }) { Text("More options…") }
            }
        }
    }
}

@Composable
private fun SourceHeader(form: MediaForm) {
    val info = form.info
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (info == null || info.thumbnail != null) {
            Box(Modifier.width(136.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                form.thumbnail?.let {
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                info != null -> {
                    Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Hint(listOfNotNull(info.uploader, info.duration?.let(FormTime::format)).joinToString("  •  "))
                }
                form.error != null -> {
                    Text("Could not read this link", style = MaterialTheme.typography.titleMedium)
                    Text(form.error.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 4,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = form::inspect) { Text("Retry") }
                }
                else -> {
                    Text("Reading link…", style = MaterialTheme.typography.titleMedium)
                    StudioProgress(null)
                }
            }
        }
    }
}

@Composable
private fun RowScope.PresetTile(icon: ImageVector, title: String, detail: String, highlighted: Boolean,
                                enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.weight(1f).heightIn(min = 104.dp), enabled = enabled, shape = MaterialTheme.shapes.large,
        colors = if (highlighted) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
