package com.aidock.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidock.app.R

/**
 * 二次验证用的数字键盘。
 *
 * 用自绘键盘而不是输入框，是为了：不弹系统输入法（不会被第三方输入法学习）、
 * 不产生 AutoFill 记录、点按范围大、深浅色下都清晰。
 */

@Composable
private fun PinDots(count: Int, filled: Int, isError: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val dot = if (count > 4) 12.dp else 14.dp
    Row(horizontalArrangement = Arrangement.spacedBy(if (count > 4) 12.dp else 16.dp)) {
        repeat(count) { i ->
            val on = i < filled
            val bg by animateColorAsState(
                targetValue = if (on) scheme.primary else Color.Transparent,
                label = "pin-dot"
            )
            Box(
                modifier = Modifier
                    .size(dot)
                    .clip(CircleShape)
                    .background(bg)
                    .border(
                        width = 1.5.dp,
                        color = when {
                            isError -> scheme.error
                            on -> scheme.primary
                            else -> scheme.outline.copy(alpha = 0.55f)
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(
                if (label.isEmpty()) Color.Transparent
                else scheme.surfaceVariant.copy(alpha = if (enabled) 0.55f else 0.2f)
            )
            .clickable(enabled = enabled && label.isNotEmpty(), onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (label) {
            "" -> Unit
            "⌫" -> Icon(
                painter = painterResource(id = R.drawable.ic_backspace),
                contentDescription = "删除一位",
                tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp)
            )
            else -> Text(
                text = label,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
fun PinKeypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    KeyButton(key, enabled) {
                        when (key) {
                            "" -> Unit
                            "⌫" -> onBackspace()
                            else -> onDigit(key[0])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LengthChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) scheme.primary else Color.Transparent)
            .border(
                1.dp,
                if (selected) scheme.primary else scheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(percent = 50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
        )
    }
}

/** 校验码输入框（4 位 / 6 位），用于「显示密码」「关闭二次验证」等场景 */
@Composable
fun PinVerifyDialog(
    title: String,
    hint: String,
    length: Int,
    error: String?,
    lockedSeconds: Int,
    onDismiss: () -> Unit,
    onVerify: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var value by remember { mutableStateOf("") }
    val locked = lockedSeconds > 0
    val enabled = !locked

    LaunchedEffect(error) {
        if (error != null) value = ""
    }
    LaunchedEffect(value) {
        if (value.length == length && enabled) onVerify(value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (locked) "错误次数过多，请 ${lockedSeconds} 秒后再试" else hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (locked) scheme.error else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(6.dp))
                PinDots(count = length, filled = value.length, isError = error != null)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = error ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(18.dp)
                )
                Spacer(Modifier.height(6.dp))
                PinKeypad(
                    enabled = enabled,
                    onDigit = { if (value.length < length) value += it },
                    onBackspace = { if (value.isNotEmpty()) value = value.dropLast(1) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 设置 / 修改验证码：两步确认。
 *
 * @param initialLength 已有验证码时的位数；首次设置从 4 位起
 * @param allowLengthChange 是否显示 4/6 位切换（修改验证码时不允许改位数会让用户困惑，这里允许）
 */
@Composable
fun PinSetupDialog(
    title: String,
    initialLength: Int,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var length by remember { mutableIntStateOf(if (initialLength == 6) 6 else 4) }
    var step by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(value) {
        if (value.length != length) return@LaunchedEffect
        if (step == 0) {
            first = value
            value = ""
            error = null
            step = 1
        } else {
            if (value == first) {
                onDone(value)
            } else {
                error = "两次输入不一致，请重新设置"
                value = ""
                first = ""
                step = 0
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (step) {
                        0 -> "输入 $length 位数字作为验证码"
                        else -> "再输入一次以确认"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (step == 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        LengthChip(length == 4, "4 位数字") {
                            if (length != 4) { length = 4; value = "" }
                        }
                        LengthChip(length == 6, "6 位数字") {
                            if (length != 6) { length = 6; value = "" }
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                }

                PinDots(count = length, filled = value.length, isError = error != null)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = error ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(18.dp)
                )
                Spacer(Modifier.height(6.dp))
                PinKeypad(
                    enabled = true,
                    onDigit = { if (value.length < length) value += it },
                    onBackspace = { if (value.isNotEmpty()) value = value.dropLast(1) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 供设置页复用的小标题 */
@Composable
internal fun SettingsSectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
        Spacer(Modifier.width(2.dp))
    }
}
