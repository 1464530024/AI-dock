# 保留 WebView 相关 JS 接口（当前未使用 @JavascriptInterface，预留给后续扩展）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
