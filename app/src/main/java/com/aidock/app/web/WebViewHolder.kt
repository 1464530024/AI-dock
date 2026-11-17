package com.aidock.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidock.app.Assistant

/**
 * 单个 WebView 实例的持有者 + 状态机。
 *
 * 设计要点：
 * 1. 只用一个 WebView，切换助手时 saveState → 下次 restoreState。
 *    比"每个助手一个 WebView"省内存（8 个常驻 WebView 在中低端机上极易被系统杀）。
 * 2. Cookie 是全局共享的（CookieManager 本身按域存储），所以同一站点登录态天然保留；
 *    saveState/restoreState 负责保留该站点内部的路由栈与表单数据。
 * 3. 所有 UI 状态都是 Compose 的 mutableStateOf，供上层直接读取触发重组。
 */
class WebViewHolder(private val context: Context) {

    /** 0..100 加载进度 */
    var progress by mutableStateOf(0)
        private set

    /** 是否正在加载（控制细进度条显隐） */
    var isLoading by mutableStateOf(false)
        private set

    /** 非空即展示错误页 */
    var errorText by mutableStateOf<String?>(null)
        private set

    /** WebView 是否还能后退（用于 BackHandler 的 enabled） */
    var canGoBack by mutableStateOf(false)
        private set

    /**
     * 是否盖一层加载遮罩。
     *
     * 换站点时 WebView 会一直显示上一页的像素，直到新文档首次绘制。
     * 网络挂起时这会变成「标题已经是 ChatGPT、内容还是上一个站」的诡异状态，
     * 所以换站点时立刻盖遮罩，等 onPageCommitVisible / onPageFinished 再揭开。
     */
    var loadingVeil by mutableStateOf(false)
        private set

    private val savedStates = HashMap<String, Bundle>()
    private val lastUrls = HashMap<String, String>()
    private var currentId: String = ""

    /**
     * 默认 UA 去掉 WebView 指纹。
     *
     * 安卓 WebView 的默认 UA 里带 `; wv` 与 `Version/4.0`，ChatGPT / Claude / Gemini
     * 这类站点会据此判定"内嵌浏览器"并弹出"请用 Chrome 打开"或直接拦截登录。
     * 抹掉这两个标记后页面按普通 Chrome 处理，兼容性明显更好。
     */
    private val defaultUserAgent: String = WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace("Version/4.0 ", "")

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    val webView: WebView = WebView(context)

    init {
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = defaultUserAgent
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppWebChromeClient()
        webView.setDownloadListener { url, _, _, _, _ -> openExternal(url) }
    }

    // ---------------------------------------------------------------- 切换

    /**
     * 切换到目标助手。已在该助手时若 forceReload=false 则直接返回。
     *
     * @param forceReload 用于"切换桌面版/移动版 UA"场景：丢弃存档并重新加载
     */
    fun switchTo(target: Assistant, desktopMode: Boolean, forceReload: Boolean = false) {
        if (target.id == currentId && !forceReload) return

        // 1) 先把当前站点存起来
        if (currentId.isNotEmpty() && currentId != target.id) {
            persistState(currentId)
        }

        currentId = target.id
        errorText = null
        progress = 0
        webView.settings.userAgentString =
            if (desktopMode) desktopUserAgent else defaultUserAgent

        if (forceReload) savedStates.remove(target.id)

        // 2) 有存档则恢复，否则加载首页
        val bundle = savedStates[target.id]
        val restored = if (bundle != null) webView.restoreState(bundle) != null else false
        if (!restored) {
            loadingVeil = true
            webView.loadUrl(lastUrls[target.id] ?: target.url)
        }
    }

    /** 把当前站点状态写入内存 Map（切换前 / 退到后台时调用） */
    fun persistState(id: String = currentId) {
        if (id.isEmpty()) return
        val bundle = Bundle()
        webView.saveState(bundle)
        savedStates[id] = bundle
        webView.url?.let { if (it.startsWith("http")) lastUrls[id] = it }
    }

    // ---------------------------------------------------------------- 操作

    fun reload() {
        errorText = null
        webView.reload()
    }

    fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
            webView.post { canGoBack = webView.canGoBack() }
        }
    }

    fun retry(fallbackUrl: String) {
        errorText = null
        progress = 0
        loadingVeil = true
        val current = webView.url
        if (current != null) webView.reload() else webView.loadUrl(fallbackUrl)
    }

    fun openExternal(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // 设备上没有可处理该 scheme 的应用，忽略
        }
    }

    /** 清除所有站点的 Cookie / 缓存 / 存档，全部 AI 需重新登录 */
    fun clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        savedStates.clear()
        lastUrls.clear()
        errorText = null
        loadingVeil = true
        webView.reload()
    }

    fun onAppPause() {
        persistState()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    fun onAppResume() {
        webView.onResume()
    }

    fun destroy() {
        try {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 内部

    @SuppressLint("SetJavaScriptEnabled")
    private inner class AppWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            // http/https 自己处理，其余（微信/支付宝/应用商店等）交给系统
            return if (url.startsWith("http://") || url.startsWith("https://")) {
                false
            } else {
                openExternal(url)
                true
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isLoading = true
            errorText = null
            canGoBack = view?.canGoBack() ?: false
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            // 新文档已经开始绘制，可以揭开遮罩了
            loadingVeil = false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            isLoading = false
            loadingVeil = false
            progress = 100
            canGoBack = view?.canGoBack() ?: false
            if (currentId.isNotEmpty() && url != null && url.startsWith("http")) {
                lastUrls[currentId] = url
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            // 只处理主文档失败，子资源失败不打断阅读
            if (request?.isForMainFrame == true) {
                isLoading = false
                loadingVeil = false
                errorText = describeError(error)
            }
        }
    }

    private inner class AppWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress = newProgress
            if (newProgress >= 100) isLoading = false
        }
    }

    private fun describeError(error: WebResourceError?): String {
        val code = error?.errorCode ?: -1
        val raw = error?.description?.toString()?.takeIf { it.isNotBlank() }
        val friendly = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP -> "无法解析服务器地址，请检查网络或代理"
            WebViewClient.ERROR_CONNECT -> "无法连接到服务器"
            WebViewClient.ERROR_TIMEOUT -> "连接超时，服务器无响应"
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "安全连接失败（SSL 握手错误）"
            WebViewClient.ERROR_BAD_URL, WebViewClient.ERROR_UNSUPPORTED_SCHEME -> "网址无法访问"
            WebViewClient.ERROR_IO -> "网络读写失败"
            else -> "页面加载失败"
        }
        return if (raw != null) "$friendly\n（$raw）" else friendly
    }
}
