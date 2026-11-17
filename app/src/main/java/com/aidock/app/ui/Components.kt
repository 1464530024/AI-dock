package com.aidock.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.MaterialTheme
import com.aidock.app.Assistant

/**
 * 助手头像。
 *
 * 单色标记（OpenAI 六瓣结）在深色主题下会整个融进深色底，
 * 这里用 ColorFilter 跟着 onSurface 重新着色，浅色近黑、深色近白。
 */
@Composable
fun AssistantLogo(
    assistant: Assistant,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(assistant.color.copy(alpha = if (dark) 0.20f else 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = assistant.logoRes),
            contentDescription = assistant.name,
            contentScale = ContentScale.Fit,
            colorFilter = if (assistant.mono) ColorFilter.tint(scheme.onSurface) else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}
