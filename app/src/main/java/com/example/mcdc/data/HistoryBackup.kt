package com.example.mcdc.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * 历史记录的「覆盖安装 / 重装安全」备份。
 *
 * 根因：历史原本只存在应用私有目录 `context.filesDir`，而该目录会在以下情况被清空：
 *  - 卸载应用；
 *  - 覆盖安装时若新 APK 与已装版本签名不一致，系统会强制「卸载再安装」，同样清空。
 * 因此即便用户只是「装上新版」，旧记录也会消失。
 *
 * 方案：额外把历史写入「共享存储」里用户选定的文件夹（如 Documents/MCDC）。
 * 该位置不随应用卸载而删除；并通过 SAF 的 persistable URI 权限在重装后自动找回，
 * 重装后最多只需用户再点一次选择同一文件夹即可恢复。
 *
 * 设计取舍（与 HistoryStore 一致）：
 *  - 不引入任何序列化/存储库，直接用 DocumentFile + ContentResolver 读写 JSON 文本。
 *  - 所有 IO 在 [Dispatchers.IO] 上执行，失败静默忽略，绝不影响主存储与界面。
 */
class HistoryBackup(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("mcdc_backup", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_TREE_URI = "tree_uri"
        private const val FOLDER_NAME = "MCDC"
        private const val FILE_NAME = "mcdc_history.json"
    }

    /** 是否已配置可用（且可访问）的备份文件夹。 */
    fun hasBackupFolder(): Boolean = resolveTreeUri() != null

    /**
     * 解析可用的备份根 Uri：
     *  1) 系统仍保留的 persistable 授权（重装/重启后可能有效，优先）；
     *  2) 本地缓存的 tree Uri（同一次安装内有效）。
     * 两者都需通过 DocumentFile 实际可访问性校验，避免缓存了失效 Uri。
     */
    private fun resolveTreeUri(): Uri? {
        val persisted = appContext.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission }
            ?.uri
        if (persisted != null && isWritableTree(persisted)) return persisted

        val cached = prefs.getString(PREF_TREE_URI, null)?.let(Uri::parse)
        if (cached != null && isWritableTree(cached)) return cached

        return null
    }

    /** 该 tree Uri 是否仍可被本应用访问（用于校验授权是否失效）。 */
    private fun isWritableTree(uri: Uri): Boolean {
        val tree = DocumentFile.fromTreeUri(appContext, uri) ?: return false
        return tree.exists() && tree.canRead()
    }

    /** 发起「选择备份文件夹」系统选择器（仅首次 / 授权丢失时调用一次）。 */
    fun launchPicker(launcher: ActivityResultLauncher<Uri?>) {
        launcher.launch(null)
    }

    /** 处理选择器返回：持久化授权并缓存 Uri。返回是否成功获得可写授权。 */
    fun onPickerResult(uri: Uri?): Boolean {
        if (uri == null) return false
        val takeFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, takeFlags)
        }.onFailure { return false }
        prefs.edit().putString(PREF_TREE_URI, uri.toString()).apply()
        return isWritableTree(uri)
    }

    /** 在备份文件夹内定位（必要时创建）历史 JSON 文件。 */
    private fun backupFile(): DocumentFile? {
        val treeUri = resolveTreeUri() ?: return null
        val tree = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        val folder = tree.findFile(FOLDER_NAME) ?: tree.createDirectory(FOLDER_NAME) ?: return null
        return folder.findFile(FILE_NAME) ?: folder.createFile("application/json", FILE_NAME)
            ?: return null
    }

    /** 写入备份文本。失败静默忽略（不影响主存储）。 */
    suspend fun write(text: String) = withContext(Dispatchers.IO) {
        runCatching {
            val file = backupFile() ?: return@withContext
            appContext.contentResolver.openOutputStream(file.uri, "wt")?.use { os ->
                os.write(text.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    /** 读取备份文本；不存在 / 无权限 / 空文件时返回 null。 */
    suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = resolveTreeUri() ?: return@withContext null
            val tree = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext null
            val folder = tree.findFile(FOLDER_NAME) ?: return@withContext null
            val file = folder.findFile(FILE_NAME) ?: return@withContext null
            if (!file.exists() || file.length() == 0L) return@withContext null
            appContext.contentResolver.openInputStream(file.uri)?.use { ins ->
                ins.bufferedReader(StandardCharsets.UTF_8).readText()
            }
        }.getOrNull()
    }
}
