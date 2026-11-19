package dev.bit.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors

class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 101
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
    private var title = ""
    private var localExport = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Media exports", NotificationManager.IMPORTANCE_LOW))
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
        if (backend != null) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        title = intent.getStringExtra("title").orEmpty()
        val local = intent.data?.let { uri ->
            LocalJob(uri, LocalInfo(title, intent.getLongExtra("durationMs", 0),
                intent.getBooleanExtra("video", false), intent.getBooleanExtra("audio", false)),
                intent.getLongExtra("startMs", 0), intent.getLongExtra("endMs", 0),
                LocalFormat.valueOf(requireNotNull(intent.getStringExtra("format"))), intent.getBooleanExtra("audioOnly", false))
        }
        val job = intent.getStringExtra("url")?.let { url -> JobSpec(url, Info(title, intent.getIntExtra("duration", -1).takeIf { it >= 0 },
            intent.getBooleanExtra("video", false), intent.getBooleanExtra("audio", false)),
            Mode.valueOf(requireNotNull(intent.getStringExtra("mode"))), intent.getBooleanExtra("convert", false),
            intent.getIntExtra("start", 0), intent.getIntExtra("end", -1).takeIf { it >= 0 },
            intent.getBooleanExtra("localTrim", false)) }
        if (job == null && local == null) { stopSelf(); return START_NOT_STICKY }
        localExport = local != null
        val runner = Backend(applicationContext)
        backend = runner
        stopping = false
        val type = if (local != null && Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ID, notification("Starting", null), type)
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
            main.post {
                if (backend !== runner) return@post
                backend = null
                val status = result.fold(
                    { JobStatus("Saved to Downloads", uri = it) },
                    { JobStatus(if (stopping) "Cancelled" else "Failed: ${it.localizedMessage ?: "Export failed"}") }
                )
                Jobs.report(status)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(stage: String, pct: Int?) {
        Jobs.report(JobStatus(stage, pct, true))
        val now = SystemClock.elapsedRealtime()
        if (stage != lastStage || now - lastTick >= 750) {
            lastTick = now
            lastStage = stage
            notifications.notify(ID, notification(stage, pct))
        }
    }

    private fun notification(stage: String, percent: Int?): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            if (localExport) action = "dev.bit.media.OPEN_CONVERTER"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, flags)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(CANCEL), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { "Media Studio" }).setContentText(stage)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        backend?.let { runner ->
            backend = null
            cancellation.execute { runner.cancel() }
        }
        Jobs.report(JobStatus("Android stopped this long-running export. Try a shorter range."))
        stopForeground(STOP_FOREGROUND_REMOVE)
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
