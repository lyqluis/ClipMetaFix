package com.clipmeta.fix.util

import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Q+ 位置脱敏的读侧钥匙。
 *
 * 有 ACCESS_MEDIA_LOCATION 权限 + picker 勾选只是“有资格”，
 * 读字节时还必须用 MediaStore.setRequireOriginal(uri) 要原始字节，
 * 否则 MediaProvider 默认返回剥掉 ©xyz 的脱敏流（meta 不受影响，所以
 * 现象总是 ©xyz:无/meta:有）。
 *
 * 非 MediaStore provider 或低版本原样返回；抛异常时调用方回退裸 uri。
 */
object UriRequireOriginal {

    fun wrap(uri: Uri): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return uri
        return try {
            MediaStore.setRequireOriginal(uri)
        } catch (_: Exception) {
            uri
        }
    }

    /**
     * 是否会话级照片选择器 URI（content://media/picker/...）。
     * 这类 URI 官方不支持 setRequireOriginal（必抛 UnsupportedOperationException），
     * 且裸流就是唯一读法，调用方应直接跳过包装。
     */
    fun isPickerUri(uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return false
        val path = uri.encodedPath ?: return false
        return path.contains("/picker/")
    }
}
