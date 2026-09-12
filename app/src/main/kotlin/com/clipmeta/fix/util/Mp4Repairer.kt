package com.clipmeta.fix.util

import android.content.Context
import android.net.Uri
import com.clipmeta.fix.mp4.Mp4Parser
import com.clipmeta.fix.mp4.Mp4Patcher
import com.clipmeta.fix.mp4.XyzLocation
import java.io.File

sealed class RepairResult {
    data class Success(
        val patchedFile: File,
        val method: String, // "overwrite" or "insert"
        val newUri: Uri?,
        val oldMoov: Long,
        val newMoov: Long,
        val newName: String? = null, // 目标文件名（<A基名>_cutfixed.mp4）
        val renamed: Boolean = false // overwrite 分支的改名是否成功
    ) : RepairResult()
    data class Failure(val reason: String) : RepairResult()
}

object Mp4Repairer {

    /** 轻量取文件名基名（只查 DISPLAY_NAME，不走 retriever）；失败返回 null 由调用方回退。 */
    private fun displayBaseName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.removeSuffix(".mp4")?.removeSuffix(".MP4")
        } catch (_: Exception) {
            null
        }
    }

    fun repair(context: Context, originalUri: Uri, editedUri: Uri): RepairResult {
        var tmpA: File? = null
        var tmpB: File? = null
        var tmpOut: File? = null
        try {
            // Copy both to temp files for random access
            tmpA = StorageHelper.copyUriToTempFile(context, originalUri, "clipmeta_a_")
            tmpB = StorageHelper.copyUriToTempFile(context, editedUri, "clipmeta_b_")
            tmpOut = File.createTempFile("clipmeta_out_", ".mp4", context.cacheDir)

            // Basic validation: must be mp4
            if (Mp4Parser.findMoov(tmpA) == null) return RepairResult.Failure("原片 A 不是有效 MP4（未找到 moov）")
            if (Mp4Parser.findMoov(tmpB) == null) return RepairResult.Failure("剪辑版 B 不是有效 MP4（未找到 moov）")

            val extracted = try {
                Mp4Parser.extract(tmpA)
            } catch (e: Exception) {
                return RepairResult.Failure("解析原片 A 失败: ${e.message}")
            }
            if (extracted.mvhdTimes == null) return RepairResult.Failure("原片 A 缺少 mvhd 时间，无法修复")
            // GPS 断言：A 若无 ©xyz，极大概率是系统脱敏（未授权位置 / 选择器未勾保留定位），
            // 此时拷出的就是去 GPS 字节，继续修只会产出无定位文件，直接失败让用户重选。
            if (!XyzLocation.hasLocation(extracted)) {
                return RepairResult.Failure("从原片A读不到GPS(©xyz缺失)：多半是系统脱敏。请先允许位置权限，并在选择器中勾选“保留定位/相机数据”后重新选择A再修")
            }
            if (extracted.metaRaw == null) {
                // Not fatal but warn; continue
            }

            val patchResult = Mp4Patcher.patch(tmpB, extracted, tmpOut)
            when (patchResult) {
                is Mp4Patcher.PatchResult.Failure -> return RepairResult.Failure("重写失败: ${patchResult.reason}")
                is Mp4Patcher.PatchResult.Success -> {
                    // Validate
                    if (!StorageHelper.validateWithRetriever(context, tmpOut)) {
                        return RepairResult.Failure("输出文件校验失败（无法读取时长）")
                    }
                    // 断言输出含 GPS，防止静默产出无定位文件
                    try {
                        val outExtracted = Mp4Parser.extract(tmpOut)
                        if (!XyzLocation.hasLocation(outExtracted)) {
                            return RepairResult.Failure("输出文件缺GPS，修复未生效（A可能被系统脱敏），请重选A后重试")
                        }
                    } catch (_: Exception) {
                        // 解析失败则信任 patch 自校验，不额外失败
                    }
                    // 目标文件名：A 的基名 + _cutfixed，证明是 A 的剪辑版且已修复
                    val newName = "${displayBaseName(context, originalUri)
                        ?: displayBaseName(context, editedUri) ?: "clipmeta"}_cutfixed.mp4"
                    // Try overwrite B first (成功后顺手改名)
                    val overwrite = StorageHelper.tryOverwriteOriginal(context, editedUri, tmpOut, newName)
                    if (overwrite.ok) {
                        return RepairResult.Success(
                            tmpOut, "overwrite", editedUri,
                            patchResult.oldMoovSize, patchResult.newMoovSize,
                            newName, overwrite.renamed
                        )
                    }
                    // Fallback: insert new entry
                    val newUri = StorageHelper.insertAsNewEntry(context, tmpOut, newName)
                    if (newUri != null) {
                        return RepairResult.Success(
                            tmpOut, "insert", newUri,
                            patchResult.oldMoovSize, patchResult.newMoovSize, newName, true
                        )
                    }
                    return RepairResult.Failure("无法写入相册：覆盖被拒绝且新建条目失败。请检查存储权限")
                }
            }
        } catch (e: Exception) {
            return RepairResult.Failure("异常: ${e.message}")
        } finally {
            // Do not delete tmpOut if success (caller may need it), but delete A/B temps
            try { tmpA?.delete() } catch (_: Exception) {}
            try { tmpB?.delete() } catch (_: Exception) {}
        }
    }
}
