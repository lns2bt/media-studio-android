package dev.bit.media

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Info(val title: String, val duration: Int?, val video: Boolean, val audio: Boolean)
enum class Mode { BOTH, VIDEO, AUDIO }
data class JobSpec(
    val url: String, val info: Info, val mode: Mode, val convert: Boolean,
    val start: Int, val end: Int?, val localTrim: Boolean = false
)

data class JobStatus(
    val stage: String = "Ready", val percent: Int? = null,
    val running: Boolean = false, val uri: Uri? = null
)

object Jobs {
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
                uri = store.getString("uri", null)?.let(Uri::parse)
            )
        }
    }

    internal fun report(status: JobStatus) {
        val previous = current.value
        current.value = status
        if (!status.running || !previous.running) {
            history.edit().putBoolean("active", status.running)
                .putString("stage", if (status.running) null else status.stage)
                .putString("uri", status.uri?.toString()).apply()
        }
    }
}

class ExtractorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Jobs.restore(getSharedPreferences("last_job", MODE_PRIVATE))
    }
}
