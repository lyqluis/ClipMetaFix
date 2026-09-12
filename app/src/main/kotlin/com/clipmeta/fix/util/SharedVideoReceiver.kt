package com.clipmeta.fix.util

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * 相册分享入口（ACTION_SEND / ACTION_SEND_MULTIPLE，mimeType 为 video）的解析与 A/B 自动识别。
 * 识别信号优先级：GPS（queryWithGps 已回填 location，含 mp4engine 兜底）> 时长 > 大小。
 * 分享 intent 自带一次性读授权，现有“拷临时文件再修”链路天然兼容，无需持久化授权。
 */
object SharedVideoReceiver {

    /** 从分享 intent 取出视频 URI（最多 2 个，超量丢弃）；非分享 action 返回空。 */
    fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val raw: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(u ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list ?: intent.clipData?.let { cd ->
                    (0 until cd.itemCount).mapNotNull { cd.getItemAt(it).uri }
                } ?: emptyList()
            }
            else -> emptyList()
        }
        return raw.filter { it != Uri.EMPTY }.distinct().take(2)
    }

    data class Candidate(
        val uri: Uri,
        val hasGps: Boolean,
        val durationMs: Long?,
        val sizeBytes: Long,
        val name: String
    )

    data class Assignment(
        val a: Uri?,
        val b: Uri?,
        /** gps | duration | single-gps | single-nogps | empty */
        val basis: String
    )

    /** 纯函数：按 GPS > 时长 > 大小把 1~2 个候选分配进 A/B。 */
    fun classify(list: List<Candidate>): Assignment {
        if (list.isEmpty()) return Assignment(null, null, "empty")
        if (list.size == 1) {
            val c = list[0]
            return if (c.hasGps) Assignment(c.uri, null, "single-gps")
            else Assignment(null, c.uri, "single-nogps")
        }
        val (x, y) = list[0] to list[1]
        if (x.hasGps != y.hasGps) {
            val a = if (x.hasGps) x else y
            val b = if (x.hasGps) y else x
            return Assignment(a.uri, b.uri, "gps")
        }
        // 都有或都没有 GPS：时长长的为 A（原片通常更长）；时长也相同按大小；还相同取第一个。
        val dx = x.durationMs ?: -1L
        val dy = y.durationMs ?: -1L
        val a = if (dy > dx) y else if (dx > dy) x else if (y.sizeBytes > x.sizeBytes) y else x
        val b = if (a.uri == x.uri) y else x
        return Assignment(a.uri, b.uri, "duration")
    }
}
