package com.clipmeta.fix.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val date: String?,
    val location: String?,
    val width: Int?,
    val height: Int?
)

object MediaInfoHelper {

    fun query(context: Context, uri: Uri): VideoInfo {
        val resolver = context.contentResolver
        var name = "unknown.mp4"
        var size: Long = -1
        resolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        // fallback size via FD
        if (size < 0) {
            try {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (_: Exception) {}
        }

        var duration: Long? = null
        var date: String? = null
        var location: String? = null
        var w: Int? = null
        var h: Int? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w == null || h == null) {
                // try rotation independent
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return VideoInfo(uri, name, size, duration, date, location, w, h)
    }

    fun formatDuration(ms: Long?): String {
        if (ms == null) return "--"
        val s = ms / 1000
        val rem = ms % 1000
        return "${s}.${rem / 100}s"
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "--"
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
            else -> String.format("%.2fMB", bytes / 1024.0 / 1024.0)
        }
    }
}
