package com.clipmeta.fix.util

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * 分区存储下的媒体删除。
 *
 * - API < 29：直接 resolver.delete。
 * - API 29：直接删，抓 RecoverableSecurityException 取授权 IntentSender 抛给调用方弹系统框。
 * - API 30+：MediaStore.createDeleteRequest 一次弹框批量删（系统托管确认）。
 * - Document URI：DocumentsContract.deleteDocument。
 * - 照片选择器会话 URI（content://media/picker/...）：删不动，如实返回失败。
 *
 * 所有单条失败都不抛异常，记进 DeleteOutcome，由 UI 展示。
 */
object MediaDeleter {

    data class DeleteOutcome(
        val uri: Uri,
        val ok: Boolean,
        val reason: String? = null
    )

    /** 是否 DocumentProvider 的 URI。 */
    fun isDocumentUri(uri: Uri): Boolean =
        uri.authority?.endsWith(".documents") == true ||
            uri.authority?.contains("documents") == true

    /** 系统删框构造结果：成功给 Sender，失败给原因（诊断用，不再吞异常）。 */
    sealed class DeleteRequest {
        data class Ready(val sender: IntentSender) : DeleteRequest()
        data class Failed(val reason: String) : DeleteRequest()
        object Unsupported : DeleteRequest() // API < 30，无此接口
    }

    /**
     * 构造删除所需的系统授权 IntentSender（API 30+ 批量）。
     * picker 会话 URI 无法删除，调用方应先用 [isDeletable] 过滤并提示。
     */
    fun buildDeleteRequest(context: Context, uris: List<Uri>): DeleteRequest {
        if (uris.isEmpty()) return DeleteRequest.Failed("空列表")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return DeleteRequest.Unsupported
        return try {
            DeleteRequest.Ready(MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender)
        } catch (e: Exception) {
            DeleteRequest.Failed(shortErr(e))
        }
    }

    /** 异常摘要（单行截断，供状态栏诊断）。 */
    fun shortErr(e: Exception): String {
        val msg = (e.message ?: "").replace(Regex("\\s+"), " ").take(100)
        return e.javaClass.simpleName + (if (msg.isBlank()) "" else ":$msg")
    }

    /** picker 会话 URI 不可删；其余尝试直接删。 */
    fun isDeletable(uri: Uri): Boolean = !UriRequireOriginal.isPickerUri(uri)

