package com.clipmeta.fix

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
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
    // 分享收件箱：相册 ACTION_SEND / SEND_MULTIPLE 带来的视频。Activity 层持有，
    // Compose 侧消费一次（takeInbox），singleTop + onNewIntent 保证前台再分享也能接到。
    var sharedInbox by mutableStateOf(emptyList<Uri>())
        private set

    fun takeInbox(): List<Uri> {
        val u = sharedInbox
        sharedInbox = emptyList()
        return u
    }

    private fun absorbShare(i: Intent?) {
        val uris = com.clipmeta.fix.util.SharedVideoReceiver.extractUris(i)
        if (uris.isNotEmpty()) sharedInbox = uris
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        absorbShare(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ClipMetaFixScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        absorbShare(intent)
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
    var pendingDeleteDone by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // 归一化 URI → 原选择 URI（删框/直删用归一化形，清状态用原形）
    val deleteBackMap = remember { mutableMapOf<Uri, Uri>() }
    val scope = rememberCoroutineScope()

    fun onDeleteDone(targets: List<Uri>, clearResult: Boolean = true) {
        for (u in targets) com.clipmeta.fix.util.MediaDeleter.releasePersistable(context, u)
        if (originalUri in targets) {
            originalUri = null
            originalInfo = null
        }
        if (editedUri in targets) {
            editedUri = null
            editedInfo = null
        }
        if (clearResult) lastResult = null
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

    /** 交换 A/B（含摘要与加载态；旧修复结果作废，避免删错）。 */
    fun swapAB() {
        val tu = originalUri
        val ti = originalInfo
        val tl = originalLoading
        originalUri = editedUri
        originalInfo = editedInfo
        originalLoading = editedLoading
        editedUri = tu
        editedInfo = ti
        editedLoading = tl
        lastResult = null
        status = "已交换 A/B，请核对时间与 GPS 后再执行修复"
    }

    /**
     * 分享视频的自动分配：逐个 queryWithGps（复用防呆链路）→ GPS/时长分类 →
     * 直接落槽（省一次重复查询）。声明顺序必须在消费方（LaunchedEffect）之前。
     */
    fun handleSharedUris(uris: List<Uri>) {
        val top = uris.take(2)
        if (top.isEmpty()) return
        status = "正在识别分享来的 ${top.size} 个视频…"
        scope.launch {
            val infos = withContext(Dispatchers.IO) {
                top.map { it to MediaInfoHelper.queryWithGps(context, it) }
            }
            val assignment = com.clipmeta.fix.util.SharedVideoReceiver.classify(
                infos.map { (u, info) ->
                    com.clipmeta.fix.util.SharedVideoReceiver.Candidate(
                        uri = u,
                        hasGps = !info.location.isNullOrBlank(),
                        durationMs = info.durationMs,
                        sizeBytes = info.sizeBytes,
                        name = info.displayName
                    )
                }
            )
            val aInfo = infos.firstOrNull { it.first == assignment.a }
            val bInfo = infos.firstOrNull { it.first == assignment.b }
            if (aInfo != null) {
                originalUri = aInfo.first
                originalInfo = aInfo.second
                originalLoading = false
            }
            if (bInfo != null) {
                editedUri = bInfo.first
                editedInfo = bInfo.second
                editedLoading = false
            }
            lastResult = null
            val aName = aInfo?.second?.displayName
            val bName = bInfo?.second?.displayName
            status = when (assignment.basis) {
                "gps" -> "已从分享自动识别：有 GPS 的“${aName}”进 A，无 GPS 的“${bName}”进 B；若不对请点“交换 A/B”"
                "duration" -> "分享的两个视频 GPS 无法区分，已按时长自动分配（A“${aName}”/B“${bName}”）；若不对请点“交换 A/B”"
                "single-gps" -> "分享的视频有 GPS，已放入 A 槽；请再选剪辑版 B"
                "single-nogps" -> "分享的视频无 GPS，已放入 B 槽；请再选原片 A"
                else -> "未能识别分享的视频，请手动选择"
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

    // 删除系统授权回调：用户点了允许即视为删框内条目删完，清状态；取消则只清已直删的。
    val deleteConsentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val done = pendingDeleteUris.map { deleteBackMap[it] ?: it }
                onDeleteDone(done)
                status = "已删除所选原文件，请去相册确认"
            } else {
                val done = pendingDeleteDone.map { deleteBackMap[it] ?: it }
                if (done.isNotEmpty()) onDeleteDone(done, clearResult = false)
                status = "删除未完成（系统确认被拒绝或取消）" +
                    if (done.isNotEmpty()) "，已直删 ${done.size} 个，剩余仍在" else "，原文件仍在"
            }
            pendingDeleteUris = emptyList()
            pendingDeleteDone = emptyList()
        }

    /**
     * 删除执行体：安全规则 overwrite→只删A；insert→删A+旧B。picker 会话 URI 删不动会明说。
     * 顺序：先逐个直删（自己的文件零弹窗秒删），被拒的再攒起来走一次系统删框，
     * 每一步的真实原因都进状态栏，不再吞异常。调用前须确认宽泛读权限（见 onDeleteClicked）。
     */
    fun proceedDelete() {
        val r = lastResult as? RepairResult.Success ?: return
        val targets = mutableListOf<Uri>()
        originalUri?.let { targets.add(it) }
        if (r.method != "overwrite") editedUri?.let { targets.add(it) }
        if (targets.isEmpty()) {
            status = "没有可删的文件"
            return
        }
        // 先逐个归一化成标准条目 URI（含 picker 会话 URI，一视同仁反查）；
        // 归一命中的进删除流，miss 的才进手动提示。回映射留着清状态用。
        deleteBackMap.clear()
        val resolveNotes = mutableListOf<String>()
        val resolved = mutableListOf<Uri>()
        val unresolvable = mutableListOf<String>()
        for (u in targets) {
            val info = if (u == originalUri) originalInfo else if (u == editedUri) editedInfo else null
            val label = if (u == originalUri) "A" else if (u == editedUri) "B" else "?"
            when (val out = com.clipmeta.fix.util.MediaDeleter.resolveForDelete(
                context, u, info?.displayName, info?.sizeBytes ?: -1, info?.durationMs
            )) {
                is com.clipmeta.fix.util.MediaDeleter.ResolveOutcome.Hit -> {
                    if (out.uri != u) resolveNotes.add("${label}归一命中")
                    deleteBackMap[out.uri] = u
                    resolved.add(out.uri)
                }
                is com.clipmeta.fix.util.MediaDeleter.ResolveOutcome.Miss -> {
                    resolveNotes.add("${label}反查miss(${out.reason})")
                    // Document URI 反查 miss 也值得直试 deleteDocument（有持久化授权常能成）；
                    // picker 会话 URI 直试必败，进手动。
                    if (com.clipmeta.fix.util.MediaDeleter.isDocumentUri(u)) {
                        deleteBackMap[u] = u
                        resolved.add(u)
                    } else {
                        unresolvable.add("$label（反查不到请在相册手动删）")
                    }
                }
            }
        }
        if (unresolvable.isNotEmpty()) {
            status = "${unresolvable.joinToString("；")}需在相册手动处理；其余继续"
        }
        if (resolved.isEmpty()) return
        fun toOriginal(u: Uri): Uri = deleteBackMap[u] ?: u
        scope.launch {
            val deleted = mutableListOf<Uri>()
            val denied = mutableListOf<Uri>()
            val errHints = mutableListOf<String>()
            var singleSender: IntentSender? = null
            withContext(Dispatchers.IO) {
                for (uri in resolved) {
                    try {
                        if (com.clipmeta.fix.util.MediaDeleter.deleteDirect(context, uri)) deleted.add(uri)
                        else denied.add(uri)
                    } catch (e: Exception) {
                        val sender = (e as? android.app.RecoverableSecurityException)
                            ?.userAction?.actionIntent?.intentSender
                        if (sender != null && singleSender == null) singleSender = sender
                        denied.add(uri)
                        errHints.add(com.clipmeta.fix.util.MediaDeleter.shortErr(e))
                    }
                }
            }
            // R+：被拒的走一次批量系统删框。先自检：非标准形不进批量，
            // 免得一个坏 URI 让整批被拒；它们直接进手动。
            val manualOnly = mutableListOf<Uri>()
            if (denied.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val batchBad = denied.filter {
                    !com.clipmeta.fix.util.MediaDeleter.isStandardMediaItem(it)
                }
                if (batchBad.isNotEmpty()) {
                    manualOnly.addAll(batchBad)
                    denied.removeAll(batchBad.toSet())
                }
                if (denied.isNotEmpty()) {
                when (val req = com.clipmeta.fix.util.MediaDeleter.buildDeleteRequest(context, denied)) {
                    is com.clipmeta.fix.util.MediaDeleter.DeleteRequest.Ready -> {
                        pendingDeleteUris = deleted + denied
                        pendingDeleteDone = deleted.toList()
                        try {
                            deleteConsentLauncher.launch(IntentSenderRequest.Builder(req.sender).build())
                            return@launch
                        } catch (e: Exception) {
                            pendingDeleteUris = emptyList()
                            pendingDeleteDone = emptyList()
                            errHints.add("弹框失败:" + com.clipmeta.fix.util.MediaDeleter.shortErr(e))
                        }
                    }
                    is com.clipmeta.fix.util.MediaDeleter.DeleteRequest.Failed ->
                        errHints.add("删框构造失败:" + req.reason)
                    is com.clipmeta.fix.util.MediaDeleter.DeleteRequest.Unsupported -> {}
                }
                }
            }
            // Q 单条授权 Sender（无批量框时）
            val sender = singleSender
            if (denied.isNotEmpty() && sender != null) {
                pendingDeleteUris = deleted + denied
                pendingDeleteDone = deleted.toList()
                try {
                    deleteConsentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    return@launch
                } catch (e: Exception) {
                    pendingDeleteUris = emptyList()
                    pendingDeleteDone = emptyList()
                    errHints.add("弹框失败:" + com.clipmeta.fix.util.MediaDeleter.shortErr(e))
                }
            }
            val leftover = denied + manualOnly
            if (leftover.isEmpty()) {
                onDeleteDone(deleted.map(::toOriginal))
                status = "已删除所选原文件，请去相册确认"
            } else {
                if (deleted.isNotEmpty()) onDeleteDone(deleted.map(::toOriginal), clearResult = false)
                val mperm = if (com.clipmeta.fix.util.MediaDeleter.hasBroadMediaRead(context)) "有" else "无"
                val hint = (resolveNotes + errHints + listOf("mperm:$mperm")).distinct().take(4).joinToString("；")
                val shapes = leftover.map { d ->
                    val o = toOriginal(d)
                    val label = if (o == originalUri) "A" else if (o == editedUri) "B" else "?"
                    val skip = if (d in manualOnly) "跳过删框" else null
                    "$label(${com.clipmeta.fix.util.MediaDeleter.uriShape(d)}${skip?.let { ";$it" } ?: ""})"
                }.joinToString("；")
                val partialTip =
                    if (Build.VERSION.SDK_INT >= 34 && resolveNotes.any { it.contains("查0行") })
                        "；若照片权限给的是“仅选中部分”，请去系统设置改成允许全部后重试"
                    else ""
                status = "删不动 ${leftover.size} 个[$shapes]" +
                    (if (hint.isNotBlank()) "（$hint$partialTip）" else "") + "，请在相册手动删除"
            }
        }
    }

    // 宽泛媒体读授权（删除反查全库的前提）：通过后自动继续删除；拒绝则维持手动删
    val mediaReadLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                proceedDelete()
            } else {
                status = "未授予媒体库访问权限，反查不到条目，请在相册手动删除"
            }
        }

    /** 删除入口（含宽泛读权限门；声明顺序必须在 proceedDelete 之后、调用方之前）。 */
    fun onDeleteClicked() {
        if (!com.clipmeta.fix.util.MediaDeleter.hasBroadMediaRead(context)) {
            status = "删除需要媒体库访问权限，请在弹窗中允许后自动继续"
            try {
                mediaReadLauncher.launch(com.clipmeta.fix.util.MediaDeleter.broadReadPermissionName())
            } catch (_: Exception) {
                status = "无法弹出媒体权限申请，请去系统设置授予后重试，或在相册手动删除"
            }
            return
        }
        proceedDelete()
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

    // 分享消费：收件箱非空（冷启动或前台再分享）即识别分配一次，takeInbox 保证只消费一次。
    val activity = context as? MainActivity
    val inbox = activity?.sharedInbox ?: emptyList()
    LaunchedEffect(inbox) {
        if (inbox.isNotEmpty()) {
            val incoming = activity?.takeInbox() ?: emptyList()
            if (incoming.isNotEmpty()) handleSharedUris(incoming)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ClipMetaFix", style = MaterialTheme.typography.headlineSmall)
        Text(
            "解决澎湃OS剪辑后丢失 GPS/拍摄时间的工具。按顺序选择 原片A 与 剪辑版B，执行修复；也可从相册直接分享 1~2 个视频过来自动识别。",
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
                if (originalUri != null && editedUri != null) {
                    OutlinedButton(onClick = { swapAB() }, modifier = Modifier.fillMaxWidth()) {
                        Text("交换 A/B（自动识别分错时用）")
                    }
                }
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
