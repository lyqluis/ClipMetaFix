package com.clipmeta.fix

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
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
import com.clipmeta.fix.util.PickVideoViaFiles
import com.clipmeta.fix.util.PickVideoViaGallery
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun onDeleteDone(targets: List<Uri>) {
        for (u in targets) com.clipmeta.fix.util.MediaDeleter.releasePersistable(context, u)
        if (originalUri in targets) {
            originalUri = null
            originalInfo = null
        }
        if (editedUri in targets) {
            editedUri = null
            editedInfo = null
        }
        lastResult = null
        status = "已删除所选原文件，请去相册确认"
    }

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
    // 相册直选（A 的主通道）：返回真实 MediaStore URI，可读原始字节（含 GPS）。
    // 照片选择器（content://media/picker/...）不支持 requireOriginal，注定无 GPS，
    // 因此 A 默认走这里；无 Gallery 机型回退到位置授权选择器。
    val pickOriginalGallery =
        rememberLauncherForActivityResult(PickVideoViaGallery()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                onUriPicked("A", uri)
            }
            pendingTarget = null
        }
    // 文件管理器直选（备用通道）：初始定位 DCIM/Camera，走 DocumentsProvider 管道。
    val pickOriginalDoc =
        rememberLauncherForActivityResult(PickVideoViaFiles()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                onUriPicked("A", uri)
            }
            pendingTarget = null
        }
    val pickEditedDoc =
        rememberLauncherForActivityResult(PickVideoViaFiles()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                onUriPicked("B", uri)
            }
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

    // 删除系统授权回调（R+ 批量删除 / Q 单条授权）：用户点了允许即视为删完，清状态。
    val deleteConsentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onDeleteDone(pendingDeleteUris)
            } else {
                status = "删除未完成（系统确认被拒绝或取消），原文件仍在"
            }
            pendingDeleteUris = emptyList()
        }

    /** 直接删除（无需系统框 / 系统框拿不到时）；Q 他人文件走授权 Sender。 */
    fun runDeleteDirect(targets: List<Uri>) {
        scope.launch {
            var needSender: IntentSender? = null
            val failed = mutableListOf<Uri>()
            withContext(Dispatchers.IO) {
                for (uri in targets) {
                    try {
                        if (com.clipmeta.fix.util.MediaDeleter.deleteDirect(context, uri)) {
                            com.clipmeta.fix.util.MediaDeleter.releasePersistable(context, uri)
                        } else {
                            failed.add(uri)
                        }
                    } catch (e: Exception) {
                        val sender = (e as? android.app.RecoverableSecurityException)
                            ?.userAction?.actionIntent?.intentSender
                        if (sender != null && needSender == null) needSender = sender
                        else failed.add(uri)
                    }
                }
            }
            val sender = needSender
            if (sender != null) {
                pendingDeleteUris = targets
                try {
                    deleteConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } catch (_: Exception) {
                    pendingDeleteUris = emptyList()
                    status = "删除失败：无法弹出系统授权，请在相册手动删"
                }
            } else if (failed.isEmpty()) {
                onDeleteDone(targets)
            } else {
                status = "部分删除失败（${failed.size} 个），请在相册手动删除剩余文件"
            }
        }
    }

    /** 删除入口：安全规则 overwrite→只删A；insert→删A+旧B。picker 会话 URI 删不动会明说。 */
    fun onDeleteClicked() {
        val r = lastResult as? RepairResult.Success ?: return
        val targets = mutableListOf<Uri>()
        originalUri?.let { targets.add(it) }
        if (r.method != "overwrite") editedUri?.let { targets.add(it) }
        if (targets.isEmpty()) {
            status = "没有可删的文件"
            return
        }
        val (deletable, undeletable) = targets.partition { com.clipmeta.fix.util.MediaDeleter.isDeletable(it) }
        if (undeletable.isNotEmpty()) {
            status = "照片选择器返回的临时条目无法删除，请在相册手动处理；其余继续"
        }
        if (deletable.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val s = com.clipmeta.fix.util.MediaDeleter.buildDeleteRequest(context, deletable)
            if (s != null) {
                pendingDeleteUris = deletable
                try {
                    deleteConsentLauncher.launch(IntentSenderRequest.Builder(s).build())
                    return
                } catch (_: Exception) {
                    pendingDeleteUris = emptyList()
                }
            }
        }
        runDeleteDirect(deletable)
    }

    /** A 的主通道：相册直选（真实 MediaStore URI，可读 GPS）；无 Gallery 则回退位置授权选择器。 */
    fun launchGalleryForA() {
        try {
            if (!PickVideoViaGallery.isAvailable(context)) throw ActivityNotFoundException()
            pendingTarget = "A"
            pickOriginalGallery.launch(Unit)
        } catch (_: Exception) {
            launchWithFallback("A")
        }
    }

    // 位置权限申请（仅 Q+ 需要；拒绝也允许继续选，只是 GPS 会为空）
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locGranted = granted
            when (pendingTarget) {
                "A", "A-gallery" -> launchGalleryForA()
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
            pendingTarget = "A-gallery"
            permissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else if (target == "A") {
            launchGalleryForA()
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
                    "默认走相册直选，可直接读到 GPS；文件方式会打开 DCIM/Camera 目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPickClicked("A") }) {
                        Text("选择原片 A")
                    }
                    OutlinedButton(onClick = { pickOriginalDoc.launch(Unit) }) {
                        Text("文件方式选 A")
                    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPickClicked("B") }) {
                        Text("选择剪辑版 B")
                    }
                    OutlinedButton(onClick = { pickEditedDoc.launch(Unit) }) {
                        Text("文件方式选 B")
                    }
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
                                    "overwrite" -> if (result.renamed)
                                        "✓ 已原位更新并改名为 ${result.newName}，请去相册验证时间与定位"
                                    else
                                        "✓ 已原位更新剪辑版 B（改名被系统拒绝，请手动改名），内容已修复，请去相册验证"
                                    else -> "✓ 已新建条目 ${result.newName ?: result.newUri}，请去相册查看"
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
                            if (!isProcessing) {
                                val deleteLabel = if (r.method == "overwrite") "删除原片A（B即成果，保留）"
                                else "删除原片A和旧B"
                                OutlinedButton(
                                    onClick = { showDeleteConfirm = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        is RepairResult.Failure -> {}
                    }
                }
                if (originalUri == null || editedUri == null) {
                    Text("请先完成步骤 1 与 2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (showDeleteConfirm) {
            val r = lastResult as? RepairResult.Success
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("确认删除？") },
                text = {
                    Text(
                        if (r?.method == "overwrite")
                            "将删除原片 A。剪辑版 B 是本次修复的成果，会保留。此操作不可恢复。"
                        else "将删除原片 A 和旧剪辑版 B，保留修复后的新文件。此操作不可恢复。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showDeleteConfirm = false; onDeleteClicked() }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("取消")
                    }
                }
            )
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
