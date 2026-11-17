package com.aidock.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 品牌色上的前景色：亮底用近黑，暗底用白。 */
fun onBrand(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF1A1A1E) else Color.White

/** 暗色模式下把品牌色提亮，避免纯黑品牌色（如 Kimi）在深色背景里消失。 */
@Composable
fun readableBrand(color: Color): Color =
    if (isSystemInDarkTheme()) lerp(color, Color.White, 0.42f) else color

/**
 * 由品牌色派生整套 M3 配色。
 *
 * 关键取舍：只让 primary / container / outline 跟随品牌色，
 * surface 保持接近中性的白（或近黑），只掺入 2%~6% 品牌色。
 * 这样切换助手时壳层有明确"换肤"感知，又不会整屏变蓝变紫导致割裂。
 */
fun brandScheme(seed: Color, dark: Boolean): ColorScheme {
    return if (!dark) {
        val surface = lerp(Color(0xFFFDFDFF), seed, 0.025f)
        lightColorScheme(
            primary = seed,
            onPrimary = onBrand(seed),
            primaryContainer = lerp(seed, Color.White, 0.86f),
            onPrimaryContainer = lerp(seed, Color(0xFF15161A), 0.30f),
            secondary = lerp(seed, Color(0xFF15161A), 0.18f),
            onSecondary = Color.White,
            secondaryContainer = lerp(seed, Color.White, 0.84f),
            onSecondaryContainer = lerp(seed, Color(0xFF15161A), 0.35f),
            tertiary = lerp(seed, Color(0xFF7A5AF8), 0.35f),
            onTertiary = Color.White,
            background = surface,
            onBackground = Color(0xFF1B1C20),
            surface = surface,
            onSurface = Color(0xFF1B1C20),
            surfaceVariant = lerp(Color(0xFFF2F3F7), seed, 0.05f),
            onSurfaceVariant = Color(0xFF5C6069),
            surfaceTint = seed,
            outline = lerp(Color(0xFF8E929B), seed, 0.12f),
            outlineVariant = Color(0xFFE3E5EB),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            scrim = Color.Black,
            inverseSurface = Color(0xFF303136),
            inverseOnSurface = Color(0xFFF3F3F7),
            inversePrimary = lerp(seed, Color.White, 0.55f)
        )
    } else {
        val surface = lerp(Color(0xFF121317), seed, 0.06f)
        darkColorScheme(
            primary = lerp(seed, Color.White, 0.34f),
            onPrimary = Color(0xFF101116),
            primaryContainer = lerp(seed, Color(0xFF121317), 0.45f),
            onPrimaryContainer = lerp(seed, Color.White, 0.74f),
            secondary = lerp(seed, Color.White, 0.42f),
            onSecondary = Color(0xFF101116),
            secondaryContainer = lerp(seed, Color(0xFF121317), 0.55f),
            onSecondaryContainer = lerp(seed, Color.White, 0.70f),
            tertiary = lerp(seed, Color(0xFFB9A8FF), 0.45f),
            onTertiary = Color(0xFF101116),
            background = surface,
            onBackground = Color(0xFFE6E6EA),
            surface = surface,
            onSurface = Color(0xFFE6E6EA),
            surfaceVariant = lerp(Color(0xFF23252B), seed, 0.07f),
            onSurfaceVariant = Color(0xFFB4B7BF),
            surfaceTint = seed,
            outline = Color(0xFF8A8D96),
            outlineVariant = Color(0xFF3A3D45),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
            scrim = Color.Black,
            inverseSurface = Color(0xFFE6E6EA),
            inverseOnSurface = Color(0xFF303136),
            inversePrimary = seed
        )
    }
}

/** 偏 Gmail 的排版：标题克制、正文 15sp、辅助信息 12sp。 */
private val AiDockTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun AiDockTheme(
    seed: Color,
    dark: Boolean,
    content: @Composable () -> Unit
) {
    // 品牌色做补间，切换助手时不会"啪"地跳色
    val animatedSeed by animateColorAsState(
        targetValue = seed,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "brand-seed"
    )
    val scheme = remember(animatedSeed, dark) { brandScheme(animatedSeed, dark) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AiDockTypography,
        content = content
    )
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
