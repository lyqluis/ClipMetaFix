package com.clipmeta.fix.util

import android.content.Context
import android.net.Uri
import com.clipmeta.fix.mp4.Mp4Parser
import com.clipmeta.fix.mp4.Mp4Patcher
import java.io.File

sealed class RepairResult {
    data class Success(
        val patchedFile: File,
        val method: String, // "overwrite" or "insert"
        val newUri: Uri?,
        val oldMoov: Long,
        val newMoov: Long
    ) : RepairResult()
    data class Failure(val reason: String) : RepairResult()
}

object Mp4Repairer {

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
                    // Try overwrite B first
                    val overwritten = StorageHelper.tryOverwriteOriginal(context, editedUri, tmpOut)
                    if (overwritten) {
                        return RepairResult.Success(tmpOut, "overwrite", editedUri, patchResult.oldMoovSize, patchResult.newMoovSize)
                    }
                    // Fallback: insert new entry
                    val infoB = MediaInfoHelper.query(context, editedUri)
                    // Derive new name: original name without extension + "_fixed.mp4"
                    val base = infoB.displayName.removeSuffix(".mp4").removeSuffix(".MP4")
                    val newName = "${base}_fixed.mp4"
                    val newUri = StorageHelper.insertAsNewEntry(context, tmpOut, newName)
                    if (newUri != null) {
                        return RepairResult.Success(tmpOut, "insert", newUri, patchResult.oldMoovSize, patchResult.newMoovSize)
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
