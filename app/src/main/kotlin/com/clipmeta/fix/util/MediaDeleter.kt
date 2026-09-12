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
