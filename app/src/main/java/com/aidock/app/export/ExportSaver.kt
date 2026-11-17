package com.aidock.app.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SaveResult {
    /** 已写入系统下载目录 */
    data class Saved(val displayPath: String, val uri: Uri) : SaveResult

    /** Android 9 及以下需要写外部存储权限 */
    data object NeedPermission : SaveResult

    data class Failed(val message: String) : SaveResult
}

/**
 * Markdown 落盘与分享。
 *
 * Android 10+ 走 MediaStore（免权限，落到 下载/AI dock/）；
 * Android 9 及以下走公共下载目录 + MediaScanner，需要 WRITE_EXTERNAL_STORAGE。
 * 分享一律经由 FileProvider 输出 cacheDir 副本，避免暴露内部路径。
 */
object ExportSaver {

    private const val SUB_DIR = "AI dock"
    private const val MIME = "text/markdown"

    fun hasLegacyWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    suspend fun saveToDownloads(
        context: Context,
        fileName: String,
        content: String
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, content)
            } else {
                if (!hasLegacyWritePermission(context)) return@withContext SaveResult.NeedPermission
                saveLegacy(context, fileName, content)
            }
        } catch (t: Throwable) {
            SaveResult.Failed(t.message ?: "写入失败")
        }
    }

    private fun saveViaMediaStore(context: Context, name: String, content: String): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return SaveResult.Failed("系统没有返回可写入的位置")

        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: run {
            resolver.delete(uri, null, null)
            return SaveResult.Failed("无法打开输出流")
        }

        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)

        return SaveResult.Saved("下载/$SUB_DIR/$name", uri)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, name: String, content: String): SaveResult {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return SaveResult.Failed("无法创建下载目录")
        val file = File(dir, name)
        file.writeText(content)
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(MIME), null
        )
        return SaveResult.Saved(file.absolutePath, Uri.fromFile(file))
    }

    /** 生成一份 cacheDir 副本用于分享 / 外部打开 */
    fun stageForShare(context: Context, name: String, content: String): Uri? = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(content)
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    } catch (_: Throwable) {
        null
    }

    fun share(context: Context, uri: Uri, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "分享对话记录").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    fun openWith(context: Context, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(view) }
            .onFailure { share(context, uri, "对话记录") }
    }

    /** 一键复制到剪贴板（不需要任何权限） */
    fun copyToClipboard(context: Context, label: String, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        true
    } catch (_: Throwable) {
        false
    }
}
