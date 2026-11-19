package dev.bit.media

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Only versionable, seekable content is cached. Revalidate the URI grant on each lookup. */
internal object WaveCache {
    const val BINS = 2048
    data class Entry(val info: LocalInfo, val peaks: List<Float>)

    fun key(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            val s = Os.fstat(asset.fileDescriptor)
            if (!OsConstants.S_ISREG(s.st_mode) || s.st_ino == 0L || s.st_mtime <= 0L || s.st_size <= 0L) return@use null
            val token = listOf(uri, asset.startOffset, asset.declaredLength, s.st_dev, s.st_ino, s.st_size,
                s.st_mtim.tv_sec, s.st_mtim.tv_nsec, s.st_ctim.tv_sec, s.st_ctim.tv_nsec).joinToString("|")
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    private fun folder(context: Context) = File(context.cacheDir, "waveforms-v1")

    @Synchronized fun get(context: Context, key: String): Entry? {
        val file = File(folder(context), key)
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val info = LocalInfo(input.readUTF(), input.readLong(), input.readBoolean(), input.readBoolean())
                require(info.durationMs > 0 && (info.audio || info.video))
                val count = input.readInt()
                require(count == if (info.audio) BINS else 0)
                Entry(info, List(count) { input.readFloat().also { require(it in 0f..1f) } })
            }.also { file.setLastModified(System.currentTimeMillis()) }
        }.getOrNull()
    }

    @Synchronized fun put(context: Context, key: String, entry: Entry) {
        runCatching {
            val folder = folder(context).apply { mkdirs() }
            val part = File.createTempFile("incoming-", ".tmp", folder)
            try {
                DataOutputStream(part.outputStream().buffered()).use { out ->
                    with(entry.info) {
                        out.writeUTF(title); out.writeLong(durationMs); out.writeBoolean(video); out.writeBoolean(audio)
                    }
                    out.writeInt(entry.peaks.size)
                    entry.peaks.forEach(out::writeFloat)
                }
                Os.rename(part.path, File(folder, key).path)
                folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
            } finally { part.delete() }
        }
    }
}
