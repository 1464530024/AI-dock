# AI dock 技术说明

> 面向要改代码、复现构建或评估安全性的人。设计动机与功能清单看 [README](../README.md)。

---

## 1. 定位与设计约束

AI dock 是一个**浏览器壳**（browser shell），不是 AI 客户端：它不调用任何厂商的 API，不持有 token，不代理流量，只把各站点在 WebView 里渲染出来，并补上原生 App 该有的体验（切换、状态保持、账号本、导出）。

四条约束决定了后面所有设计：

| 约束 | 推论 |
| :-- | :-- |
| 不依赖厂商 API（它们也没开放对话导出） | 导出只能读**已渲染的 DOM**，必须容忍站点改版 |
| 不在壳里保存任何登录 token | Cookie 归 WebView 自己管理，壳只负责落盘与清理 |
| 零后端 | 所有状态在本机；账号本必须自己加密 |
| 单 Activity | 用 Compose 状态驱动界面，不用 Fragment/NavHost 那套 |

## 2. 技术选型

| 方案 | 结论 | 原因 |
| :-- | :-- | :-- |
| **原生 Kotlin + Compose** | ✅ 采用 | 只需要一个 `WebView` + 一层壳，跨端框架在这里是纯负担；壳层要跟 12 种品牌色做细腻的配色派生，Compose 的动态 `ColorScheme` 最省事 |
| Capacitor 6 | ❌ | 项目里已有别的 Capacitor 项目，但它的价值在"复用 Web 前端"，本项目前端就是别人的站点，套两层 WebView 反而更重 |
| Flutter | ❌ | 本机没有 Flutter SDK，且 platform view 里的 `WebView` 状态管理比原生更绕 |
| 每个助手一个 Activity/WebView | ❌ | 12 个 WebView 常驻会有明显内存压力，且切换时重建成本高。改成**单实例 + 状态快照** |

依赖极简：除 AndroidX 官方库（core-ktx / activity-compose / lifecycle / compose / datastore）外**没有第三方依赖**。

## 3. 工程与构建

```
JDK 17
Gradle 8.2.1（wrapper）
AGP 8.2.1 / Kotlin 1.9.22 / Compose 编译器 1.5.8
Compose BOM 2024.02.01
compileSdk 34 · minSdk 24 · targetSdk 34
```

选 8.2.1 是因为本机 Gradle 缓存与 AGP 插件已有，能**完全离线出包**（`--offline`），不需要装 Android Studio。

`resourceConfigurations += ["zh", "en"]` 把资源裁剪到中英两语，APK 体积省下近 1 MB。

### 签名策略

release 密钥**不进仓库**。`app/build.gradle` 在配置阶段读取 `keystore/keystore.properties`：

```groovy
def keystorePropsFile = rootProject.file('keystore/keystore.properties')
def hasReleaseKeystore = keystorePropsFile.exists() && /* .jks 也要存在 */
```

- 配置齐全 → 用 release 密钥签名（本仓库发布版走这条）
- 缺失 → `signingConfig` 回落 `signingConfigs.debug`，产物仍可安装

这样贡献者 clone 下来只填 `local.properties` 就能跑，不会因为"没有密钥"卡住。

### 出包命令

```bash
export JAVA_HOME=/path/to/jdk-17
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease --offline
```

## 4. 运行时结构

```
MainActivity (ComponentActivity, singleTop)
└── setContent { AiDockTheme { AiDockApp() } }
    ├── ModalNavigationDrawer          ← 左侧抽屉
    │   └── 抽屉内容：按 Region 分组的 12 项
    └── Scaffold
        ├── CenterAlignedTopAppBar     ← LOGO + 名称/域名 + 导出/刷新/更多
        ├── WebViewHost(AndroidView)   ← 唯一 WebView 实例
        ├── 加载遮罩 / 错误页 / 进度条
        └── SettingsScreen（全屏覆盖，用状态切换而非新 Activity）
```

关键点：**设置页不是独立 Activity**。用 `if (showSettings) SettingsScreen() else 主界面` 切换，好处是 WebView 实例不被销毁——从设置页返回时网页还停在原处，代价是设置页自己要用 `BackHandler` 兜住返回。

