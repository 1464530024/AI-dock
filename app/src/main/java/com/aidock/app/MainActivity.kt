package com.aidock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.aidock.app.ui.AiDockRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：让 TopAppBar / 抽屉自己处理状态栏内边距，WebView 可以铺满
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AiDockRoot()
        }
    }
}
