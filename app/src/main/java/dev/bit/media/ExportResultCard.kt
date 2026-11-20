package dev.bit.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ExportRow(val name: String, val folder: String, val volume: String, val mime: String)
private class MissingExport : Exception()

private fun ContentResolver.exportRow(uri: Uri): ExportRow {
    val columns = arrayOf(MediaColumns.DISPLAY_NAME, MediaColumns.RELATIVE_PATH, MediaColumns.VOLUME_NAME, MediaColumns.MIME_TYPE)
    return query(uri, columns, null, null, null)?.use {
        if (!it.moveToFirst()) throw MissingExport()
        ExportRow(it.getString(0), it.getString(1), it.getString(2), it.getString(3) ?: "application/octet-stream")
    } ?: error("No MediaStore cursor")
}

private sealed interface RenameResult {
    data class Saved(val row: ExportRow) : RenameResult
    data class Changed(val row: ExportRow) : RenameResult
    data class Conflict(val suggestion: String?) : RenameResult
}

private fun ContentResolver.renameExport(uri: Uri, expected: ExportRow, base: String): RenameResult {
    val current = exportRow(uri)
    if (current != expected) return RenameResult.Changed(current)
    val parts = ExportName.split(current.name)
    require(parts.problem(base) == null)
    val requested = base + parts.suffix
    if (requested == current.name) return RenameResult.Saved(current)
    val siblings = query(
        MediaStore.Files.getContentUri(current.volume), arrayOf(MediaColumns.DISPLAY_NAME),
        "${MediaColumns.RELATIVE_PATH} = ? AND ${BaseColumns._ID} != ?",
        arrayOf(current.folder, ContentUris.parseId(uri).toString()), null
    )?.use { cursor -> buildSet<String> { while (cursor.moveToNext()) cursor.getString(0)?.let { add(it) } } }
        ?: error("Cannot check folder")
    val available = parts.availableBase(base, siblings)
    if (available != base) return RenameResult.Conflict(available)

    val values = ContentValues().apply { put(MediaColumns.DISPLAY_NAME, requested) }
    val count = update(uri, values, "${MediaColumns.DISPLAY_NAME} = ? AND ${MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(current.name, current.folder))
    val actual = exportRow(uri)
    if (count != 1 && actual == current) error("Rename was declined")
    return if (count == 1) RenameResult.Saved(actual) else RenameResult.Changed(actual)
}

@Composable
fun ExportResultCard(context: Context, uri: Uri, onEdit: (Uri) -> Unit) {
    key(uri) { ExportCardContent(context, uri, onEdit) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportCardContent(context: Context, uri: Uri, onEdit: (Uri) -> Unit) {
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var row by remember { mutableStateOf<ExportRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf<String?>(null) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val draft = field.text
    var notice by remember { mutableStateOf<String?>(null) }
    fun accept(actual: ExportRow) {
        if (editingName != null && editingName != actual.name) {
            editingName = null
            notice = "The file name changed. Review the current name before renaming."
        }
        row = actual
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: MissingExport) {
                row = null
                editingName = null
                notice = "This export is no longer available."
            } catch (_: ActivityNotFoundException) {
                notice = "No app is available for this action."
            } catch (_: Exception) {
                row = null
                notice = "Could not access the export. Check that it is still in Downloads and try again."
            } finally {
                busy = false
            }
        }
    }
    fun refresh() = runAction { accept(withContext(Dispatchers.IO) { resolver.exportRow(uri) }) }
    LaunchedEffect(uri) { refresh() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Export result", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            val current = row
            if (current == null) {
                if (notice == null) Text("Loading file details…")
                else OutlinedButton(onClick = ::refresh, enabled = !busy) { Text("Retry") }
            } else {
                Text(current.name, style = MaterialTheme.typography.bodyLarge)
                val parts = ExportName.split(current.name)
                if (editingName != null) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(editingName) { focus.requestFocus() }
                    val problem = parts.problem(draft)
                    fun save() {
                        if (problem != null || busy) return
                        val base = draft
                        runAction {
                            when (val result = withContext(Dispatchers.IO) { resolver.renameExport(uri, current, base) }) {
                                is RenameResult.Saved -> {
                                    editingName = null
                                    row = result.row
                                    notice = "Saved as ${result.row.name}."
                                }
                                is RenameResult.Changed -> {
                                    editingName = null
                                    row = result.row
                                    notice = "File details changed. Review the current name and try again."
                                }
                                is RenameResult.Conflict -> {
                                    result.suggestion?.let { field = TextFieldValue(it, TextRange(0, it.length)) }
                                    notice = if (result.suggestion == null) "Name already in use. Choose another name."
                                        else "Name already in use. Save to use ${result.suggestion}${parts.suffix}, or choose another name."
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        field, { field = it; notice = null }, Modifier.fillMaxWidth().focusRequester(focus),
                        enabled = !busy, singleLine = true, label = { Text("File name") },
                        isError = problem != null, supportingText = {
                            Text(problem ?: if (parts.suffix.isEmpty()) "This file has no extension." else "Extension stays ${parts.suffix}")
                        }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() })
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::save, enabled = !busy && problem == null) { Text("Save name") }
                        TextButton(onClick = { editingName = null; notice = null }, enabled = !busy) { Text("Cancel") }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            field = TextFieldValue(parts.base, TextRange(0, parts.base.length))
                            editingName = current.name
                            notice = null
                        }, enabled = !busy) { Text("Rename") }
                        for (share in listOf(false, true)) {
                            OutlinedButton(onClick = {
                                runAction {
                                    val actual = withContext(Dispatchers.IO) { resolver.exportRow(uri) }
                                    accept(actual)
                                    val intent = if (share) Intent(Intent.ACTION_SEND).setType(actual.mime)
                                        .putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, actual.mime)
                                    intent.clipData = ClipData.newRawUri(actual.name, uri)
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    val launch = if (share) Intent.createChooser(intent, "Share file") else intent
                                    if (context !is Activity) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(launch)
                                }
                            }, enabled = !busy) { Text(if (share) "Share" else "Open") }
                        }
                    }
                    // GIF and WebP exports are images; the converter edits audio and video only.
                    if (!current.mime.startsWith("image/")) FilledTonalButton(onClick = {
                        runAction {
                            accept(withContext(Dispatchers.IO) { resolver.exportRow(uri) })
                            onEdit(uri)
                        }
                    }, enabled = !busy) { Text("Continue editing") }
                }
            }
            notice?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
