package com.aidock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidock.app.AssistantCatalog
import com.aidock.app.data.AccountVault
import com.aidock.app.data.PinCheck
import com.aidock.app.data.PinPolicy
import com.aidock.app.data.VaultAccount
import com.aidock.app.data.VaultSnapshot
import com.aidock.app.web.WebViewHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 需要验证码才能继续的动作 */
private enum class PinAsk { REVEAL, DISABLE, CHANGE }

private const val REVEAL_WINDOW_MILLIS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    holder: WebViewHolder,
    versionName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { AccountVault(context) }
    val scheme = MaterialTheme.colorScheme

    var snap by remember { mutableStateOf<VaultSnapshot?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 密码明文只在这段时间内可见，到点自动打回星号
    var revealUntil by remember { mutableLongStateOf(0L) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val revealed = revealUntil > nowTick

    var ask by remember { mutableStateOf<PinAsk?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinLockLeft by remember { mutableIntStateOf(0) }
    var setupPin by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<VaultAccount?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VaultAccount?>(null) }
    var confirmClearSites by remember { mutableStateOf(false) }
    var confirmWipeVault by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    /** 统一兜底：任何保险箱操作失败都只弹提示，不让异常冒到主线程 */
    fun vaultOp(success: String = "", block: suspend () -> VaultSnapshot) {
        scope.launch {
            try {
                snap = block()
                if (success.isNotEmpty()) toast(success)
            } catch (t: Throwable) {
                toast("操作失败：" + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { vault.snapshot() }.onSuccess { snap = it }
    }

    // 显示密码窗口的倒计时
    LaunchedEffect(revealUntil) {
        if (revealUntil <= 0L) return@LaunchedEffect
        while (true) {
            nowTick = System.currentTimeMillis()
            if (nowTick >= revealUntil) {
                revealUntil = 0L
                break
            }
            delay(500)
        }
    }

    // 验证码错误锁定的秒级倒计时
    LaunchedEffect(pinLockLeft) {
        if (pinLockLeft <= 0) return@LaunchedEffect
        delay(1000)
        pinLockLeft = (pinLockLeft - 1).coerceAtLeast(0)
    }

    DisposableEffect(Unit) {
        onDispose { revealUntil = 0L }
    }

    fun startAsk(kind: PinAsk) {
        // 先读一次最新策略：锁定状态与位数可能刚被改过，用内存里的旧值会漏掉锁定
        scope.launch {
            val fresh = runCatching { vault.snapshot() }.getOrNull()
            if (fresh != null) snap = fresh
            val p = fresh?.pin ?: snap?.pin ?: PinPolicy()
            pinError = null
            pinLockLeft = p.lockedSeconds(System.currentTimeMillis())
            if (!p.enabled || p.hash.isBlank()) {
                // 未开启二次验证：直接放行
                when (kind) {
                    PinAsk.REVEAL -> revealUntil = System.currentTimeMillis() + REVEAL_WINDOW_MILLIS
                    PinAsk.DISABLE -> vaultOp("已关闭二次验证") { vault.setPinEnabled(false, "") }
                    PinAsk.CHANGE -> setupPin = true
                }
            } else {
                ask = kind
            }
        }
    }

    val policy = snap?.pin ?: PinPolicy()

    fun onPinEntered(pin: String) {
        scope.launch {
            val pair = try {
                vault.verifyPin(pin)
            } catch (t: Throwable) {
                pinError = "验证失败：" + (t.message ?: t.javaClass.simpleName)
                return@launch
            }
            val (result, nextPolicy) = pair
            when (result) {
                is PinCheck.Ok -> {
                    snap = snap?.copy(pin = nextPolicy)
                    pinError = null
                    val kind = ask
                    ask = null
                    when (kind) {
                        PinAsk.REVEAL -> revealUntil = System.currentTimeMillis() + REVEAL_WINDOW_MILLIS
                        PinAsk.DISABLE -> vaultOp("已关闭二次验证") { vault.setPinEnabled(false, "") }
                        PinAsk.CHANGE -> setupPin = true
                        null -> Unit
                    }
                }
                is PinCheck.Wrong -> pinError = "验证码不正确，还可以尝试 ${result.remaining} 次"
                is PinCheck.Locked -> {
                    // 锁定状态必须回写进内存快照，否则重开弹窗仍会显示可输入的键盘
                    snap = snap?.copy(pin = nextPolicy)
                    pinError = null
                    pinLockLeft = result.seconds
                    ask = null
                    toast("错误次数过多，已锁定 ${result.seconds} 秒")
                }
            }
        }
    }

    Scaffold(
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "账号 · 安全 · 数据",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.surface,
                    titleContentColor = scheme.onSurface,
                    navigationIconContentColor = scheme.onSurface
                )
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp
            )
        ) {
            // ------------------------------------------------ 账号管理
            item {
                SettingsSectionTitle("账号管理") {
                    if ((snap?.accounts?.size ?: 0) > 0) {
                        TextButton(onClick = {
                            if (revealed) revealUntil = 0L else startAsk(PinAsk.REVEAL)
                        }) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    id = if (revealed) com.aidock.app.R.drawable.ic_eye_off
                                    else com.aidock.app.R.drawable.ic_eye
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(if (revealed) "隐藏密码" else "显示密码")
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, scheme.outlineVariant.copy(alpha = 0.75f)
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (revealed) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(scheme.primary.copy(alpha = 0.08f))
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(scheme.primary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "密码已显示 · ${((revealUntil - nowTick) / 1000 + 1).coerceAtLeast(1)} 秒后自动隐藏",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.primary
                                )
                            }
                        }

                        val accounts = snap?.accounts.orEmpty()
                        if (accounts.isEmpty()) {
                            EmptyAccounts()
                        } else {
                            accounts.forEachIndexed { index, account ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 60.dp),
                                        color = scheme.outlineVariant.copy(alpha = 0.55f)
                                    )
                                }
                                AccountRow(
                                    account = account,
                                    revealed = revealed,
                                    onEdit = { editor = account; editorOpen = true },
                                    onDelete = { deleting = account },
                                    onCopy = {
                                        if (account.password.isBlank()) {
                                            toast("这条记录没有密码")
                                        } else if (!revealed && policy.enabled) {
                                            startAsk(PinAsk.REVEAL)
                                            toast("先验证身份，再复制密码")
                                        } else {
                                            if (!revealed) {
                                                revealUntil =
                                                    System.currentTimeMillis() + REVEAL_WINDOW_MILLIS
                                            }
                                            val ok = com.aidock.app.export.ExportSaver
                                                .copyToClipboard(
                                                    context, account.username.ifBlank { "密码" },
                                                    account.password
                                                )
                                            toast(if (ok) "密码已复制到剪贴板" else "复制失败")
                                        }
                                    }
                                )
                            }
                        }

                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.55f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editor = null
                                    editorOpen = true
                                }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "添加账号",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "账号由你手动登记，App 不会读取网页里输入的密码。密码用 AES-256-GCM " +
                        "加密存在本机，密钥在系统 Keystore 里，卸载或换机后无法还原。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
                )
            }

            // ------------------------------------------------ 安全
            item { SettingsSectionTitle("安全") }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, scheme.outlineVariant.copy(alpha = 0.75f)
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        SettingsRow(
                            icon = { Icon(Icons.Default.Lock, null, Modifier.size(19.dp), tint = scheme.primary) },
                            title = "显示密码前二次验证",
                            subtitle = if (policy.enabled) {
                                "已开启 · ${policy.length} 位数字验证码"
                            } else {
                                "已关闭 · 点击「显示密码」直接可见"
                            },
                            trailing = {
                                Switch(
                                    checked = policy.enabled,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            if (policy.hash.isBlank()) setupPin = true
                                            else vaultOp("已开启二次验证") {
                                                vault.setPinEnabled(true, "")
                                            }
                                        } else {
                                            startAsk(PinAsk.DISABLE)
                                        }
                                    }
                                )
                            }
                        )

                        if (policy.enabled) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 54.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.55f)
                            )
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    "验证码位数",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurface
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LengthPill("4 位数字", policy.length == 4) {
                                        if (policy.length != 4) startAsk(PinAsk.CHANGE)
                                    }
                                    LengthPill("6 位数字", policy.length == 6) {
                                        if (policy.length != 6) startAsk(PinAsk.CHANGE)
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 54.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.55f)
                            )
                            SettingsRow(
                                icon = {
                                    Icon(
                                        Icons.Default.Edit, null,
                                        Modifier.size(19.dp), tint = scheme.primary
                                    )
                                },
                                title = "修改验证码",
                                subtitle = "需要先验证当前验证码",
                                onClick = { startAsk(PinAsk.CHANGE) }
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------ 数据
            item { SettingsSectionTitle("数据与存储") }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, scheme.outlineVariant.copy(alpha = 0.75f)
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        SettingsRow(
                            icon = {
                                Icon(
                                    Icons.Default.Warning, null,
                                    Modifier.size(19.dp), tint = scheme.onSurfaceVariant
                                )
                            },
                            title = "清除所有站点数据",
                            subtitle = "清空 Cookie 与缓存，所有助手需要重新登录",
                            onClick = { confirmClearSites = true }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 54.dp),
                            color = scheme.outlineVariant.copy(alpha = 0.55f)
                        )
                        SettingsRow(
                            icon = {
                                Icon(
                                    Icons.Default.Delete, null,
                                    Modifier.size(19.dp), tint = scheme.error
                                )
                            },
                            title = "清空账号保险箱",
                            subtitle = if ((snap?.accounts?.size ?: 0) == 0) "当前没有保存的账号"
                            else "删除全部 ${snap?.accounts?.size} 个已保存账号",
                            enabled = (snap?.accounts?.size ?: 0) > 0,
                            onClick = { confirmWipeVault = true }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 54.dp),
                            color = scheme.outlineVariant.copy(alpha = 0.55f)
                        )
                        SettingsRow(
                            icon = {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(
                                        id = com.aidock.app.R.drawable.ic_download
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(19.dp),
                                    tint = scheme.onSurfaceVariant
                                )
                            },
                            title = "对话导出位置",
                            subtitle = "下载/AI dock/ · .md 文件",
                            onClick = null
                        )
                    }
                }
            }

            // ------------------------------------------------ 关于
            item { SettingsSectionTitle("关于") }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, scheme.outlineVariant.copy(alpha = 0.75f)
                    )
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        InfoLine("版本", versionName)
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = scheme.outlineVariant.copy(alpha = 0.55f)
                        )
                        InfoLine(
                            "已接入",
                            "${AssistantCatalog.domestic.size} 个国内 · " +
                                "${AssistantCatalog.foreign.size} 个国外助手"
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = scheme.outlineVariant.copy(alpha = 0.55f)
                        )
                        InfoLine(
                            "加密",
                            if (snap?.hardwareBacked == true) "AndroidKeyStore · AES-256-GCM"
                            else "本地密钥 · AES-256-GCM（该系统不支持 Keystore）"
                        )
                    }
                }
            }

            item {
                Text(
                    text = "二次验证是防止旁人随手点开密码的界面闸门，" +
                        "不能替代系统锁屏与设备加密。请把手机锁屏密码一起设好。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
                )
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗

    ask?.let { kind ->
        PinVerifyDialog(
            title = when (kind) {
                PinAsk.REVEAL -> "显示密码"
                PinAsk.DISABLE -> "关闭二次验证"
                PinAsk.CHANGE -> "修改验证码"
            },
            hint = when (kind) {
                PinAsk.REVEAL -> "输入 ${policy.length} 位验证码后显示密码"
                PinAsk.DISABLE -> "输入 ${policy.length} 位验证码以关闭保护"
                PinAsk.CHANGE -> "输入当前 ${policy.length} 位验证码"
            },
            length = policy.length,
            error = pinError,
            lockedSeconds = pinLockLeft,
            onDismiss = {
                ask = null
                pinError = null
            },
            onVerify = { onPinEntered(it) }
        )
    }

    if (setupPin) {
        PinSetupDialog(
            title = if (policy.hash.isBlank()) "设置验证码" else "设置新验证码",
            initialLength = policy.length,
            onDismiss = { setupPin = false },
            onDone = { pin ->
                setupPin = false
                vaultOp("验证码已保存 · " + (if (pin.length == 6) "6" else "4") + " 位数字") {
                    vault.setPinEnabled(true, pin)
                }
            }
        )
    }

    if (editorOpen) {
        AccountEditorDialog(
            initial = editor,
            onDismiss = { editorOpen = false },
            onSave = { siteId, siteName, user, pwd, note ->
                editorOpen = false
                val base = editor
                vaultOp(if (base == null) "已保存到账号保险箱" else "已更新") {
                    val account = VaultAccount(
                        id = base?.id ?: vault.newId(),
                        assistantId = siteId,
                        username = user,
                        password = pwd,
                        note = note,
                        updatedAt = System.currentTimeMillis(),
                        siteName = siteName
                    )
                    val next = vault.upsert(account)
                    revealUntil = 0L
                    next
                }
            }
        )
    }

    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条账号？") },
            text = { Text("将永久删除「${account.username}」的账号记录，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = account.id
                    deleting = null
                    vaultOp("已删除") { vault.delete(id) }
                }) { Text("删除", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }

    if (confirmClearSites) {
        AlertDialog(
            onDismissRequest = { confirmClearSites = false },
            title = { Text("清除所有站点数据？") },
            text = {
                Text("将清空全部助手的 Cookie 与本地缓存，之后每个站点都需要重新登录。账号保险箱里的记录不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearSites = false
                    holder.clearSiteData()
                    toast("站点数据已清除")
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSites = false }) { Text("取消") }
            }
        )
    }

    if (confirmWipeVault) {
        AlertDialog(
            onDismissRequest = { confirmWipeVault = false },
            title = { Text("清空账号保险箱？") },
            text = { Text("将删除全部已保存的账号与密码，此操作不可撤销。验证码设置会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipeVault = false
                    vaultOp("账号保险箱已清空") {
                        revealUntil = 0L
                        vault.deleteAllAccounts()
                    }
                }) { Text("全部删除", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipeVault = false }) { Text("取消") }
            }
        )
    }
}