## 5. 模块详解

### 5.1 `AssistantCatalog.kt` — 助手元数据表

```kotlin
data class Assistant(
    val id: String,          // 稳定标识：DataStore key + 状态快照 Map 的 key
    val name: String,
    val shortName: String,
    val url: String,
    val domain: String,      // 抽屉副标题，也让用户知道正在加载哪个站点
    val color: Color,        // 壳层主题种子色
    @DrawableRes val logoRes: Int,
    val region: Region = Region.DOMESTIC,
    val mono: Boolean = false,
)
```

两个容易忽略的字段：

- `id` 一旦改动，用户之前保存的"上次选中的助手"和 WebView 快照就全丢了，**只能加不能改**。
- `mono`：OpenAI 的六瓣结是纯黑单色标记，深色主题下直接糊在深色底上。标了 `mono = true` 后，渲染时不给它原图，而是用 `onSurface` 动态着色（浅色主题近黑、深色主题近白）。

主色不是拍脑袋定的，是用 `tools/ColorProbe.java` 采样官方 LOGO 像素算出来的——这一步骤曾发现两个品牌色写错（元宝应是绿不是蓝，智谱应是蓝不是青）。

### 5.2 `Theme.kt` — 从一个种子色派生整套 M3 配色

不用 `dynamicColorScheme()`（那是取系统壁纸色），而是手写 `brandScheme(seed, dark)`：以品牌色为 `primary`，用 `lerp` 往白/黑方向派生 `primaryContainer`、`surface`、`outline` 等，深色模式单独一条分支。

这样做的好处：切到豆包，整界面变成豆蓝色系；切到 Claude，变成珊瑚色系。用户的感受是"换了个主题"，而不是"换了个 App"——`surface` 只掺 2.5%~6% 的种子色，底色依旧是中性白/黑，不会变成廉价的大色块。

`onBrand(color)` 用相对亮度决定主色上的文字用黑还是白，避免豆绿 `#02C160` 上放白字读不清。

> 踩坑：`animateColorAsState` 的 import 必须来自 `androidx.compose.animation`，写 `animation.core` 会编译报"找不到符号"。

### 5.3 `WebViewHolder.kt` — 单实例 + 每站点快照

核心数据结构：

```kotlin
private val savedStates = HashMap<String, Bundle>()   // id -> WebView.saveState()
private val lastUrls   = HashMap<String, String>()    // id -> 离开时的 URL
var currentId: String
```

**切换流程**（`switchTo`）：

1. `persistState(currentId)` —— 把当前页 `saveState()` 存进 Map，`CookieManager.flush()` 落盘
2. 取目标站点的 `Bundle`，`restoreState(bundle)`
3. `restoreState` 返回 `null` 说明快照无效（页面已不存在/进程被回收），回落到 `lastUrls[id] ?: url`
4. **立刻盖上加载遮罩**（`loadingVeil = true`）

第 4 步是实测补的：网络挂起时会出现"标题已经是 Claude、正文还是豆包"的错位，因为 `loadUrl` 是异步的，旧页面在新文档开始绘制前一直可见。遮罩在 `onPageCommitVisible`（新文档首个像素可绘制）时才揭，比 `onPageFinished` 早得多，体感更快。

**状态字段**都用 `mutableStateOf` 暴露给 Compose：`progress` / `isLoading` / `errorText` / `canGoBack` / `loadingVeil`。

**UA 处理**：

```kotlin
WebSettings.getDefaultUserAgent(context)
    .replace("; wv", "")
    .replace("Version/4.0 ", "")
```

Android WebView 默认 UA 带 `; wv` 与 `Version/4.0`，ChatGPT / Claude / Gemini 会据此判定"内嵌浏览器"并拒绝登录。抹掉这两个标记后能正常进站（Google 账号登录仍会被 Google 侧拦截，见 §9.6）。

**WebView 安全配置**：

```kotlin
javaScriptEnabled = true
domStorageEnabled = true
allowFileAccess = false                                  // 不读本地文件
mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE       // 兼容站点里少量 http 资源
setSupportMultipleWindows(false)                          // 新窗口在当前视图打开
mediaPlaybackRequiresUserGesture = false
```