    /** 删除反查要用的宽泛读权限名（33+ 细分 VIDEO，以下沿用 EXTERNAL_STORAGE）。 */
    fun broadReadPermissionName(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    /** 是否持有宽泛媒体读权限（反查全库的前提；单文件授权不算）。 */
    fun hasBroadMediaRead(context: Context): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, broadReadPermissionName()
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 是否标准 MediaStore 条目 URI（content://media/.../<数字id>，删框只认这种）。 */
    fun isStandardMediaItem(uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return false
        val tail = uri.lastPathSegment ?: return false
        return tail.isNotEmpty() && tail.all { it.isDigit() }
    }

    /** 诊断用：authority + 尾段形态。 */
    fun uriShape(uri: Uri): String {
        val tail = uri.lastPathSegment ?: "-"
        val shown = if (tail.length > 24) "…${tail.takeLast(20)}" else tail
        return "auth:${uri.authority ?: "-"}/tail:$shown"
    }

    /** 反查结果：命中给标准 URI，miss 给原因（行数/异常），供状态栏诊断。 */
    sealed class ResolveOutcome {
        data class Hit(val uri: Uri) : ResolveOutcome()
        data class Miss(val reason: String) : ResolveOutcome()
    }

    /**
     * 把各类 URI 归一化成标准 MediaStore 条目 URI（供删框用）。
     * 已是标准形则 Hit 原样返回；自家 Gallery 等非标准形先按 SIZE（+DURATION）跨卷反查，
     * 再按 DISPLAY_NAME 反查；查不到给 Miss（调用方用原 URI 硬试，失败进手动提示）。
     */
    fun resolveForDelete(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        sizeBytes: Long = -1,
        durationMs: Long? = null
    ): ResolveOutcome {
        if (isStandardMediaItem(uri)) return ResolveOutcome.Hit(uri)
        if (UriRequireOriginal.isPickerUri(uri)) return ResolveOutcome.Miss("picker会话无条目")
        return findMediaStoreItem(context, displayName, sizeBytes, durationMs)
    }

    private fun findMediaStoreItem(
        context: Context,
        displayName: String?,
        sizeBytes: Long,
        durationMs: Long?
    ): ResolveOutcome {
        return try {
            // 遍历所有外部卷（副卷/SD卡上的文件主卷查不到）
            val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaStore.getExternalVolumeNames(context)
                } catch (_: Exception) {
                    setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
            } else {
                setOf(MediaStore.VOLUME_EXTERNAL)
            }
            // 第一判据：SIZE 精确查（文件名会骗人，字节数不会；空游标直接记0行）
            if (sizeBytes > 0) {
                val rows = mutableListOf<Row2>()
                for (vol in volumes) {
                    val coll = collectionFor(context, vol)
                    try {
                        context.contentResolver.query(
                            coll,
                            arrayOf(
                                MediaStore.Video.Media._ID,
                                MediaStore.Video.Media.SIZE,
                                MediaStore.Video.Media.DURATION
                            ),
                            "${MediaStore.Video.Media.SIZE}=?",
                            arrayOf(sizeBytes.toString()),
                            null
                        )?.use { c ->
                            collectRows(c, coll, rows)
                        }
                    } catch (_: Exception) {
                        // 单卷失败不影响其他卷
                    }
                }
                if (rows.size == 1) {
                    val r = rows[0]
                    return ResolveOutcome.Hit(android.content.ContentUris.withAppendedId(r.coll, r.id))
                }
                if (rows.size > 1 && durationMs != null && durationMs > 0) {
                    // 多行并列用时长缩圈（±2s 容差），仍不唯一就放弃
                    val narrowed = rows.filter { kotlin.math.abs(it.dur - durationMs) <= 2000 }
                    if (narrowed.size == 1) {
                        val r = narrowed[0]
                        return ResolveOutcome.Hit(android.content.ContentUris.withAppendedId(r.coll, r.id))
                    }
                    return ResolveOutcome.Miss("同大小${rows.size}行且时长不定")
                }
                if (rows.size > 1) return ResolveOutcome.Miss("同大小${rows.size}行且无时长")
                // size查0行：继续往下用文件名查（可能 SIZE 登记不一致）
            }
            // 第二判据：DISPLAY_NAME 精确查（MIUI 报的名字可能有细微出入，仅作补充）
            if (!displayName.isNullOrBlank()) {
                val rows = mutableListOf<Row2>()
                for (vol in volumes) {
                    val coll = collectionFor(context, vol)
                    try {
                        context.contentResolver.query(
                            coll,
                            arrayOf(
                                MediaStore.Video.Media._ID,
                                MediaStore.Video.Media.SIZE,
                                MediaStore.Video.Media.DURATION
                            ),
                            "${MediaStore.Video.Media.DISPLAY_NAME}=?",
                            arrayOf(displayName),
                            null
                        )?.use { c ->
                            collectRows(c, coll, rows)
                        }
                    } catch (_: Exception) {
                    }
                }
                if (rows.isEmpty()) {
                    val sizeNote = if (sizeBytes > 0) "+大小查0行" else ""
                    return ResolveOutcome.Miss("查0行(名:$displayName$sizeNote)")
                }
                val sized = if (sizeBytes > 0) rows.find { it.size == sizeBytes } else null
                val chosen = sized ?: rows.singleOrNull()
                if (chosen == null) {
                    ResolveOutcome.Miss("同名${rows.size}行且大小不定")
                } else {
                    ResolveOutcome.Hit(android.content.ContentUris.withAppendedId(chosen.coll, chosen.id))
                }
            } else {
                ResolveOutcome.Miss("无文件名且大小未知")
            }
        } catch (e: Exception) {
            ResolveOutcome.Miss("err:" + shortErr(e))
        }
    }

    private fun collectionFor(context: Context, vol: String): android.net.Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(vol)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun collectRows(
        c: android.database.Cursor,
        coll: android.net.Uri,
        rows: MutableList<Row2>
    ) {
        val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
        val durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION)
        while (c.moveToNext()) {
            rows.add(
                Row2(
                    coll,
                    c.getLong(idIdx),
                    if (sizeIdx >= 0) c.getLong(sizeIdx) else -1,
                    if (durIdx >= 0) c.getLong(durIdx) else -1
                )
            )
        }
    }

    private data class Row2(val coll: android.net.Uri, val id: Long, val size: Long, val dur: Long)

    /**
     * 无需系统框时的直接删除（API < 30，或 Document URI）。
     * API 29 他人文件会抛 RecoverableSecurityException，调用方取
     * (e as RecoverableSecurityException).userAction.actionIntent.intentSender 弹框。
     */
    @Throws(Exception::class)
    fun deleteDirect(context: Context, uri: Uri): Boolean {
        return if (isDocumentUri(uri)) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) {
                false
            }
        } else {
            try {
                context.contentResolver.delete(uri, null, null) > 0
            } catch (e: Exception) {
                // API 29 他人文件走授权流程，抛给调用方处理
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                    e is android.app.RecoverableSecurityException
                ) {
                    throw e
                }
                false
            }
        }
    }

    /** 释放之前 take 过的可持久化读授权（删完清理，失败忽略）。 */
    fun releasePersistable(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
    }
}
