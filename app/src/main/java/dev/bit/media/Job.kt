package dev.bit.media

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Info(
    val title: String, val duration: Int?, val video: Boolean, val audio: Boolean,
    val uploader: String? = null, val thumbnail: String? = null,
    val chapters: List<Chapter> = emptyList(), val formats: List<SourceFormat> = emptyList(),
) {
    val qualities: List<Int> get() = MediaMeta.qualities(formats)
}
enum class Mode { BOTH, VIDEO, AUDIO }
data class JobSpec(
    val url: String, val info: Info, val mode: Mode, val convert: Boolean,
    val start: Int, val end: Int?, val localTrim: Boolean = false,
    /** Highest video height to select; null selects the best available. */
    val height: Int? = null,
    /** File name chosen by the user, without extension; null derives it from the title. */
    val name: String? = null,
)

data class JobStatus(
    val stage: String = "Ready", val percent: Int? = null,
    val running: Boolean = false, val uri: Uri? = null,
    /** The engine's error text when the export failed. */
    val error: String? = null,
)

object Jobs {
    /** True while the app is on screen, so finished exports need no audible alert. */
    @Volatile var visible = false
    private val current = MutableStateFlow(JobStatus())
    val state = current.asStateFlow()
    private lateinit var history: SharedPreferences

    internal fun restore(store: SharedPreferences) {
        history = store
        if (store.getBoolean("active", false)) {
            report(JobStatus("Previous export interrupted. Start it again."))
        } else {
            current.value = JobStatus(
                store.getString("stage", "Ready") ?: "Ready",
                uri = store.getString("uri", null)?.let(Uri::parse),
                error = store.getString("error", null),
            )
        }
    }

    /** Hides a finished result (the file stays in Downloads). Returns it for undo, or null. */
    internal fun dismissResult(): JobStatus? {
        val previous = current.value
        if (previous.running || (previous.uri == null && previous.stage == "Ready")) return null
        report(JobStatus())
        return previous
    }

    internal fun restoreResult(status: JobStatus) {
        if (!current.value.running) report(status)
    }

    internal fun report(status: JobStatus) {
        val previous = current.value
        current.value = status
        if (!status.running || !previous.running) {
            history.edit().putBoolean("active", status.running)
                .putString("stage", if (status.running) null else status.stage)
                .putString("uri", status.uri?.toString())
                .putString("error", if (status.running) null else status.error).apply()
        }
    }
}

class ExtractorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Jobs.restore(getSharedPreferences("last_job", MODE_PRIVATE))
    }
}