**错误翻译**（`describeError`）：把 `WebViewClient` 的错误码翻成人话——

| 错误码 | 提示 |
| :-- | :-- |
| `ERROR_HOST_LOOKUP` / `ERROR_NAME_NOT_RESOLVED` | 找不到这个域名，检查网络或代理 |
| `ERROR_CONNECT` | 连不上服务器 |
| `ERROR_TIMEOUT` | 连接超时 |
| `ERROR_FAILED_SSL_HANDSHAKE` / `ERROR_SSL_*` | 证书校验失败 |
| 其它 | 原始 description 直接透出 + 错误码 |

同时只处理 `isForMainFrame == true`，子资源（图片、字体）加载失败不打断阅读。

### 5.4 `AccountVault.kt` — 账号保险箱

文件：`filesDir/vault.json`（`accounts` 数组 + `pin` 对象）。

**密码加密**

```kotlin
// 硬件密钥：AndroidKeyStore 生成 AES-256，不可导出
Cipher.getInstance("AES/GCM/NoPadding")
c.init(Cipher.ENCRYPT_MODE, key)   // 不传 IV，由 Keystore 生成
val iv = c.iv                      // 之后从 cipher 取出来存进密文头
val ct = c.doFinal(plain.toByteArray())
"v1:" + base64(iv) + ":" + base64(ct)
```

> **这是本项目最大的一个坑。** Keystore 生成的 AES 密钥默认 `setRandomizedEncryptionRequired(true)`，即**不允许调用方自带 IV**。第一版自己生成 12 字节 IV 传 `GCMParameterSpec`，保存账号时直接抛 `InvalidAlgorithmParameterException: Caller-provided IV not permitted`，App 当场崩溃。正确做法如上：加密方向让 Keystore 生成 IV 再回读，解密方向照常传回 IV（解密不受此限制）。
>
> 降级密钥是普通 `SecretKeySpec`，没有该限制，需自己生成 IV——所以 `encrypt()` 里要按 `hardware` 分支处理。

Keystore 不可用（极少数 ROM/虚拟设备）时退到 `filesDir/.vault.key` 的 32 字节随机密钥，`VaultSnapshot.hardwareBacked` 会标为 `false`，界面提示"非硬件保护"。

**二次验证**

- 验证码**不存明文**，只存 `hashPin(pin, salt)`：4096 轮加盐 SHA-256
- 4 位 / 6 位可切换，切换时保留原 salt、重算 hash
- 失败计数 `failCount` 与 `lockedUntil` 落盘，**重启 App 不会重置**
- 连错 5 次锁 30 秒（`PIN_MAX_FAILS` / `PIN_LOCK_MILLIS`）
- 开关关闭状态下调用 `verifyPin` 直接返回 `Ok`，但**关闭开关本身要先验证**（否则任何人拿到手机就能关掉保护）

> 踩坑：`verifyPin` 返回的 `PinPolicy` 第一版只写盘没回写内存快照，导致锁定期间重新打开弹窗仍可输入——因为界面读的是内存里的 `snap.pin`。现在所有会改 `pin` 的操作都返回新快照并回灌。

### 5.5 `ConversationExporter.kt` — 注入 JS 抽取 DOM

**为什么注入 JS**：DOM 在 WebView 里，Kotlin 侧拿不到；`evaluateJavascript` 能在页面上下文里跑代码，还能直接把结果作为 JSON 字符串回传。9 秒超时兜底（`withTimeoutOrNull`）。

**返回值的两层引号**：`evaluateJavascript` 的结果本身是 JSON 编码的字符串，需要先 `JSONTokener(raw).nextValue()` 解一层才能拿到对象。

**三层降级**

```
ADAPTERS（域名正则 → 选择器 + 角色判定 + 正文节点）   ← 命中就用
  ↓ 未命中 / 抽不到
GENERIC（找子节点最多的容器，按属性/对齐/头像交替判角色）
  ↓ 仍失败
PAGE   （整页可见文本作为单条导出）
```

