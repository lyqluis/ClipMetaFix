package com.example.metafix

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.metafix.mp4.DonorExtractor
import com.example.metafix.mp4.MetaFixer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var uriA: Uri? = null
    private var uriB: Uri? = null
    private lateinit var pickA: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var pickB: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var tvA: TextView
    private lateinit var tvB: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnFix: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        fun label() = TextView(this).apply {
            textSize = 13f
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }
        tvA = label(); tvB = label(); tvLog = label()
        val btnA = Button(this).apply { text = "① 选择原片 A（拍摄时的原始视频）" }
        val btnB = Button(this).apply { text = "② 选择剪辑版 B（剪辑后丢信息的那个）" }
        btnFix = Button(this).apply { text = "③ 执行修复"; isEnabled = false }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(btnA); addView(tvA)
            addView(btnB); addView(tvB)
            addView(btnFix); addView(tvLog)
        }
        setContentView(root)

        pickA = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                uriA = it
                tvA.text = "A 的信息：\n${summarize(it)}"   // 防呆关键：肉眼确认
                updateFixButton()
            }
        }
        pickB = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                uriB = it
                tvB.text = "B：${displayName(it)}"
                updateFixButton()
            }
        }
        btnA.setOnClickListener { pickA.launch(videoRequest()) }
        btnB.setOnClickListener { pickB.launch(videoRequest()) }
        btnFix.setOnClickListener { executeFix() }
    }

    private fun videoRequest() =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)

    private fun updateFixButton() {
        btnFix.isEnabled = uriA != null && uriB != null
    }

    private fun summarize(uri: Uri): String {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: "（无日期）"
            val loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: "（无定位）"
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "?"
            "拍摄时间: $date\nGPS: $loc\n时长: ${dur.toLong() / 1000}s"
        } finally {
            r.release()
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else uri.toString() }
            ?: uri.toString()

    private fun executeFix() {
        val a = uriA ?: return
        val b = uriB ?: return
        btnFix.isEnabled = false
        tvLog.text = "处理中…（大文件需要几秒）"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { doFix(a, b) } }
            tvLog.text = result.fold(
                onSuccess = { "✅ 修复完成，请到相册验证：时间线应回到拍摄当天，详情页应有定位。" },
                onFailure = { "❌ 失败：${it.message ?: it.javaClass.simpleName}" }
            )
            btnFix.isEnabled = true
        }
    }

    private fun doFix(a: Uri, b: Uri) {
        val workDir = File(cacheDir, "metafix").apply { mkdirs() }
        val fa = File(workDir, "a.mp4")
        val fb = File(workDir, "b.mp4")
        val fo = File(workDir, "fixed.mp4")

        copyToTemp(a, fa)
        copyToTemp(b, fb)

        val donor = DonorExtractor.extract(fa.toPath())
        MetaFixer.fix(donor, fb.toPath(), fo.toPath())

        verifyOutput(fo)                       // 验证不过就抛异常，B  untouched
        replaceB(b, fo, displayName(b))
        fo.delete()
    }

    private fun copyToTemp(src: Uri, dst: File) {
        contentResolver.openInputStream(src)!!.use { input ->
            FileOutputStream(dst).use { input.copyTo(it) }
        }
    }

    private fun verifyOutput(f: File) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?: throw IllegalStateException("输出文件无法解析")
        } finally {
            r.release()
        }
    }

    /** 先尝试原位覆盖 B；被 Android 14 拒绝则降级为新建同名条目 */
    private fun replaceB(bUri: Uri, fixed: File, name: String) {
        try {
            contentResolver.openFileDescriptor(bUri, "rwt")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel
                    .transferFrom(FileInputStream(fixed).channel, 0, fixed.length())
            }
        } catch (se: SecurityException) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
            val newUri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IllegalStateException("创建新条目失败")
            contentResolver.openOutputStream(newUri)!!.use { output ->
                fixed.inputStream().use { it.copyTo(output) }
            }
            throw SecurityException("系统拒绝原位修改，已生成修复版到相册（DCIM/Camera/$name），请手动删除旧的 B。")
        }
    }
}
