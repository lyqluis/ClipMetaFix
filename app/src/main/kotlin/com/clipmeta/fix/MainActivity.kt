package com.clipmeta.fix

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
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
import com.clipmeta.fix.util.PickVideoWithLocation
import com.clipmeta.fix.util.RepairResult
import com.clipmeta.fix.util.VideoInfo
import com.clipmeta.fix.util.hasMediaLocationPermission
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
    var originalLoading by remember { mutableStateOf(false) }
    var editedLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<RepairResult?>(null) }
    var locGranted by remember { mutableStateOf(hasMediaLocationPermission(context)) }
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun onUriPicked(target: String, uri: Uri) {
        if (target == "A") {
            originalUri = uri
            originalLoading = true
            originalInfo = null
            scope.launch {
                val info = withContext(Dispatchers.IO) { MediaInfoHelper.queryWithGps(context, uri) }
                originalInfo = info
                originalLoading = false
                if (info.location.isNullOrBlank()) {
                    status = "A 的 GPS 为空：请确认已授予位置权限，并在选择器中勾选“保留定位/相机数据”后重新选择"
                } else {
                    if (status?.startsWith("A 的 GPS") == true) status = null
                }
                lastResult = null
            }
        } else {
            editedUri = uri
            editedLoading = true
            editedInfo = null
            scope.launch {
                val info = withContext(Dispatchers.IO) { MediaInfoHelper.queryWithGps(context, uri) }
                editedInfo = info
                editedLoading = false
                lastResult = null
            }
        }
    }

    // 带位置授权的新选择器（首选）
    val pickOriginalLocation =
        rememberLauncherForActivityResult(PickVideoWithLocation()) { uri ->
            if (uri != null) onUriPicked("A", uri)
            pendingTarget = null
        }
    val pickEditedLocation =
        rememberLauncherForActivityResult(PickVideoWithLocation()) { uri ->
            if (uri != null) onUriPicked("B", uri)
            pendingTarget = null
        }
    // 老选择器 fallback（无 ACTION_PICK_IMAGES 的机型）
    val pickOriginalFallback =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                status = "当前机型不支持位置授权选择器，已用兼容模式打开；若 GPS 为空请改用系统相册选择"
                onUriPicked("A", uri)
            }
            pendingTarget = null
        }
    val pickEditedFallback =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onUriPicked("B", uri)
            pendingTarget = null
        }

    fun launchWithFallback(target: String) {
        try {
            if (!PickVideoWithLocation.isAvailable(context)) throw ActivityNotFoundException()
            if (target == "A") pickOriginalLocation.launch(Unit)
            else pickEditedLocation.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            if (target == "A") {
                pickOriginalFallback.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            } else {
                pickEditedFallback.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
        } catch (_: Exception) {
            if (target == "A") {
                pickOriginalFallback.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            } else {
                pickEditedFallback.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
        }
    }

    // 位置权限申请（仅 Q+ 需要；拒绝也允许继续选，只是 GPS 会为空）
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locGranted = granted
            when (pendingTarget) {
                "A" -> launchWithFallback("A")
                "B" -> launchWithFallback("B")
            }
            if (!granted && pendingTarget == "A") {
                status = "未授予位置权限：选到的视频 GPS 将为空，可继续但修出来也没有定位"
            }
            // pendingTarget 在 picker 回调里清；若权限框取消导致 picker 未弹，这里兜底不清由下次覆盖
        }

    fun onPickClicked(target: String) {
        // 只有 A 强依赖位置权限才先申请；B 直接进选择器
        if (target == "A" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !locGranted) {
            pendingTarget = "A"
            permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            pendingTarget = target
            launchWithFallback(target)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !locGranted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "未授予位置权限：A 的 GPS 将读不到。点“选择原片 A”时会自动申请，请允许。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        HorizontalDivider()

        // Step 1: pick original
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 选择原片 A", style = MaterialTheme.typography.titleMedium)
                Text("原片是相机直出的完整视频，包含 GPS 与拍摄时间", style = MaterialTheme.typography.bodySmall)
                Text(
                    "选择时请在系统选择器中勾选“保留定位/相机数据”（如有），否则 GPS 会被系统去掉",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { onPickClicked("A") }) {
                    Text("选择原片 A")
                }
                when {
                    originalLoading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("读取中… 大文件需数秒", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    originalInfo != null -> InfoBlock(originalInfo!!, isOriginal = true)
                    else -> Text("未选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Step 2: pick edited
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. 选择剪辑版 B", style = MaterialTheme.typography.titleMedium)
                Text("相册剪辑后导出的新视频（丢失元数据）", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onPickClicked("B") }) {
                    Text("选择剪辑版 B")
                }
                when {
                    editedLoading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("读取中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    editedInfo != null -> InfoBlock(editedInfo!!, isOriginal = false)
                    else -> Text("未选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Step 3: action
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. 执行修复", style = MaterialTheme.typography.titleMedium)
                val enabled = originalUri != null && editedUri != null && !isProcessing &&
                    !originalLoading && !editedLoading
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
        }
        val gpsLabel = if (isOriginal) "GPS" else "GPS(剪辑版一般为空，正常)"
        val srcLabel = info.locationSource?.let { "（来源:$it）" } ?: ""
        Text(
            "$gpsLabel: ${(info.location ?: "无")}$srcLabel",
            style = MaterialTheme.typography.bodySmall,
            color = if (info.location == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        info.debugDetail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isOriginal && info.location == null) {
            Text(
                "GPS 为空多半是系统脱敏：请确认已允许位置权限，并在选择器里勾保留定位后重选",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
