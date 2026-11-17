package com.aidock.app.ui

import android.Manifest
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aidock.app.Assistant
import com.aidock.app.AssistantCatalog
import com.aidock.app.BuildConfig
import com.aidock.app.R
import com.aidock.app.data.Prefs
import com.aidock.app.export.CaptureResult
import com.aidock.app.export.ConversationExporter
import com.aidock.app.export.ExportSaver
import com.aidock.app.export.SaveResult
import com.aidock.app.web.WebViewHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val VERSION = BuildConfig.VERSION_NAME

@Composable
fun AiDockRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context.applicationContext) }
    var initialId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        initialId = prefs.currentId()
    }

    val ready = initialId
    if (ready == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFDFDFF)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    } else {
        AiDockScreen(initialId = ready, prefs = prefs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiDockScreen(initialId: String, prefs: Prefs) {
    val context = LocalContext.current
    var currentId by rememberSaveable { mutableStateOf(initialId) }
    val current = AssistantCatalog.byId(currentId)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val holder = remember { WebViewHolder(context) }
    val dark = isSystemInDarkTheme()
    val snackbar = remember { SnackbarHostState() }

    var desktopIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 导出对话
    var exportOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exportName by remember { mutableStateOf("") }
    var exportMarkdown by remember { mutableStateOf("") }
    var exportCapture by remember { mutableStateOf<CaptureResult?>(null) }
    var pendingSave by remember { mutableStateOf(false) }

    val isDesktop = current.id in desktopIds

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingSave) {
            pendingSave = false
            scope.launch {
                when (val r = ExportSaver.saveToDownloads(context, exportName, exportMarkdown)) {
                    is SaveResult.Saved -> snackbar.showSnackbar("已保存到 ${r.displayPath}")
                    is SaveResult.Failed -> snackbar.showSnackbar("保存失败：${r.message}")
                    SaveResult.NeedPermission -> snackbar.showSnackbar("没有写入权限，无法保存")
                }
            }
        } else if (!granted) {
            pendingSave = false
            scope.launch { snackbar.showSnackbar("没有写入权限，可以用「分享」或「复制」") }
        }
    }

    fun runExport() {
        if (holder.errorText != null) {
            scope.launch { snackbar.showSnackbar("页面没打开，先刷新再导出") }
            return
        }
        exportOpen = true
        exporting = true
        exportError = null
        exportCapture = null
        scope.launch {
            val capture = ConversationExporter.capture(holder.webView, current)
            exporting = false
            if (!capture.ok) {
                exportError = capture.error
                return@launch
            }
            val md = ConversationExporter.buildMarkdown(
                assistant = current,
                url = capture.url.ifBlank { holder.webView.url ?: current.url },
                title = capture.title,
                messages = capture.messages,
                note = capture.note,
                version = VERSION
            )
            exportMarkdown = md
            exportName = ConversationExporter.fileName(current)
            exportCapture = capture
        }
    }

    fun doSave() {
        if (exportMarkdown.isBlank()) return
        scope.launch {
            when (val r = ExportSaver.saveToDownloads(context, exportName, exportMarkdown)) {
                is SaveResult.Saved -> snackbar.showSnackbar("已保存到 ${r.displayPath}")
                is SaveResult.Failed -> snackbar.showSnackbar("保存失败：${r.message}")
                SaveResult.NeedPermission -> {
                    pendingSave = true
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    // 首次进入：读偏好 → 加载上次选中的助手
    LaunchedEffect(Unit) {
        desktopIds = prefs.desktopIds()
        holder.switchTo(current, current.id in desktopIds)
    }

    // 退到后台时保存网页状态并落盘 Cookie，避免重启后登录态丢失
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder.onAppPause()
                Lifecycle.Event.ON_RESUME -> holder.onAppResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.destroy()
        }
    }

    // 返回键：设置页 → 抽屉 → 网页后退 → 系统退出
    BackHandler(
        enabled = !showSettings && (drawerState.isOpen || holder.canGoBack)
    ) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            holder.goBack()
        }
    }

    val onSelect: (Assistant) -> Unit = { target ->
        if (target.id != currentId) {
            currentId = target.id
            holder.switchTo(target, target.id in desktopIds)
            scope.launch { prefs.setCurrentId(target.id) }
        }
        scope.launch { drawerState.close() }
    }

    AiDockTheme(seed = current.color, dark = dark) {
        Box(Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    AiDockDrawer(
                        current = current,
                        onSelect = onSelect,
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            showSettings = true
                        }
                    )
                }
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AssistantLogo(assistant = current, size = 22.dp)
                                        Spacer(Modifier.width(9.dp))
                                        Column {
                                            Text(
                                                text = current.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = current.domain,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = "打开助手列表"
                                        )
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { runExport() }) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_download),
                                            contentDescription = "导出对话为 Markdown"
                                        )
                                    }
                                    IconButton(onClick = { holder.reload() }) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "刷新"
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { menuOpen = true }) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "更多"
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuOpen,
                                            onDismissRequest = { menuOpen = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("导出对话（Markdown）") },
                                                onClick = {
                                                    menuOpen = false
                                                    runExport()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("在浏览器中打开") },
                                                onClick = {
                                                    menuOpen = false
                                                    holder.openExternal(
                                                        holder.webView.url ?: current.url
                                                    )
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (isDesktop) "切换到移动版网页"
                                                        else "切换到桌面版网页"
                                                    )
                                                },
                                                onClick = {
                                                    menuOpen = false
                                                    val next =
                                                        if (isDesktop) desktopIds - current.id
                                                        else desktopIds + current.id
                                                    desktopIds = next
                                                    scope.launch { prefs.setDesktopIds(next) }
                                                    holder.switchTo(
                                                        current,
                                                        current.id in next,
                                                        forceReload = true
                                                    )
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("清除本站数据") },
                                                onClick = {
                                                    menuOpen = false
                                                    confirmClear = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("设置") },
                                                onClick = {
                                                    menuOpen = false
                                                    showSettings = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("关于 AI dock") },
                                                onClick = {
                                                    menuOpen = false
                                                    showAbout = true
                                                }
                                            )
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            WebProgressBar(progress = holder.progress, visible = holder.isLoading)
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .imePadding()
                    ) {
                        WebViewHost(holder = holder)

                        if (holder.loadingVeil) {
                            LoadingVeil(
                                assistant = current,
                                onOpenBrowser = {
                                    holder.openExternal(holder.webView.url ?: current.url)
                                }
                            )
                        }

                        val err = holder.errorText
                        if (err != null) {
                            ErrorPanel(
                                message = err,
                                fallbackUrl = current.url,
                                onRetry = { holder.retry(current.url) },
                                onOpenBrowser = {
                                    holder.openExternal(holder.webView.url ?: current.url)
                                }
                            )
                        }
                    }
                }
            }

            // 设置页盖在 WebView 之上，WebView 保持挂载，返回后无需重新加载
            if (showSettings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        holder = holder,
                        versionName = VERSION,
                        onBack = { showSettings = false }
                    )
                }
                BackHandler { showSettings = false }
            }
        }
    }

    if (exportOpen) {
        ExportDialog(
            current = current,
            exporting = exporting,
            error = exportError,
            capture = exportCapture,
            markdown = exportMarkdown,
            fileName = exportName,
            onDismiss = { exportOpen = false },
            onSave = { doSave() },
            onShare = {
                val uri = ExportSaver.stageForShare(context, exportName, exportMarkdown)
                if (uri != null) ExportSaver.share(context, uri, "AI dock 对话记录")
                else scope.launch { snackbar.showSnackbar("生成分享文件失败") }
                exportOpen = false
            },
            onCopy = {
                val ok = ExportSaver.copyToClipboard(context, "${current.name} 对话", exportMarkdown)
                scope.launch {
                    snackbar.showSnackbar(if (ok) "Markdown 已复制到剪贴板" else "复制失败")
                }
                exportOpen = false
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除本站数据？") },
            text = {
                Text("将清除所有 AI 站点的 Cookie 与本地缓存，之后每个站点都需要重新登录。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    holder.clearSiteData()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("AI dock") },
            text = {
                Column {
                    Text("版本 $VERSION", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "一个把 ${AssistantCatalog.all.size} 个 AI 助手" +
                            "（国内 ${AssistantCatalog.domestic.size} 个 · 国外 ${AssistantCatalog.foreign.size} 个）" +
                            "装进同一个壳的浏览器容器。网页内容由各站点提供，App 不修改任何第三方页面，" +
                            "也不上传你的对话内容——导出的 Markdown 只写在你自己手机上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            }
        )
    }
}

// ------------------------------------------------------------------ 导出弹窗

@Composable
private fun ExportDialog(
    current: Assistant,
    exporting: Boolean,
    error: String?,
    capture: CaptureResult?,
    markdown: String,
    fileName: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val ready = capture != null && markdown.isNotBlank()

    val preview = remember(markdown) {
        val body = markdown.substringAfter("---\n", markdown).trim()
        body.take(420)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_download),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("导出对话为 Markdown", style = MaterialTheme.typography.titleSmall)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    exporting -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在读取当前页面…",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }

                    error != null -> Column(Modifier.fillMaxWidth()) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "提示：先发一条消息让对话渲染出来；超长对话受网页虚拟滚动限制，只能导出已加载的部分。",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }

                    ready -> Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "识别到 ${capture!!.messages.size} 条消息",
                                style = MaterialTheme.typography.labelLarge,
                                color = scheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(8.dp))
                            val srcLabel = when (capture!!.source) {
                                "adapter" -> "站点适配"
                                "generic" -> "结构识别"
                                else -> "整页文本"
                            }
                            Text(
                                text = srcLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$fileName · 约 ${capture!!.chars} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                        if (capture!!.note.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = capture!!.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = scheme.surfaceVariant.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = scheme.onSurfaceVariant,
                                maxLines = 9,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 190.dp)
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (ready) {
                Button(onClick = onSave) { Text("保存到下载") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (ready) {
                Row {
                    TextButton(onClick = onCopy) { Text("复制") }
                    TextButton(onClick = onShare) { Text("分享") }
                }
            } else if (!exporting) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ------------------------------------------------------------------ 抽屉

@Composable
private fun AiDockDrawer(
    current: Assistant,
    onSelect: (Assistant) -> Unit,
    onOpenSettings: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val brand = readableBrand(current.color)

    // 手机按屏宽 82% 取宽，平板上封顶 320dp，避免大屏抽屉拉满整屏
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth = if (screenWidth >= 600.dp) 320.dp else screenWidth * 0.82f

    ModalDrawerSheet(
        modifier = Modifier.width(drawerWidth),
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        drawerContainerColor = scheme.surface,
        drawerContentColor = scheme.onSurface
    ) {
        // 头部：品牌色极淡渐变，不整块铺色，避免与网页割裂
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            current.color.copy(alpha = 0.13f),
                            current.color.copy(alpha = 0.03f),
                            scheme.surface
                        )
                    )
                )
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "AI dock",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "AI 助手聚合工作台",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(brand.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(brand)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "当前：${current.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = brand,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.7f))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            drawerGroup("国内助手", AssistantCatalog.domestic.size)
            items(AssistantCatalog.domestic, key = { it.id }) { item ->
                AssistantRow(
                    assistant = item,
                    selected = item.id == current.id,
                    onClick = { onSelect(item) }
                )
            }

            drawerGroup("国外助手", AssistantCatalog.foreign.size)
            items(AssistantCatalog.foreign, key = { it.id }) { item ->
                AssistantRow(
                    assistant = item,
                    selected = item.id == current.id,
                    onClick = { onSelect(item) }
                )
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.7f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "设置",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
        }

        Text(
            text = "网页内容由第三方站点提供 · v$VERSION",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.drawerGroup(title: String, count: Int) {
    item(key = "hdr-" + title) {
        Text(
            text = "$title · $count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun AssistantRow(
    assistant: Assistant,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val brand = readableBrand(assistant.color)
    val dark = isSystemInDarkTheme()

    val rowBg = when {
        selected -> assistant.color.copy(alpha = if (dark) 0.22f else 0.11f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistantLogo(assistant = assistant, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = assistant.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) brand else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = assistant.domain,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant.copy(alpha = if (selected) 0.95f else 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选中",
                tint = brand,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ 主体

@Composable
private fun WebViewHost(holder: WebViewHolder) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        factory = { ctx ->
            // 用 FrameLayout 兜住常驻 WebView：换肤只触发重组，不会重建 WebView
            FrameLayout(ctx).apply {
                (holder.webView.parent as? ViewGroup)?.removeView(holder.webView)
                addView(
                    holder.webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    )
}

/**
 * 换站点时的加载遮罩。
 *
 * 遮罩的作用不是"好看"：WebView 在新文档首次绘制前会一直显示上一页的像素，
 * 不盖住就会出现"标题已是 ChatGPT、正文还是上一个站"的错乱。
 * 超过 8 秒还没好，给出网络提示与「用浏览器打开」的退路。
 */
@Composable
private fun LoadingVeil(assistant: Assistant, onOpenBrowser: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(assistant.id) {
        slow = false
        delay(8000)
        slow = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            AssistantLogo(assistant = assistant, size = 52.dp)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = scheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在打开 ${assistant.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = assistant.domain,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            if (slow) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "比平时慢一些，可能是网络或代理的问题。\n国内站点可直接访问，\n国外站点需要可用的网络环境。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenBrowser) { Text("用浏览器打开") }
            }
        }
    }
}

@Composable
private fun WebProgressBar(progress: Int, visible: Boolean) {
    val fraction by animateFloatAsState(
        targetValue = (progress.coerceIn(0, 100) / 100f),
        animationSpec = tween(durationMillis = 220),
        label = "web-progress"
    )
    val primary = MaterialTheme.colorScheme.primary
    val track = primary.copy(alpha = 0.14f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(if (visible) track else Color.Transparent)
    ) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                    .height(2.dp)
                    .background(primary)
            )
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    fallbackUrl: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "页面打不开",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = onRetry) { Text("重试") }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenBrowser) { Text("用浏览器打开") }
            Spacer(Modifier.height(6.dp))
            Text(
                text = fallbackUrl,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