已适配 10 个站点：

| 域名 | 选择器锚点 |
| :-- | :-- |
| `chatgpt.com` / `chat.openai.com` | `[data-message-author-role]`，正文取 `.markdown` |
| `gemini.google.com` | `user-query` / `model-response` 自定义元素 |
| `claude.ai` | `[data-testid="user-message"]` / `assistant-message` / `.font-claude-message` |
| `copilot.microsoft.com` | `[data-content="user-message"]` / `[data-content="ai-message"]` |
| `chat.deepseek.com` | `.ds-message` + 两个哈希类名 `.fbb737a4`(用户) / `.aaff8b8f`(AI) |
| `kimi.com` / `moonshot.cn` | `.segment-content` / `.chat-content-item`，角色向父节点取 `user/self` vs `assistant/robot` |
| `doubao.com` | `[data-testid="send_message"]` / `receive_message` / `.message-content` |
| `tongyi.aliyun.com` | `[class*="questionItem"]` / `[class*="answerItem"]` / `.bubble` |
| `yuanbao.tencent.com` | `[class*="agent-chat__bubble"]` / `.hyc-content-md` / `.hyc-common-markdown` |

**HTML → Markdown 转换器**（纯手写，无库）：递归遍历节点，处理标题、段落、`ul/ol`（含嵌套缩进）、`blockquote`、`pre>code`（带语言标记，从 `class="language-xxx"` 提取）、表格（转 GFM 表格）、`strong/em/code/a/img` 行内元素、`hr`。

**代码块缩进的坑**（两次踩）：

1. 通用清洗函数 `norm()` 会把连续空格压成一个，直接用在代码上是灾难——`    if lo >= hi:` 变成 `if lo >= hi:`，导出的 Python 不可用。修法：单独写 `codeNorm()`，**只去行尾空白、不动行首缩进**。
2. 修完 `codeNorm` 还是塌，因为最后清洗消息时又调了一次 `norm()`。现在消息级清洗用 `finalNorm()`：只收尾去空行，绝不碰行首。

**角色判定优先级**：显式属性（`data-message-author-role`、`data-content`）→ 头像 `alt/src` 关键词 → 左右对齐 + 气泡位置，都没有才退回"消息 N"。

**虚拟滚动的物理限制**：这些站点只渲染可视区附近的消息，滚动过远的早期内容根本不在 DOM 里，**任何 DOM 层面的导出都拿不到**。所以导出预览必须显示"识别到 N 条"，让用户能判断是否被截断——不支持的功能就说清楚，不假装成功。

单次上限 300 条（`R.messages.slice(-300)`），防超长页面把 JSON 撑爆。

### 5.6 `ExportSaver.kt` — 落盘 / 分享 / 复制

| 场景 | 实现 |
| :-- | :-- |
| Android 10+ 存到下载目录 | `MediaStore.Downloads` + `RELATIVE_PATH = Download/AI dock` + `IS_PENDING` 两阶段写入 |
| Android 7~9 | 直接写 `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/AI dock`，需要 `WRITE_EXTERNAL_STORAGE`（manifest 里 `maxSdkVersion=28`） |
| 分享 | 写 `cacheDir/exports/`，`FileProvider` 出 `content://`，`ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` |
| 复制 | `ClipboardManager`，**不**用 `ClipDescription.EXTRA_IS_SENSITIVE`（对话内容不是密码，标了反而让部分系统禁止粘贴） |

文件名：`AI-dock_<站点id>_<yyyyMMdd-HHmm>.md`，同分钟重复导出会撞名，MediaStore 会自动加 `(1)` 后缀。

### 5.7 设置页与数字键盘

`PinDialogs.kt` 完全自绘，**不调系统键盘**：

- 不用 `TextField`，所以不会触发输入法联想、不会被第三方输入法记录，也不会因 `adjustResize` 把对话框顶飞
- 4 位 / 6 位圆点指示器，输错时圆点描红并且**清空重来**（不是保留已输的数字）
- 锁定状态：显示"错误次数过多，已锁定 N 秒"，键盘整体 `enabled = false` 并置灰，倒计时归零自动恢复
- 对话框在内容变化时高度会跳（4 位转 6 位多一排圆点），用固定高度容器避免抖动

