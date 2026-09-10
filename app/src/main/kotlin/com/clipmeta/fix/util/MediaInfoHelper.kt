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
    val height: Int?,
    /** GPS 来源：system=MediaMetadataRetriever, mp4engine=©xyz 直读 */
    val locationSource: String? = null,
    /** 防呆调试：如 "©xyz:有/meta:有" */
    val debugDetail: String? = null
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

        // 第一段：裸 URI 读基础字段（行为与修复前一致，时长/时间/宽高一定能回来）。
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        // 第二段：location 为空且 Q+ 时，用 requireOriginal URI 只补 location。
        // wrapped URI 可能被 picker 系 provider 拒绝，异常则丢弃，保留第一段结果。
        if (location.isNullOrBlank() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val wrapped = UriRequireOriginal.wrap(uri)
            if (wrapped != uri) {
                val retriever2 = MediaMetadataRetriever()
                try {
                    retriever2.setDataSource(context, wrapped)
                    retriever2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let {
                        if (it.isNotBlank()) location = it
                    }
                } catch (_: Exception) {
                } finally {
                    try { retriever2.release() } catch (_: Exception) {}
                }
            }
        }
        return VideoInfo(uri, name, size, duration, date, location, w, h)
    }

    /**
     * 带 GPS 兜底的查询（必须在 Dispatchers.IO 调用）：
     * retriever.LOCATION 优先（日期/时长已够用时最快），为 null 则把 Uri
     * 拷贝到临时文件后用 mp4engine 直读 ©xyz。
     * 注意：若系统已脱敏（无位置权限 / 选择器未勾保留定位），两种通道
     * 都会是 null，此时如实返回 null，由 UI 提示用户重选。
     */
    fun queryWithGps(context: Context, uri: Uri): VideoInfo {
        val base = query(context, uri)
        if (!base.location.isNullOrBlank()) {
            return base.copy(locationSource = "system")
        }
        var tmp: java.io.File? = null
        return try {
            tmp = StorageHelper.copyUriToTempFile(context, uri, "clipmeta_info_")
            val extracted = try {
                com.clipmeta.fix.mp4.Mp4Parser.extract(tmp)
            } catch (_: Exception) {
                null
            }
            if (extracted == null) {
                base.copy(debugDetail = "解析失败")
            } else {
                val xyz = com.clipmeta.fix.mp4.XyzLocation.parse(extracted.udtaChildrenFiltered)
                val hasMeta = extracted.metaRaw != null
                val hasXyz = xyz != null
                val orig = if (UriRequireOriginal.wrap(uri) != uri) "是" else "否"
                val detail = "©xyz:${if (hasXyz) "有" else "无"}/meta:${if (hasMeta) "有" else "无"}/orig:$orig"
                if (xyz != null) {
                    base.copy(location = xyz, locationSource = "mp4engine", debugDetail = detail)
                } else {
                    base.copy(debugDetail = detail)
                }
            }
        } catch (_: Exception) {
            base
        } finally {
            try { tmp?.delete() } catch (_: Exception) {}
        }
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
