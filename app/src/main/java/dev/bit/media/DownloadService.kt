package dev.bit.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Size
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.Executors

class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 101
        private const val DONE_CHANNEL = "finished"
        internal const val DONE_ID = 102
        /** Set on activity intents from the result notification so the activity can dismiss it. */
        internal const val FROM_RESULT = "dev.bit.media.FROM_RESULT"
        private const val CANCEL = "dev.bit.media.CANCEL"
        fun startLocal(context: Context, job: LocalJob) {
            context.startForegroundService(Intent(context, DownloadService::class.java).apply {
                data = job.uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("title", job.info.title)
                putExtra("durationMs", job.info.durationMs)
                putExtra("video", job.info.video)
                putExtra("audio", job.info.audio)
                putExtra("audioOnly", job.audioOnly)
                putExtra("format", job.format.name)
                putExtra("startMs", job.startMs)
                putExtra("endMs", job.endMs)
                putExtra("name", job.name)
            })
        }
        fun start(context: Context, job: JobSpec) {
            context.startForegroundService(Intent(context, DownloadService::class.java).apply {
                putExtra("url", job.url)
                putExtra("title", job.info.title)
                putExtra("duration", job.info.duration ?: -1)
                putExtra("video", job.info.video)
                putExtra("audio", job.info.audio)
                putExtra("mode", job.mode.name)
                putExtra("convert", job.convert)
                putExtra("localTrim", job.localTrim)
                putExtra("start", job.start)
                putExtra("end", job.end ?: -1)
                putExtra("height", job.height ?: -1)
                putExtra("thumbnail", job.info.thumbnail)
                putExtra("name", job.name)
            })
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(CANCEL))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val cancellation = Executors.newSingleThreadExecutor()
    private var backend: Backend? = null
    private var stopping = false
    private var lastTick = 0L
    private var lastStage = ""
    private var lastPercent: Int? = null
    private var foregroundType = 0
    private var retry: Intent? = null
    @Volatile private var artwork: Bitmap? = null
    private var title = ""
    private var localExport = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, "Media exports", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(DONE_CHANNEL, "Finished exports", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Saved files and failed exports"
            },
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            val current = backend
            if (current == null) stopSelf() else if (!stopping) {
                stopping = true
                publish("Cancelling", null)
                cancellation.execute { current.cancel() }
            }
            return START_NOT_STICKY
        }
        if (backend != null) {
            // A second start request (e.g. Retry) must still satisfy the foreground contract.
            startForeground(ID, notification(lastStage.ifBlank { "Starting" }, lastPercent), foregroundType)
            // Only one export runs at a time; say so instead of dropping the request silently.
            if (intent != null) Toast.makeText(this, "Another export is running. Start this one when it has finished.",
                Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        title = intent.getStringExtra("title").orEmpty()
        val local = intent.data?.let { uri ->
            LocalJob(uri, LocalInfo(title, intent.getLongExtra("durationMs", 0),
                intent.getBooleanExtra("video", false), intent.getBooleanExtra("audio", false)),
                intent.getLongExtra("startMs", 0), intent.getLongExtra("endMs", 0),
                LocalFormat.valueOf(requireNotNull(intent.getStringExtra("format"))), intent.getBooleanExtra("audioOnly", false),
                intent.getStringExtra("name"))
        }
        val job = intent.getStringExtra("url")?.let { url -> JobSpec(url, Info(title, intent.getIntExtra("duration", -1).takeIf { it >= 0 },
            intent.getBooleanExtra("video", false), intent.getBooleanExtra("audio", false)),
            Mode.valueOf(requireNotNull(intent.getStringExtra("mode"))), intent.getBooleanExtra("convert", false),
            intent.getIntExtra("start", 0), intent.getIntExtra("end", -1).takeIf { it >= 0 },
            intent.getBooleanExtra("localTrim", false), intent.getIntExtra("height", -1).takeIf { it > 0 },
            intent.getStringExtra("name")) }
        if (job == null && local == null) { stopSelf(); return START_NOT_STICKY }
        localExport = local != null
        // Local sources hold a temporary read grant, so only link exports can be retried from a notification.
        retry = if (job != null) Intent(intent) else null
        artwork = null
        lastStage = ""
        lastPercent = null
        notifications.cancel(DONE_ID)
        val runner = Backend(applicationContext)
        backend = runner
        stopping = false
        foregroundType = if (local != null && Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ID, notification("Starting", null), foregroundType)
        intent.getStringExtra("thumbnail")?.let { link ->
            Thread {
                val image = Thumbnails.load(link, 256) ?: return@Thread
                main.post {
                    if (backend !== runner) return@post
                    artwork = image
                    if (!stopping) notifications.notify(ID, notification(lastStage.ifBlank { "Starting" }, lastPercent))
                }
            }.apply { isDaemon = true }.start()
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaExtractor:activeExport").apply {
                acquire(6L * 60 * 60 * 1000)
            }
        Jobs.report(JobStatus("Starting", running = true))
        executor.execute {
            val result = runCatching {
                val update: (String, Int?) -> Unit = { stage, percent ->
                    main.post { if (backend === runner && !stopping) publish(stage, percent) }
                }
                if (local != null) runner.runLocal(local, update) else runner.run(requireNotNull(job), update)
            }
            val saved = result.getOrNull()?.let { uri -> runCatching { saved(uri) }.getOrNull() }
            main.post {
                if (backend !== runner) return@post
                backend = null
                val status = result.fold(
                    { JobStatus("Saved to Downloads", uri = it) },
                    { if (stopping) JobStatus("Cancelled")
                      else JobStatus("Export failed", error = it.localizedMessage ?: "Export failed") }
                )
                Jobs.report(status)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                when {
                    result.isSuccess -> saved?.let { notifications.notify(DONE_ID, it) }
                    !stopping -> notifications.notify(DONE_ID,
                        failed(result.exceptionOrNull()?.localizedMessage ?: "Export failed"))
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(stage: String, pct: Int?) {
        Jobs.report(JobStatus(stage, pct, true))
        val now = SystemClock.elapsedRealtime()
        lastPercent = pct
        if (stage != lastStage || now - lastTick >= 750) {
            lastTick = now
            lastStage = stage
            notifications.notify(ID, notification(stage, pct))
        }
    }

    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
        if (localExport) action = "dev.bit.media.OPEN_CONVERTER"
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }, flags)

    private fun notification(stage: String, percent: Int?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_studio).setLargeIcon(artwork)
            .setContentTitle(title.ifBlank { "Media Studio" }).setContentText(stage)
            .setContentIntent(openApp()).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }

    /** While the app is on screen its result card already shows the outcome; stay quiet. */
    private fun resultChannel() = if (Jobs.visible) CHANNEL else DONE_CHANNEL

    /** Runs on the worker thread: reads the published row and its artwork. */
    private fun saved(uri: Uri): Notification {
        var name = title.ifBlank { "Media" }
        var mime = "application/octet-stream"
        var size = 0L
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE), null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                name = row.getString(0) ?: name
                size = row.getLong(1)
                mime = row.getString(2) ?: mime
            }
        }
        val art = runCatching { contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull() ?: artwork
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val send = Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(name, uri) }, "Share file")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val edit = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .putExtra(FROM_RESULT, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val details = if (size > 0) "Saved to Downloads · ${Formatter.formatShortFileSize(this, size)}" else "Saved to Downloads"
        return Notification.Builder(this, resultChannel())
            .setSmallIcon(R.drawable.ic_stat_done).setLargeIcon(art)
            .setContentTitle(name).setContentText(details)
            .setContentIntent(openApp()).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Open", PendingIntent.getActivity(this, 10, view, flags)).build())
            .addAction(Notification.Action.Builder(null, "Share", PendingIntent.getActivity(this, 11, send, flags)).build())
            .addAction(Notification.Action.Builder(null, "Edit", PendingIntent.getActivity(this, 12, edit, flags)).build())
            .build()
    }

    private fun failed(reason: String): Notification {
        val problem = Problems.explain(reason)
        val builder = Notification.Builder(this, resultChannel())
            .setSmallIcon(android.R.drawable.stat_notify_error).setLargeIcon(artwork)
            .setContentTitle(title.ifBlank { "Media Studio" }).setContentText("Export failed")
            .setStyle(Notification.BigTextStyle().bigText("Export failed. ${problem.message}"))
            .setContentIntent(openApp()).setAutoCancel(true)
        retry?.let {
            builder.addAction(Notification.Action.Builder(null, "Retry",
                PendingIntent.getForegroundService(this, 13, it, flags)).build())
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        backend?.let { runner ->
            backend = null
            cancellation.execute { runner.cancel() }
        }
        Jobs.report(JobStatus("Android stopped this long-running export. Try a shorter range."))
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.notify(DONE_ID, failed("Android stopped this long-running export. Try a shorter range."))
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        backend?.let { runner ->
            backend = null
            cancellation.execute { runner.cancel() }
            Jobs.report(JobStatus("Export interrupted"))
        }
        executor.shutdown()
        cancellation.shutdown()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
