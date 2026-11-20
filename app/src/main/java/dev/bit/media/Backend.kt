package dev.bit.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

class Backend(context: Context) {
    private val app = context.applicationContext
    private val bin = File(app.applicationInfo.nativeLibraryDir)
    private val base = File(app.noBackupFilesDir, YoutubeDL.baseName)
    private val python = File(bin, "libpython.so").path
    private val active = mutableSetOf<Task>()

    private class Task {
        var group: Int? = null
        val signal = CancellationSignal()
        @Volatile var cancelled = false
        fun check() {
            if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
        }
    }

    fun cancel() = synchronized(active) {
        active.forEach {
            it.cancelled = true
            it.signal.cancel()
            it.group?.let(::killGroup)
        }
    }

    private fun killGroup(id: Int) {
        runCatching { Os.kill(-id, OsConstants.SIGKILL) }
    }

    private fun <T> work(body: (Task, File) -> T): T {
        engineLock.readLock().lock()
        try {
            return session(body)
        } finally {
            engineLock.readLock().unlock()
        }
    }

    private fun <T> session(body: (Task, File) -> T): T {
        val task = Task()
        synchronized(active) { active.add(task) }
        val root = File(app.cacheDir, "media-extractor")
        val dir = File(root, UUID.randomUUID().toString())
        try {
            synchronized(storageLock) {
                if (cleaned.add(root.path)) root.listFiles()?.forEach { old ->
                    // MediaStore also expires abandoned pending rows if the cache
                    // was reclaimed or the process died before writing the journal.
                    runCatching {
                        val row = File(old, "pending-download").readText().toLong()
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row)
                        app.contentResolver.delete(uri, "${MediaStore.MediaColumns.IS_PENDING} = 1", null)
                    }
                    old.deleteRecursively()
                }
                check(dir.mkdirs()) { "Cannot create temporary storage." }
            }
            YoutubeDL.init(app)
            FFmpeg.init(app)
            task.check()
            return body(task, dir)
        } finally {
            synchronized(active) { active.remove(task) }
            dir.deleteRecursively()
        }
    }

    // yt-dlp may spawn FFmpeg or QuickJS. Give the entire command its own process
    // group so cancellation also stops children blocked on a network read.
    private fun command(task: Task, dir: File, args: List<String>, line: (String) -> Unit) {
        task.check()
        val builder = ProcessBuilder(listOf(python, "-c", LAUNCHER) + args)
            .directory(dir).redirectErrorStream(true)
        val pyHome = File(base, "packages/python/usr").path
        builder.environment().apply {
            put("LD_LIBRARY_PATH", "$pyHome/lib:${base.path}/packages/ffmpeg/usr/lib:${bin.path}")
            put("SSL_CERT_FILE", "$pyHome/etc/tls/cert.pem")
            put("PYTHONHOME", pyHome)
            put("HOME", dir.path)
            put("TMPDIR", dir.path)
            put("PATH", "${get("PATH")}:${bin.path}")
            put("PYTHONIOENCODING", "UTF-8")
        }
        val p = builder.start()
        var group: Int? = null
        val diagnostics = ArrayDeque<String>()
        try {
            p.outputStream.close()
            p.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val first = reader.readLine()
                group = first?.removePrefix("GROUP:")?.toIntOrNull()?.takeIf { it > 1 }
                    ?: throw IOException("Could not start the media engine: ${first.orEmpty().take(200)}")
                synchronized(active) {
                    task.group = group
                    task.check()
                }
                reader.forEachLine {
                    task.check()
                    if (it.isNotBlank() && !it.startsWith('{')) {
                        if (diagnostics.size == 6) diagnostics.removeFirst()
                        diagnostics.addLast(it.take(400))
                    }
                    line(it)
                }
            }
            task.check()
            if (p.waitFor() != 0) {
                val detail = diagnostics.lastOrNull { it.startsWith("ERROR:") } ?: diagnostics.lastOrNull()
                throw IOException(detail ?: "Media processing failed.")
            }
        } finally {
            synchronized(active) {
                if (p.isAlive) {
                    group?.let(::killGroup)
                    p.destroyForcibly()
                }
                task.group = null
            }
        }
    }

    private val ytDlp get() = File(File(base, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin).path

    private fun ytDlpVersion(task: Task, dir: File): String? {
        var yt: String? = null
        runCatching {
            command(task, dir, listOf(python, ytDlp, "--version")) { if (yt == null && it.isNotBlank()) yt = it.trim() }
        }
        return yt
    }

    /** Versions of the installed yt-dlp and FFmpeg, each null when it cannot be read. */
    fun versions(): Pair<String?, String?> = work { task, dir ->
        var ff: String? = null
        runCatching {
            command(task, dir, listOf(File(bin, "libffmpeg.so").path, "-version")) { line ->
                if (ff == null && line.startsWith("ffmpeg version ")) ff = line.split(' ').getOrNull(2)
                    ?.let { if (it.length > 1 && it[0] == 'n' && it[1].isDigit()) it.drop(1) else it }
            }
        }
        ytDlpVersion(task, dir) to ff
    }

    /**
     * Replaces yt-dlp with its latest stable release from GitHub. Waits briefly for running
     * work and refuses while an export holds the engine. Returns whether a new release was
     * installed, and the version now in use.
     */
    fun updateYtDlp(): Pair<Boolean, String?> {
        if (!engineLock.writeLock().tryLock(5, TimeUnit.SECONDS))
            throw IOException("Media Studio is busy reading or exporting media. Try again when it has finished.")
        try {
            val before = work { task, dir -> ytDlpVersion(task, dir) }
            // The library compares the latest tag with its own record, which is empty for the bundled copy.
            before?.let {
                app.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit().putString("dlpVersion", it).commit()
            }
            val status = try {
                YoutubeDL.updateYoutubeDL(app)
            } catch (e: Exception) {
                throw IOException(e.cause?.localizedMessage ?: e.localizedMessage ?: "Could not download yt-dlp.", e)
            }
            val after = work { task, dir -> ytDlpVersion(task, dir) }
            return (status == YoutubeDL.UpdateStatus.DONE) to (after ?: before)
        } finally {
            engineLock.writeLock().unlock()
        }
    }

    private fun ytArgs(url: String, options: List<String>): List<String> {
        val uri = Uri.parse(url)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter an HTTP or HTTPS media link." }
        return listOf(
            python, ytDlp,
            "--ignore-config", "--no-cache-dir", "--no-playlist", "--socket-timeout", "30",
            "--ffmpeg-location", File(bin, "libffmpeg.so").path,
            "--js-runtimes", "quickjs:${File(bin, "libqjs.so").path}"
        ) + options + listOf("--", url)
    }

    fun inspect(url: String): Info = work { task, dir ->
        var data: JSONObject? = null
        val options = listOf("--flat-playlist", "--playlist-end", "1", "--dump-single-json")
        command(task, dir, ytArgs(url, options)) {
            if (it.startsWith('{')) data = JSONObject(it)
        }
        val item = data ?: throw IOException("No media information was returned.")
        require(!item.has("entries")) { "Please share a link to a single media item." }
        require(!item.optBoolean("is_live") && item.optString("live_status") !in setOf("is_live", "is_upcoming", "post_live")) {
            "Only on-demand media is supported."
        }
        var video = false
        var audio = false
        val seconds = item.optDouble("duration").takeIf { it.isFinite() && it > 0 }
        val variants = mutableListOf<SourceFormat>()
        fun track(f: JSONObject) {
            if (f.optBoolean("has_drm") || f.optString("protocol") == "mhtml") return
            // Generic HTTP extractors do not always report codecs. Unknown is
            // potentially available; ffprobe verifies the actual tracks later.
            val hasVideo = f.optString("vcodec") != "none"
            val hasAudio = f.optString("acodec") != "none"
            video = video || hasVideo
            audio = audio || hasAudio
            val bitrate = f.optDouble("tbr").takeIf { it.isFinite() && it > 0 }
            val bytes = f.optLong("filesize").takeIf { it > 0 } ?: f.optLong("filesize_approx").takeIf { it > 0 }
                ?: if (bitrate != null && seconds != null) (bitrate * 125 * seconds).toLong() else null
            variants += SourceFormat(f.optInt("height").takeIf { it > 0 }, hasVideo, hasAudio, bytes)
        }
        val formats = item.optJSONArray("formats")
        if (formats == null) track(item)
        else for (i in 0 until formats.length()) formats.optJSONObject(i)?.let(::track)
        require(video || audio) { "No downloadable audio or video tracks were found." }
        val duration = seconds?.let { ceil(it).toInt() }
        Info(item.optString("title").ifBlank { "Media" }, duration, video, audio,
            uploader = item.text("uploader") ?: item.text("channel"),
            thumbnail = (item.text("thumbnail") ?: item.optJSONArray("thumbnails")?.let { list ->
                (list.length() - 1 downTo 0).firstNotNullOfOrNull { list.optJSONObject(it)?.text("url") }
            })?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            chapters = chapters(item.optJSONArray("chapters"), duration),
            formats = variants)
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun chapters(list: JSONArray?, duration: Int?): List<Chapter> {
        if (list == null) return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val c = list.optJSONObject(i) ?: return@mapNotNull null
            val start = c.optDouble("start_time").takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val end = c.optDouble("end_time").takeIf { it.isFinite() } ?: return@mapNotNull null
            val a = floor(start).toInt()
            val b = ceil(end).toInt().let { if (duration != null) minOf(it, duration) else it }
            if (b <= a) null else Chapter(c.text("title") ?: "Chapter ${i + 1}", a, b)
        }
    }

    fun run(job: JobSpec, progress: (String, Int?) -> Unit): Uri = work { task, dir ->
        val end = job.end
        val duration = job.info.duration
        require(job.start >= 0 && (end == null || end > job.start) &&
            (duration == null || job.start < duration && (end == null || end <= duration))) { "Invalid time range." }
        val stage = when (job.mode) {
            Mode.BOTH -> "Downloading video and audio"
            Mode.VIDEO -> "Downloading video"
            Mode.AUDIO -> "Downloading audio"
        }
        // A quality cap falls back to the unrestricted choice when no variant fits it.
        val cap = job.height?.let { "[height<=$it]" }
        val selector = when (job.mode) {
            Mode.BOTH -> if (cap == null) "bv+ba/b" else "bv$cap+ba/b$cap/bv+ba/b"
            Mode.VIDEO -> if (cap == null) "bv/b" else "bv$cap/b$cap/bv/b"
            Mode.AUDIO -> "ba/b"
        }
        val bounded = job.start != 0 || end != null && end != duration
        val host = Uri.parse(job.url).host.orEmpty().lowercase()
        val xSection = bounded && !job.localTrim &&
            listOf("x.com", "twitter.com").any { host == it || host.endsWith(".$it") }
        // The input for an audio clip may contain video. Prefer the same working
        // X section source as BOTH; process() maps only the requested output track.
        val formats = when {
            !xSection -> selector
            job.mode == Mode.AUDIO -> "b[protocol=https][ext=mp4]/bv+ba/b/ba"
            else -> "b[protocol=https][ext=mp4]/$selector"
        }
        val opts = mutableListOf(
            "-f", formats, "--newline", "--progress", "--no-simulate",
            "--playlist-end", "1", "--match-filter", "!is_live & !is_upcoming",
            "--progress-template", "download:PERCENT:%(progress._percent_str)s",
            "-o", File(dir, "source.%(ext)s").path
        )
        if (bounded && !job.localTrim) {
            opts += listOf("--download-sections", "*${job.start}-${end ?: "inf"}")
        }
        progress(stage, null)
        command(task, dir, ytArgs(job.url, opts)) {
            when {
                it.startsWith("PERCENT:") -> {
                    val percent = it.substringAfter(':').trim().removeSuffix("%").toDoubleOrNull()
                    progress(stage, percent?.toInt()?.coerceIn(0, 100))
                }
                it.startsWith("[Merger]") -> progress("Merging tracks", null)
            }
        }
        val input = dir.listFiles()?.singleOrNull { it.isFile && it.name.startsWith("source.") }
            ?: throw IOException("No single downloadable media file was produced.")
        val output = process(task, dir, input, job, progress)
        publish(task, output, ExportName.base(job.info.title, job.name,
            if (bounded) clipLabel(job.start * 1000L, (end ?: duration)?.times(1000L)) else ""), job.mode == Mode.AUDIO, progress)
    }

    // SAF providers can expose slices or non-seekable pipes. Stage once per operation
    // so probing, accurate input seeking and container index reads all see the same bytes.
    private fun localSource(task: Task, dir: File, uri: Uri): File {
        require(uri.scheme in setOf("content", "file")) { "Select a local media file." }
        val source = File(dir, "input")
        val asset = app.contentResolver.openAssetFileDescriptor(uri, "r", task.signal)
            ?: throw IOException("Cannot open the selected file.")
        asset.use {
            it.createInputStream().use { input ->
                source.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    val polls = arrayOf(StructPollfd().apply {
                        fd = asset.fileDescriptor
                        events = OsConstants.POLLIN.toShort()
                    })
                    val pipe = OsConstants.S_ISFIFO(Os.fstat(asset.fileDescriptor).st_mode)
                    while (true) {
                        task.check()
                        if (pipe && Os.poll(polls, 200) == 0) continue
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        }
        return source
    }

    private data class Tracks(
        val format: JSONObject, val video: JSONObject?, val audio: JSONObject?, val duration: Long,
    )

    private fun localTracks(task: Task, dir: File, input: File): Tracks {
        val json = StringBuilder()
        command(task, dir, listOf(File(bin, "libffprobe.so").path, "-v", "error") + LOCAL_INPUT +
            listOf("-show_entries", "format=format_name,duration:format_tags=title,major_brand:stream=index,codec_type,codec_name,duration,sample_rate:stream_disposition=attached_pic",
                "-of", "json", input.path)) { json.appendLine(it) }
        val data = JSONObject(json.toString())
        val format = data.getJSONObject("format")
        val streams = data.getJSONArray("streams")
        val all = (0 until streams.length()).map(streams::getJSONObject)
        val video = all.firstOrNull { it.optString("codec_type") == "video" &&
            it.optJSONObject("disposition")?.optInt("attached_pic") != 1 }
        val audio = all.firstOrNull { it.optString("codec_type") == "audio" }
        require(video != null || audio != null) { "No audio or video stream was found." }
        val duration = (listOf(format, video, audio).filterNotNull().firstNotNullOfOrNull {
            it.optDouble("duration").takeIf { d -> d.isFinite() && d > 0 }
        } ?: throw IOException("Cannot determine the media duration."))
        return Tracks(format, video, audio, (duration * 1000).roundToLong().coerceAtLeast(1))
    }

    private fun localName(task: Task, uri: Uri): String? =
        if (uri.scheme == "content") app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null, task.signal)?.use { if (it.moveToFirst()) it.getString(0) else null }
        else uri.lastPathSegment

    fun inspectLocal(uri: Uri, onInfo: (LocalInfo) -> Unit = {}, onWave: (List<Float>) -> Unit): LocalInfo {
        val started = android.os.SystemClock.elapsedRealtime()
        val key = WaveCache.key(app, uri)
        if (key != null) WaveCache.get(app, key)?.let {
            onInfo(it.info)
            onWave(it.peaks)
            android.util.Log.d("StudioWave", "Cached editor ready in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            return it.info
        }
        return scanLocal(uri, key, {
            android.util.Log.d("StudioWave", "Editor ready in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            onInfo(it)
        }, onWave).also {
            android.util.Log.d("StudioWave", "Analysis finished in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        }
    }

    private fun scanLocal(uri: Uri, key: String?, onInfo: (LocalInfo) -> Unit,
                          onWave: (List<Float>) -> Unit): LocalInfo = work { task, dir ->
        val input = localSource(task, dir, uri)
        val tracks = localTracks(task, dir, input)
        val title = tracks.format.optJSONObject("tags")?.optString("title")?.takeIf { it.isNotBlank() }
            ?: localName(task, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Media"
        val audio = tracks.audio
        val info = LocalInfo(title, tracks.duration, tracks.video != null, audio != null)
        task.check()
        onInfo(info)
        if (audio == null) onWave(emptyList()) else try {
            // FFmpeg decodes one audio track; only a timestamp and channel-wide peak
            // leave the filter per block. Neither PCM nor a per-frame list is retained.
            val peaks = FloatArray(WaveCache.BINS)
            val rate = audio.optInt("sample_rate", 48000).coerceAtLeast(1)
            val block = (tracks.duration.toDouble() * rate / (1000 * peaks.size)).toInt().coerceIn(1, 65536)
            val filter = "asetpts=PTS-STARTPTS,asetnsamples=n=$block:p=0,astats=metadata=1:reset=1:measure_perchannel=none:measure_overall=Peak_level," +
                "ametadata=print:key=lavfi.astats.Overall.Peak_level:file='pipe\\:1'"
            var bucket = 0
            var lastUpdate = android.os.SystemClock.elapsedRealtime()
            command(task, dir, listOf(File(bin, "libffmpeg.so").path, "-nostdin", "-v", "error") + LOCAL_INPUT +
                listOf("-i", input.path, "-map", "0:${audio.getInt("index")}", "-af", filter, "-f", "null", "-")) { line ->
                if (line.startsWith("frame:")) {
                    val seconds = line.substringAfter("pts_time:").trim().toDoubleOrNull() ?: 0.0
                    bucket = (seconds * 1000 * peaks.size / tracks.duration).toInt().coerceIn(peaks.indices)
                } else if (line.startsWith("lavfi.astats.Overall.Peak_level=")) {
                    val db = line.substringAfter('=').toDoubleOrNull()
                    if (db != null && db.isFinite()) {
                        peaks[bucket] = maxOf(peaks[bucket], 10.0.pow(db / 20).toFloat().coerceIn(0f, 1f))
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 250) {
                        onWave(peaks.toList())
                        lastUpdate = now
                    }
                }
            }
            val envelope = peaks.toList()
            onWave(envelope)
            if (key != null && WaveCache.key(app, uri) == key) WaveCache.put(app, key, WaveCache.Entry(info, envelope))
        } catch (e: IOException) {
            task.check()
            onWave(emptyList())
        }
        task.check()
        info
    }

    // Validate the provider's suffix against the probed demuxer family.
    private fun originalExtension(tracks: Tracks, name: String?): String? {
        val formats = tracks.format.getString("format_name").split(',')
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        val brand = tracks.format.optJSONObject("tags")?.optString("major_brand")?.trim().orEmpty()
        val family = when {
            "mov" in formats -> listOf(when {
                brand == "qt" -> "mov"
                brand.startsWith("3g2") -> "3g2"
                brand.startsWith("3g") -> "3gp"
                brand.startsWith("M4A") -> "m4a"
                else -> "mp4"
            }, "mov", "mp4", "m4v", "m4a", "3gp", "3g2")
            "matroska" in formats -> listOf(if (tracks.video == null) "mka" else "mkv", "mkv", "mka", "webm")
            "ogg" in formats -> listOf("ogg", "oga", "ogv") +
                if (tracks.audio?.optString("codec_name") == "opus") listOf("opus") else emptyList()
            "mpegts" in formats -> listOf("ts", "m2ts", "mts")
            "mpeg" in formats -> listOf("mpg", "mpeg", "vob")
            "asf" in formats -> listOf("asf", "wmv", "wma")
            "aiff" in formats -> listOf("aiff", "aif", "aifc")
            else -> formats.filter { it in setOf("aac", "mp3", "flac", "wav", "avi", "flv", "amr",
                "ac3", "eac3", "ape", "wv", "caf") }
        }
        return ext?.takeIf { it in family } ?: family.firstOrNull()
    }

    fun runLocal(job: LocalJob, progress: (String, Int?) -> Unit): Uri = work { task, dir ->
        require(job.startMs >= 0 && job.endMs > job.startMs && job.endMs <= job.info.durationMs) { "Invalid time range." }
        progress("Reading source", null)
        val input = localSource(task, dir, job.uri)
        val tracks = localTracks(task, dir, input)
        require(job.endMs <= tracks.duration) { "The media duration changed. Select the file again." }
        val format = job.format
        val video = when (format) {
            LocalFormat.H264, LocalFormat.VP9, LocalFormat.GIF, LocalFormat.WEBP -> requireNotNull(tracks.video?.takeUnless { job.audioOnly }) {
                "Select a video track for this format."
            }
            LocalFormat.ORIGINAL -> tracks.video?.takeUnless { job.audioOnly }
            else -> null
        }
        // Animated images have no sound.
        val audio = tracks.audio?.takeUnless { format.animated }
        require(video != null || audio != null) { "No audio track was found." }
        val ac = audio?.optString("codec_name")
        val clipped = job.startMs != 0L || job.endMs != tracks.duration
        val sourceExt = if (format == LocalFormat.ORIGINAL) originalExtension(tracks, localName(task, job.uri)) else null
        val keepContainer = video != null || tracks.video == null
        if (!clipped && keepContainer && sourceExt != null) {
            return@work publish(task, input, ExportName.base(job.info.title, job.name, ""), video == null, progress, sourceExt)
        }
        val ext = when (format) {
            LocalFormat.ORIGINAL -> when {
                keepContainer && sourceExt != null && sourceExt != "ape" -> sourceExt
                video == null -> when (ac) {
                    "aac", "alac" -> "m4a"
                    "mp3", "flac", "opus", "ac3", "eac3" -> ac
                    "vorbis" -> "ogg"
                    else -> "mka"
                }
                else -> "mkv"
            }
            LocalFormat.MP3 -> "mp3"
            LocalFormat.AAC -> "m4a"
            LocalFormat.OPUS -> "opus"
            LocalFormat.FLAC -> "flac"
            LocalFormat.H264 -> "mp4"
            LocalFormat.VP9 -> "webm"
            LocalFormat.GIF, LocalFormat.WEBP -> AnimatedImage.extension(format)
        }
        val stage = if (format == LocalFormat.ORIGINAL) if (clipped) "Trimming original tracks" else "Copying tracks"
            else "Encoding ${format.name}"
        progress(stage, null)
        val out = File(dir, "result.$ext")
        val args = mutableListOf(File(bin, "libffmpeg.so").path, "-nostdin", "-v", "error")
        if (job.startMs > 0) args += listOf("-ss", "${job.startMs}ms")
        args += LOCAL_INPUT + listOf("-i", input.path)
        val length = job.endMs - job.startMs
        if (clipped) args += listOf("-t", "${length}ms")
        if (video != null) {
            args += listOf("-map", "0:${video.getInt("index")}")
            args += when (format) {
                LocalFormat.H264 -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                    "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-pix_fmt", "yuv420p")
                LocalFormat.VP9 -> listOf("-c:v", "libvpx-vp9", "-row-mt", "1", "-cpu-used", "5",
                    "-crf", "33", "-b:v", "0", "-pix_fmt", "yuv420p")
                LocalFormat.GIF, LocalFormat.WEBP -> AnimatedImage.outputArgs(format, job.motion)
                else -> listOf("-c:v", "copy")
            }
        }
        if (audio != null) {
            args += listOf("-map", "0:${audio.getInt("index")}")
            args += when (format) {
                LocalFormat.ORIGINAL -> listOf("-c:a", "copy")
                LocalFormat.MP3 -> listOf("-c:a", "libmp3lame", "-ac", "2", "-q:a", "2")
                LocalFormat.AAC, LocalFormat.H264 -> listOf("-c:a", "aac", "-ac", "2", "-ar", "48000", "-b:a", "192k")
                LocalFormat.OPUS, LocalFormat.VP9 -> listOf("-c:a", "libopus", "-ac", "2", "-ar", "48000", "-b:a", "128k")
                LocalFormat.FLAC -> listOf("-c:a", "flac")
                LocalFormat.GIF, LocalFormat.WEBP -> error("Animated images have no audio")
            }
        }
        args += listOf("-map_chapters", "-1")
        if (format == LocalFormat.ORIGINAL) when (ext) {
            "m4v" -> args += listOf("-f", "mp4")
            "aif", "aifc" -> args += listOf("-f", "aiff")
            "mts", "m2ts" -> args += listOf("-f", "mpegts", "-mpegts_m2ts_mode", "1")
        }
        if (ext in setOf("mp4", "m4a", "mov")) args += listOf("-movflags", "+faststart")
        args += listOf("-progress", "pipe:1", "-nostats", out.path)
        command(task, dir, args) {
            if (it.startsWith("out_time_us=")) it.substringAfter('=').toLongOrNull()?.let { us ->
                progress(stage, (us / 10.0 / length).toInt().coerceIn(0, 99))
            }
        }
        publish(task, out, ExportName.base(job.info.title, job.name,
            if (clipped) clipLabel(job.startMs, job.endMs) else ""), video == null, progress)
    }

    private fun process(task: Task, dir: File, input: File, job: JobSpec, progress: (String, Int?) -> Unit): File {
        val probe = StringBuilder()
        // The download is remote content: restrict FFmpeg to plain local media, as for picked
        // files, so a playlist disguised as media cannot make it read local files or URLs.
        command(task, dir, listOf(File(bin, "libffprobe.so").path, "-v", "error") + LOCAL_INPUT +
            listOf("-show_streams", "-of", "json", input.path)) { probe.appendLine(it) }
        val streams = JSONObject(probe.toString()).getJSONArray("streams")
        val tracks = (0 until streams.length()).map { streams.getJSONObject(it) }
        val video = tracks.firstOrNull { it.optString("codec_type") == "video" &&
            it.optJSONObject("disposition")?.optInt("attached_pic") != 1 }
        val audio = tracks.firstOrNull { it.optString("codec_type") == "audio" }
        val v = if (job.mode == Mode.AUDIO) null else requireNotNull(video) {
            if (!job.localTrim && (job.start > 0 || job.end != null))
                "The downloaded section has no video track. Enable 'Download full source and trim locally' and retry."
            else "The downloaded file has no video track."
        }
        val a = if (job.mode == Mode.VIDEO) null else requireNotNull(audio) {
            if (!job.localTrim && (job.start > 0 || job.end != null))
                "The downloaded section has no audio track. Enable 'Download full source and trim locally' and retry."
            else "The downloaded file has no audio track."
        }
        val vc = v?.getString("codec_name")
        val ac = a?.getString("codec_name")
        val ext = when {
            job.convert -> if (v != null) "mp4" else "mp3"
            tracks.size == listOfNotNull(v, a).size -> input.extension
            v == null -> when (ac) {
                "aac", "alac" -> "m4a"
                "mp3", "flac", "opus" -> ac
                "vorbis" -> "ogg"
                else -> "mka"
            }
            input.extension in setOf("mp4", "webm", "mov", "mkv") -> input.extension
            else -> "mkv"
        }
        val encodeVideo = job.convert && vc != null && vc != "h264"
        val encodeAudio = job.convert && ac != null && ac != (if (v == null) "mp3" else "aac")
        if (!job.localTrim && !encodeAudio && !encodeVideo && ext == input.extension && tracks.size == listOfNotNull(v, a).size) return input
        val stage = if (encodeAudio || encodeVideo) "Converting to ${ext.uppercase()}" else if (job.localTrim) "Trimming locally" else "Remuxing tracks"
        progress(stage, null)
        val out = File(dir, "result.$ext")
        val args = mutableListOf(File(bin, "libffmpeg.so").path, "-nostdin", "-v", "error")
        if (job.localTrim && job.start > 0) args += listOf("-ss", job.start.toString())
        args += LOCAL_INPUT + listOf("-i", input.path)
        if (job.localTrim && job.end != null) args += listOf("-t", (job.end - job.start).toString())
        if (v != null) {
            args += listOf("-map", "0:${v.getInt("index")}", "-c:v", if (encodeVideo) "libx264" else "copy")
            if (encodeVideo) args += listOf("-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-pix_fmt", "yuv420p", "-preset", "veryfast")
        }
        if (a != null) {
            args += listOf("-map", "0:${a.getInt("index")}", "-c:a",
                if (!encodeAudio) "copy" else if (v == null) "libmp3lame" else "aac")
            if (encodeAudio) args += if (v == null) listOf("-q:a", "2") else listOf("-b:a", "192k")
        }
        if (ext in setOf("mp4", "m4a")) args += listOf("-movflags", "+faststart")
        args += listOf("-progress", "pipe:1", "-nostats", out.path)
        val seconds = (job.end ?: job.info.duration)?.minus(job.start)
        command(task, dir, args) {
            if (it.startsWith("out_time_us=") && seconds != null && seconds > 0) {
                it.substringAfter('=').toLongOrNull()?.let { us ->
                    progress(stage, (us / (seconds * 10_000L)).coerceIn(0, 99).toInt())
                }
            }
        }
        return out
    }

    /** [name] is the final base name from [ExportName.base]. */
    private fun publish(task: Task, file: File, name: String, audioOnly: Boolean, progress: (String, Int?) -> Unit,
                        extension: String = file.extension): Uri {
        task.check()
        val size = file.length()
        check(size > 0) { "The output file is empty." }
        val ext = extension.lowercase()
        val type = if (audioOnly) "audio" else "video"
        val mime = when (ext) {
            "mp4", "m4a" -> "$type/mp4"
            "webm" -> "$type/webm"
            "mkv", "mka" -> "$type/x-matroska"
            "opus", "ogg" -> "$type/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        progress("Saving", 0)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create a file in Downloads.")
        try {
            File(file.parentFile, "pending-download").writeText(android.content.ContentUris.parseId(uri).toString())
            val sink = resolver.openOutputStream(uri) ?: throw IOException("Cannot write to Downloads.")
            sink.buffered().use { output ->
                file.inputStream().use { input ->
                    val bytes = ByteArray(128 * 1024)
                    var written = 0L
                    var previous = 0
                    while (true) {
                        task.check()
                        val n = input.read(bytes)
                        if (n < 0) break
                        output.write(bytes, 0, n)
                        written += n
                        val percent = (written * 100 / size).toInt()
                        if (percent != previous) progress("Saving", percent)
                        previous = percent
                    }
                    check(written == size) { "The saved file is incomplete." }
                }
            }
            synchronized(active) {
                task.check()
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) { "Cannot publish the file in Downloads." }
                active.remove(task)
            }
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private companion object {
        // Only self-contained media: a picked playlist must not fetch other files or URLs.
        val LOCAL_INPUT = listOf("-protocol_whitelist", "file", "-format_whitelist",
            "mov,matroska,webm,mp3,aac,ogg,flac,wav,aiff,avi,asf,mpeg,mpegts,flv,amr,ac3,eac3,ape,wv,caf,3g2,3gp,m4a,mj2,mp4")
        val storageLock = Any()
        /** Readers run yt-dlp or FFmpeg; the writer replaces yt-dlp. */
        val engineLock = ReentrantReadWriteLock()
        val cleaned = mutableSetOf<String>()
        const val LAUNCHER = "import os,sys; os.setsid(); print('GROUP:'+str(os.getpid()),flush=True); os.execv(sys.argv[1],sys.argv[1:])"
    }
}
