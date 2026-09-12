package com.clipmeta.fix.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * 相册直选视频（返回真实 MediaStore URI）。
 *
 * 背景：系统照片选择器（ACTION_PICK_IMAGES）返回的是会话级作用域 URI
 * （content://media/picker/...），官方不支持 setRequireOriginal，
 * 位置许可也经常挂不上，©xyz 注定拿不到。
 * 用 ACTION_PICK 直接对 MediaStore.Video 选片，返回真实 URI
 * （content://media/external/video/media/123），配合
 * ACCESS_MEDIA_LOCATION + setRequireOriginal 即可读到原始字节（含 GPS）。
 * 无 Gallery 的机型抛异常，由调用方 fallback 到其他通道。
 */
class PickVideoViaGallery : ActivityResultContract<Unit, Uri?>() {

    companion object {
        fun createBaseIntent(): Intent {
            return Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }

        @Suppress("DEPRECATION")
        fun isAvailable(context: Context): Boolean {
            return try {
                createBaseIntent().resolveActivity(context.packageManager) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun createIntent(context: Context, input: Unit): Intent = createBaseIntent()

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        parseSingleVideoResult(resultCode, intent)
}

/** 三个单选视频 contract 共用：data 优先，否则取 clipData 第一个。 */
fun parseSingleVideoResult(resultCode: Int, intent: Intent?): Uri? {
    if (resultCode != Activity.RESULT_OK) return null
    if (intent == null) return null
    intent.data?.let { return it }
    val clip = intent.clipData ?: return null
    if (clip.itemCount > 0) return clip.getItemAt(0).uri
    return null
}

/**
 * 文件管理器选视频，且初始定位到 DCIM/Camera（省去一层层翻目录）。
 * 经 DocumentsProvider 管道，字节不受照片选择器脱敏影响。
 */
class PickVideoViaFiles : ActivityResultContract<Unit, Uri?>() {

    companion object {
        fun createBaseIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                try {
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:DCIM/Camera"
                        )
                    )
                } catch (_: Exception) {
                    // 不支持的机型忽略，直进默认目录
                }
            }
        }
    }

    override fun createIntent(context: Context, input: Unit): Intent = createBaseIntent()

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        parseSingleVideoResult(resultCode, intent)
}
