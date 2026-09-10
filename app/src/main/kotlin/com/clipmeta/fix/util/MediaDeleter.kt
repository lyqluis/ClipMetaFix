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

    /**
     * 构造删除所需的系统授权 IntentSender（API 30+ 批量 / API 29 单条回退）。
     * 返回 null 表示无需系统框，调用方直接 [deleteDirect]。
     * picker 会话 URI 无法删除，调用方应先用 [isDeletable] 过滤并提示。
     */
    fun buildDeleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            } catch (_: Exception) {
                null
            }
        }
        return null
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
