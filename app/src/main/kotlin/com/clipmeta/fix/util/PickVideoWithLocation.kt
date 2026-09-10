package com.clipmeta.fix.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract

/**
 * 带位置授权的视频选择器。
 *
 * 背景：系统照片选择器默认对位置信息脱敏。必须同时满足：
 * 1. App 声明并获得 ACCESS_MEDIA_LOCATION 权限；
 * 2. 启动选择器时带 EXTRA_REQUEST_LOCATION_METADATA_ACCESS=true，
 *    系统才会在选择器 UI 中询问用户是否共享位置（“保留原始定位”勾选框）。
 * 否则 MediaMetadataRetriever.LOCATION 返回 null，且 openInputStream
 * 拿到的也是去掉 ©xyz 后的字节，引擎直读也救不了。
 *
 * AndroidX PickVisualMedia(1.8.2) 的 Request 体没有位置字段，
 * 因此这里直接用 MediaStore.ACTION_PICK_IMAGES + 字符串字面量 extra，
 * 避免对 compileSdk 中是否存在该常量的依赖。低版本/无 picker 机型
 * 抛 ActivityNotFoundException，由调用方 fallback 到 PickVisualMedia。
 */
class PickVideoWithLocation : ActivityResultContract<Unit, Uri?>() {

    companion object {
        const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"
        const val EXTRA_REQUEST_LOCATION_METADATA_ACCESS =
            "android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"

        /** 是否可用系统照片选择器（ACTION_PICK_IMAGES handler 存在） */
        @Suppress("DEPRECATION")
        fun isAvailable(context: Context): Boolean {
            return try {
                val intent = createBaseIntent()
                intent.resolveActivity(context.packageManager) != null
            } catch (_: Exception) {
                false
            }
        }

        fun createBaseIntent(): Intent {
            return Intent(ACTION_PICK_IMAGES).apply {
                type = "video/*"
                putExtra(EXTRA_REQUEST_LOCATION_METADATA_ACCESS, true)
            }
        }
    }

    override fun createIntent(context: Context, input: Unit): Intent {
        return createBaseIntent()
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        if (intent == null) return null
        // 单选时 data 有值；部分实现放在 ClipData 里
        intent.data?.let { return it }
        val clip = intent.clipData ?: return null
        if (clip.itemCount > 0) return clip.getItemAt(0).uri
        return null
    }
}

/** Q+ 才需要位置权限；Q 以下直接返回 true（无脱敏机制）。 */
fun hasMediaLocationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_MEDIA_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** 启动带位置的选择器，失败时抛 ActivityNotFoundException 由调用方 fallback。 */
@Throws(ActivityNotFoundException::class)
fun buildLocationPickerIntent(): Intent = PickVideoWithLocation.createBaseIntent()