// ------------------------------------------------------------------ 小组件

@Composable
private fun EmptyAccounts() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "还没有保存账号",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "把常用的 AI 站点账号记在这里，\n下次登录不用再翻备忘录。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AccountRow(
    account: VaultAccount,
    revealed: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val assistant = AssistantCatalog.all.firstOrNull { it.id == account.assistantId }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (assistant != null) {
            AssistantLogo(assistant = assistant, size = 34.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (account.siteName.ifBlank { "AI" }).take(1),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = account.username.ifBlank { "（未填账号）" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = assistant?.name ?: account.siteName.ifBlank { "自定义站点" },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                if (account.note.isNotBlank()) {
                    Text(
                        text = " · " + account.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            when {
                account.broken -> Text(
                    "密码无法解密，请重新编辑填写",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error
                )
                account.password.isBlank() -> Text(
                    "未保存密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                revealed -> Text(
                    text = account.password,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                else -> Text(
                    text = "•".repeat(account.password.length.coerceIn(6, 12)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp
                )
            }
        }

        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "账号操作",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("复制密码") },
                    onClick = { menu = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = { menu = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = scheme.error) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun LengthPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) scheme.primary else scheme.outline.copy(alpha = 0.45f),
                RoundedCornerShape(percent = 50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    Modifier.size(13.dp), tint = scheme.primary
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant.copy(alpha = if (enabled) 0.9f else 0.5f)
            )
        }
        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface
        )
    }
}

// ------------------------------------------------------------------ 账号编辑

@Composable
private fun AccountEditorDialog(
    initial: VaultAccount?,
    onDismiss: () -> Unit,
    onSave: (siteId: String, siteName: String, user: String, pwd: String, note: String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var siteId by remember {
        mutableStateOf(initial?.assistantId ?: AssistantCatalog.all.first().id)
    }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pwd by remember { mutableStateOf(initial?.password ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var showPwd by remember { mutableStateOf(false) }
    var siteMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val site = AssistantCatalog.all.firstOrNull { it.id == siteId } ?: AssistantCatalog.all.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加账号" else "编辑账号") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 站点选择
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp, scheme.outline.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { siteMenu = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistantLogo(assistant = site, size = 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                site.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface
                            )
                            Text(
                                site.domain,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "更换",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = siteMenu,
                        onDismissRequest = { siteMenu = false }
                    ) {
                        AssistantCatalog.all.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.name) },
                                leadingIcon = { AssistantLogo(assistant = a, size = 22.dp) },
                                onClick = {
                                    siteId = a.id
                                    siteMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = {
                        user = it
                        error = null
                    },
                    label = { Text("账号 / 手机号 / 邮箱") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPwd) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPwd = !showPwd }) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    id = if (showPwd) com.aidock.app.R.drawable.ic_eye_off
                                    else com.aidock.app.R.drawable.ic_eye
                                ),
                                contentDescription = if (showPwd) "隐藏" else "显示",
                                modifier = Modifier.size(19.dp),
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正在输入的密码可直接查看；已保存的密码需二次验证后才能看。",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (user.isBlank()) {
                    error = "请填写账号"
                } else {
                    onSave(site.id, site.name, user.trim(), pwd, note.trim())
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