## 6. 数据格式

### `vault.json`

```json
{
  "accounts": [
    {
      "id": "acc_m3x8k1_4f2a9c7b",
      "assistantId": "qwen",
      "site": "通义千问",
      "user": "me@example.com",
      "pwd": "v1:<base64(iv)>:<base64(ciphertext)>",
      "note": "工作号",
      "at": 1763348192000
    }
  ],
  "pin": {
    "enabled": true,
    "length": 4,
    "hash": "<base64(sha256 x4096)>",
    "salt": "<base64(16B)>",
    "fail": 0,
    "lockedUntil": 0
  }
}
```

`pwd` 为空串表示这条记录没存密码。解密失败（换机恢复备份、Keystore 失效）时 `VaultAccount.broken = true`，界面提示"密码需重填"而不是显示乱码。

### 导出的 Markdown

```markdown
# 通义千问 · 对话记录

- 导出时间：2026-11-17 09:36
- 来源：https://tongyi.aliyun.com/qianwen/
- 页面标题：通义千问
- 消息数：2 · 约 186 字
- 说明：站点适配器（tongyi.aliyun.com）
- 工具：AI dock 1.2.0（第三方站点网页内容的本地摘录）

---

## 我

用 Python 写一个快排，要求原地排序

## 通义千问

```python
def qsort(a, lo, hi):
    if lo >= hi:
        return
    i, j = lo, hi
    pivot = a[(lo + hi) // 2]
    ...
```

---

> 由 AI dock 从页面内容摘录，可能与原始对话排版略有差异；网页端的虚拟滚动会导致超出可视区的历史消息不被导出。
```

## 7. 安全与隐私边界

**做到什么**

- 密码在磁盘上始终是 AES-256-GCM 密文，密钥不可导出
- 验证码只存摘要，无法反推
- 导出文件只写本地，不经任何服务器
- 无第三方 SDK、无埋点、无崩溃上报

**没做到什么（明确写出，不含糊）**

- 二次验证是**界面闸门**，不是对 `vault.json` 的第二层加密。能调用 Keystore 密钥的程序就能解密
- 已 root 的设备上，Keystore 的保护强度取决于 TEE/StrongBox 是否完好
- 网页里用户自己粘贴的内容不受壳保护（那是站点的责任范围）
- 导出时读取的是**已渲染的 DOM**，如果站点在页面里插入了不可见元素，理论上也会被带出——导出预览让用户在保存前看一眼

## 8. 测试与验证

没有单元测试框架（项目规模不值当），用三层验证：

1. **静态**：`aapt2 dump badging` 确认版本号/权限/launchable；`apksigner verify --print-certs` 确认签名指纹前后一致（覆盖升级才不会失败）
2. **真实 DOM 回归**：`tools/extract_test.py` 用 mock 页面（ChatGPT 结构、Gemini 结构、通用结构）在 chrome-headless 里跑注入脚本，断言消息数、角色、代码块缩进、表格、嵌套列表
3. **真机全链路**：`adb` 驱动点击/输入，逐屏 `screencap` 后人工核对；`logcat -b crash` 归零才算过

真机上实际跑过的路径：冷启动恢复上次站点 → 抽屉 12 项分组 → 12 个站点逐个切换（含失败态）→ 网页内多级跳转与返回键三级 → 新建/编辑/删除账号 → 显示密码（4 位与 6 位各 1 次）→ 连错 5 次锁定与自动解锁 → 关闭二次验证 → 导出并检查落盘文件内容 → 杀进程重启验证持久化。

## 9. 踩坑记录

按"当时怎么发现、根因是什么、怎么修的"记，多数是实测撞出来的。

### 9.1 `painterResource` 不能指向 mipmap 里的 launcher

`IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`，首启必崩。

根因：`R.mipmap.ic_launcher` 在 API 26+ 命中的是 `mipmap-anydpi-v26/ic_launcher.xml`（`<adaptive-icon>`），Compose 的 `painterResource` 加载不了这种 XML。修法：把一个纯 PNG 副本放到 `drawable-*/app_logo.png`，Compose 只引它。

### 9.2 Keystore 不允许自带 IV

见 §5.4。表现是保存第一条账号就崩。

### 9.3 锁定状态没回写内存

见 §5.4。表现是锁定期间重开弹窗还能输入，等于锁了个寂寞。

### 9.4 代码块缩进被压平

见 §5.5。表现是导出的 Python 无法运行。

### 9.5 切换站点时正文与标题错位

见 §5.3。加了加载遮罩 + 8 秒慢网提示。

### 9.6 国外站点的两个天花板

- **UA 判定**：抹掉 `; wv` / `Version/4.0` 可解，已在 §5.3 处理
- **Google 账号登录**：Google 依据 OAuth 策略拒绝 WebView 内的登录请求，**任何 WebView 壳都解不了**（除非自己实现 OAuth + 注入 cookie，那是另一种性质和风险）。建议用邮箱密码登录 Gemini

### 9.7 Gradle mergeDex 偶发失败

`app/build/intermediates/dex` 目录被上一轮进程占着时，`assembleRelease` 会在 `mergeDexRelease` 阶段失败。处理：`./gradlew --stop` 后删 `app/build/intermediates/dex`、`merged_dex` 再编。

### 9.8 Windows 上 javac 的中文坑

`javac` 默认按系统 GBK 读源文件，源码里有中文注释就报"编码 GBK 的不可映射字符"。必须显式 `-encoding UTF-8`。

### 9.9 小米设备 `INSTALL_FAILED_USER_RESTRICTED`

`adb install` 报这个错且手机上没弹授权框，是 MIUI 的私有开关：

```bash
adb shell settings put global adb_install_need_confirm 0
```

### 9.10 Git Bash 会改写 adb 的路径参数

`adb install D:/xx.apk` 里的路径会被 MSYS 转成 `/d/xx.apk`，adb 报 `failed to stat`。解决：`export MSYS_NO_PATHCONV=1`，并且**同时**把传给 adb 的 Windows 路径写成反斜杠形式 `D:\workbuddy\...`。

## 10. 扩展指南

### 加一个助手

1. 取官方 LOGO，按 `tools/LogoMaker.java` 的规格生成 5 档密度 PNG（宽 192/144/96/72/48），放进 `res/drawable-{xxxhdpi,xxhdpi,xhdpi,hdpi,mdpi}/logo_<id>.png`
2. 在 `AssistantCatalog.all` 里加一条 `Assistant(...)`，`region` 选 `DOMESTIC`/`FOREIGN`，纯黑单色标记记得 `mono = true`
3. 完事。抽屉、顶栏、主题派生、状态快照全部自动生效

### 给新站点加导出适配器

在 `ConversationExporter` 的 `ADAPTERS` 里加一条：

```js
{ re: /(^|\.)example\.com$/, sel: "[data-role]", role: function (el) {
    return el.getAttribute("data-role") === "user" ? "user" : "ai"; },
  body: function (el) { return el.querySelector(".markdown") || el; } }
```

然后在 `tools/extract_test.py` 里补一个 mock 页面做回归。**不加适配器也能用**——通用启发式会兜住，只是准确率低一些。

## 11. 技术债与路线图

| 项 | 说明 |
| :-- | :-- |
| 导出未做多轮分页抓取 | 虚拟滚动导致长对话导不全，理论上可以程序化滚动逐屏累积，但要处理懒加载与去重，收益/风险比一般 |
| 无自动填充 | 需要按站点写注入脚本，登录框结构各版本会变，维护成本高于收益 |
| 主题色硬编码 8 个种子 | 可以从 LOGO 自动采样生成，但遇到深色标（Kimi）会偏色，目前手动维护更稳 |
| 未做深色模式手动开关 | 现在跟系统走；App 内切换会导致 `uiMode` 重建，回到首页 |
| 横屏未专门优化 | 网页会重排，壳层没做双栏 |
| 无 i18n | 界面硬编码中文；`resourceConfigurations` 已留出 en 的口子 |
