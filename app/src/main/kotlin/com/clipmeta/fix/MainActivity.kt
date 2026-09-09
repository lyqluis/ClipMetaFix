package com.clipmeta.fix

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clipmeta.fix.util.MediaInfoHelper
import com.clipmeta.fix.util.Mp4Repairer
import com.clipmeta.fix.util.RepairResult
import com.clipmeta.fix.util.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ClipMetaFixScreen()
                }
            }
        }
    }
}

@Composable
fun ClipMetaFixScreen() {
    val context = LocalContext.current
    var originalUri by remember { mutableStateOf<Uri?>(null) }
    var editedUri by remember { mutableStateOf<Uri?>(null) }
    var originalInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var editedInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<RepairResult?>(null) }
    val scope = rememberCoroutineScope()

    val pickOriginal = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            originalUri = uri
            // take persistable permission
            try { context.contentResolver.takePersistableUriPermission(uri, 1) } catch (_: Exception) {}
            originalInfo = MediaInfoHelper.query(context, uri)
            status = null
            lastResult = null
        }
    }
    val pickEdited = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            editedUri = uri
            try { context.contentResolver.takePersistableUriPermission(uri, 1) } catch (_: Exception) {}
            editedInfo = MediaInfoHelper.query(context, uri)
            status = null
            lastResult = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ClipMetaFix", style = MaterialTheme.typography.headlineSmall)
        Text(
            "解决澎湃OS剪辑后丢失 GPS/拍摄时间的工具。按顺序选择 原片A 与 剪辑版B，执行修复。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        // Step 1: pick original
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 选择原片 A", style = MaterialTheme.typography.titleMedium)
                Text("原片是相机直出的完整视频，包含 GPS 与拍摄时间", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pickOriginal.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                    Text("选择原片 A")
                }
                originalInfo?.let { info ->
                    InfoBlock(info, isOriginal = true)
                } ?: Text("未选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Step 2: pick edited
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. 选择剪辑版 B", style = MaterialTheme.typography.titleMedium)
                Text("相册剪辑后导出的新视频（丢失元数据）", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pickEdited.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                    Text("选择剪辑版 B")
                }
                editedInfo?.let { info ->
                    InfoBlock(info, isOriginal = false)
                } ?: Text("未选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // Step 3: action
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. 执行修复", style = MaterialTheme.typography.titleMedium)
                val enabled = originalUri != null && editedUri != null && !isProcessing
                Button(
                    onClick = {
                        val a = originalUri ?: return@Button
                        val b = editedUri ?: return@Button
                        isProcessing = true
                        status = "处理中… 大文件需数秒，请稍候"
                        lastResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { Mp4Repairer.repair(context, a, b) }
                            isProcessing = false
                            lastResult = result
                            status = when (result) {
                                is RepairResult.Success -> when (result.method) {
                                    "overwrite" -> "✓ 已原位更新剪辑版 B，请去相册验证时间与定位"
                                    else -> "✓ 已新建条目 ${result.newUri}，请去相册查看；可手动删除旧 B"
                                }
                                is RepairResult.Failure -> "✗ 失败: ${result.reason}"
                            }
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProcessing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isProcessing) "处理中…" else "执行修复")
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                lastResult?.let { r ->
                    when (r) {
                        is RepairResult.Success -> {
                            Text("moov ${r.oldMoov} → ${r.newMoov} bytes，方式: ${r.method}", style = MaterialTheme.typography.bodySmall)
                            r.newUri?.let { uri -> Text("新Uri: $uri", style = MaterialTheme.typography.labelSmall) }
                        }
                        is RepairResult.Failure -> {}
                    }
                }
                if (originalUri == null || editedUri == null) {
                    Text("请先完成步骤 1 与 2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("验证方法", style = MaterialTheme.typography.labelLarge)
                Text("• 相册中剪辑版应排在拍摄当天，详情页显示 GPS\n• 与 exiftool \"-TagsFromFile A -all:all B\" 的语义一致\n• 去除 mcvr 缩略图，不增加文件体积", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoBlock(info: VideoInfo, isOriginal: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(info.displayName, style = MaterialTheme.typography.bodyMedium)
        Text("大小 ${MediaInfoHelper.formatSize(info.sizeBytes)}  时长 ${MediaInfoHelper.formatDuration(info.durationMs)}  ${info.width?.let { "${it}x${info.height}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        if (isOriginal) {
            Text("拍摄时间: ${info.date ?: "无（将无防呆提示，请确认选对）"}", style = MaterialTheme.typography.bodySmall, color = if (info.date == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("GPS: ${info.location ?: "无"}", style = MaterialTheme.typography.bodySmall, color = if (info.location == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
