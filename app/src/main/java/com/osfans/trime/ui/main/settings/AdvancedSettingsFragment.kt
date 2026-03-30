package com.osfans.trime.ui.main.settings

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.osfans.trime.core.Rime
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.main.MainViewModel
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.ZipInputStream
import kotlin.getValue

class ProgressInputStream(
    inputStream: InputStream,
    private val onProgress: (Long) -> Unit
) : FilterInputStream(inputStream) {
    private var totalBytesRead = 0L

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val bytesRead = super.read(b, off, len)
        if (bytesRead != -1) {
            totalBytesRead += bytesRead
            onProgress(totalBytesRead)
        }
        return bytesRead
    }

    override fun read(): Int {
        val b = super.read()
        if (b != -1) {
            totalBytesRead += 1
            onProgress(totalBytesRead)
        }
        return b
    }
}

class AdvancedSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().advanced) {

    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode
    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon
    private val viewModel: MainViewModel by viewModels()

    // 1. 用于授权 rime 目录的 Launcher (OpenDocumentTree)
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { treeUri ->
            // 严格 SAF 要求：必须持久化权限，否则重启后该 Uri 将失效
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            
            // 建议：此处可以将 treeUri.toString() 存入 AppPrefs，方便后续自动读取
            Toast.makeText(requireContext(), "rime 目录授权成功", Toast.LENGTH_SHORT).show()
        }
    }

    // 2. 用于选择源文件的 Launcher (GetContent)
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sourceUri ->
            importFileToAuthorizedFolder(sourceUri)
        }
    }

    @Keep
    private val onUiModeChange = PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, v ->
        val mode = when (v) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    @Keep
    private val onShowAppIconChange = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        showAppIcon(requireContext(), v)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiMode.registerOnChangeListener(onUiModeChange)
        showAppIcon.registerOnChangeListener(onShowAppIconChange)
    }

    // 3. 动态注入 Preference 按钮
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        val context = requireContext()

        // 按钮 A：授权目录
        val authPref = Preference(context).apply {
            key = "saf_auth_folder"
            title = "授权 rime 文件夹"
            summary = "严格模式下必须先指定并授权 rime 存放目录"
            order = 998
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                // 检查是否已经存在授权
                if (context.contentResolver.persistedUriPermissions.isNotEmpty()) {
                    Toast.makeText(context, "已经授权过 rime 目录，无需重复操作", Toast.LENGTH_SHORT).show()
                } else {
                    folderPickerLauncher.launch(null)
                }
                true
            }
        }

        // 按钮 B：选择模型文件并导入到 rime 文件夹
        val importPref = Preference(context).apply {
            key = "saf_import_file"
            title = "导入 wanxiang 模型文件"
            summary = "手动选择模型文件并拷贝至 rime 文件夹"
            order = 999
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                importFileLauncher.launch("*/*")
                true
            }
        }

        // 按钮 C：升级 wanxiang 模型
        val upgradeWanXiangGramPref = Preference(context).apply {
            key = "upgrade_wanxiang_gram"
            title = "升级 wanxiang 模型"
            summary = "下载最新的 wanxiang 模型文件到 rime 文件夹，并部署"
            order = 1000
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                // 1. 获取之前授权过的 Rime 根目录 Uri
                val context = requireContext()
                val resolver = context.contentResolver
                val persistedUri = resolver.persistedUriPermissions.firstOrNull()?.uri

                if (persistedUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false
                val originalTitle = title.toString()
                val originalSummary = summary.toString()

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        performUpgradeGram(context, persistedUri, this@apply)

                        // 3. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalTitle（部署完成）"
                                Toast.makeText(context, "wanxiang 模型升级完成！", Toast.LENGTH_SHORT).show()
                                title = originalTitle
                                summary = originalSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalTitle（升级失败）"
                        summary = "错误: ${e.message}"
                        e.printStackTrace()
                    } finally {
                        delay(3000)
                        isEnabled = true
                    }
                }
                true
            }
        }

        // 按钮 D：升级 wanxiang 输入方案
        val upgradeWanXiangSchemaPref = Preference(context).apply {
            key = "upgrade_wanxiang_schema"
            title = "升级 wanxiang 输入方案"
            summary = "下载最新的 wanxiang 输入方案到 rime 文件夹，并部署"
            order = 1001
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                // 1. 获取之前授权过的 Rime 根目录 Uri
                val context = requireContext()
                val resolver = context.contentResolver
                val persistedUri = resolver.persistedUriPermissions.firstOrNull()?.uri

                if (persistedUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false
                val originalTitle = title.toString()
                val originalSummary = summary.toString()

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        performUpgradeSchema(context, persistedUri, this@apply)

                        // 3. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalTitle（部署完成）"
                                Toast.makeText(context, "wanxiang 输入方案升级完成！", Toast.LENGTH_SHORT).show()
                                title = originalTitle
                                summary = originalSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalTitle（升级失败）"
                        summary = "错误: ${e.message}"
                        e.printStackTrace()
                    } finally {
                        delay(3000)
                        isEnabled = true
                    }
                }
                true
            }
        }

        preferenceScreen.addPreference(authPref)
        preferenceScreen.addPreference(importPref)
        preferenceScreen.addPreference(upgradeWanXiangGramPref)
        preferenceScreen.addPreference(upgradeWanXiangSchemaPref)
    }

    @SuppressLint("DefaultLocale")
    private suspend fun performUpgradeGram(context: Context, rootUri: Uri, pref: Preference) = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val url = "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri) ?: throw Exception("无法解析 Rime 目录")

        val originalTitle = pref.title

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("网络请求失败: ${response.code}")

            val body = response.body ?: throw Exception("下载内容为空")
            val totalSize = body.contentLength()
            val inputStream = body.byteStream()

            // 包装流以监听下载进度
            val progressStream = ProgressInputStream(inputStream) { bytesRead ->
                // 计算 KB 和 MB
                val kb = bytesRead / 1024.0
                val mb = kb / 1024.0

                CoroutineScope(Dispatchers.Main).launch {
                    if (totalSize > 0) {
                        // 如果能获取总大小，显示百分比
                        val percent = (bytesRead * 100 / totalSize).toInt()
                        pref.title = "$originalTitle（下载中: $percent%）"
                    } else {
                        // 如果总大小未知（GitHub 常见情况），显示已下载大小
                        if (mb >= 1) {
                            // 超过 1MB 显示 MB，保留两位小数
                            pref.title = String.format("${originalTitle}（下载中: %.2f MB）", mb)
                        } else {
                            // 不足 1MB 显示 KB，保留两位小数
                            pref.title = String.format("${originalTitle}（下载中: %.2f KB）", kb)
                        }
                    }
                }
            }

            // 定位并创建目标文件
            val fileName = "wanxiang-lts-zh-hans.gram"
            val targetFile = rootFolder.findFile(fileName) ?: rootFolder.createFile("application/octet-stream", fileName)
            // "wt" 覆盖写入
            targetFile?.uri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    progressStream.copyTo(output)
                }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private suspend fun performUpgradeSchema(context: Context, rootUri: Uri, pref: Preference) = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val url = "https://codeload.github.com/kingkongdog/rime_wanxiang/zip/refs/heads/wanxiang"
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri) ?: throw Exception("无法解析 Rime 目录")

        val originalTitle = pref.title

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("网络请求失败: ${response.code}")

            val body = response.body ?: throw Exception("下载内容为空")
            val totalSize = body.contentLength()
            val inputStream = body.byteStream()

            // 包装流以监听下载进度
            val progressStream = ProgressInputStream(inputStream) { bytesRead ->
                // 计算 KB 和 MB
                val kb = bytesRead / 1024.0
                val mb = kb / 1024.0

                CoroutineScope(Dispatchers.Main).launch {
                    if (totalSize > 0) {
                        // 如果能获取总大小，显示百分比
                        val percent = (bytesRead * 100 / totalSize).toInt()
                        pref.title = "$originalTitle（下载中: $percent%）"
                    } else {
                        // 如果总大小未知（GitHub 常见情况），显示已下载大小
                        if (mb >= 1) {
                            // 超过 1MB 显示 MB，保留两位小数
                            pref.title = String.format("${originalTitle}（下载中: %.2f MB）", mb)
                        } else {
                            // 不足 1MB 显示 KB，保留两位小数
                            pref.title = String.format("${originalTitle}（下载中: %.2f KB）", kb)
                        }
                    }
                }
            }

            val zipInputStream = ZipInputStream(progressStream)
            var entry = zipInputStream.nextEntry

            while (entry != null) {
                // 剥离 GitHub ZIP 默认的第一层目录 rime_wanxiang-wanxiang/
                val entryPath = entry.name.substringAfter("/", "")

                if (entryPath.isNotEmpty()) {
                    if (entry.isDirectory) {
                        createDirRecursive(rootFolder, entryPath)
                    } else {
                        writeFileToSAF(context, rootFolder, entryPath, zipInputStream)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
    }

    // 递归创建 SAF 目录
    private fun createDirRecursive(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        path.split("/").filter { it.isNotEmpty() }.forEach { segment ->
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
        }
        return current
    }

    // 将解压流写入 SAF 文件
    private fun writeFileToSAF(context: Context, root: DocumentFile, filePath: String, zipStream: ZipInputStream) {
        val segments = filePath.split("/")
        val fileName = segments.last()
        val dirPath = segments.dropLast(1).joinToString("/")

        val targetDir = if (dirPath.isEmpty()) root else createDirRecursive(root, dirPath)

        targetDir?.let { dir ->
            // 使用 "wt" 模式：如果文件存在则覆盖并截断
            val file = dir.findFile(fileName) ?: dir.createFile("application/octet-stream", fileName)
            file?.uri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    zipStream.copyTo(output)
                }
            }
        }
    }

    // 4. 全 SAF 写入逻辑：从 sourceUri 拷贝到已授权的 TreeUri
    private fun importFileToAuthorizedFolder(sourceUri: Uri) {
        val context = requireContext()
        val resolver = context.contentResolver

        // 获取已持久化的权限列表
        val persistedUri = resolver.persistedUriPermissions.firstOrNull()?.uri

        if (persistedUri == null) {
            Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val fileName = getFileName(sourceUri)

            if (fileName.isNullOrBlank()) {
                Toast.makeText(context, "错误：无法解析文件名，导入中止", Toast.LENGTH_LONG).show()
                return
            }
            
            // 将 TreeUri 包装为 DocumentFile 目录对象
            val rootDoc = DocumentFile.fromTreeUri(context, persistedUri)
            
            if (rootDoc != null && rootDoc.isDirectory) {
                // 严格模式：如果存在同名文件则先删除，实现“覆盖”
                rootDoc.findFile(fileName)?.delete()
                
                // 创建新文件（SAF 会根据 mimeType 自动处理）
                val targetFile = rootDoc.createFile("application/octet-stream", fileName)
                
                if (targetFile != null) {
                    // 全流式操作：从 SAF 源到 SAF 目标
                    resolver.openInputStream(sourceUri)?.use { input ->
                        resolver.openOutputStream(targetFile.uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    Toast.makeText(context, "成功导入: $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "导入失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // 从 Content Uri 中解析文件名的标准方法
    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = cursor.getString(index)
                }
            }
        }
        return name ?: uri.path?.substringAfterLast('/')
    }

    override fun onDestroy() {
        uiMode.unregisterOnChangeListener(onUiModeChange)
        showAppIcon.unregisterOnChangeListener(onShowAppIconChange)
        super.onDestroy()
    }

    companion object {
        private const val SETTINGS_ACTIVITY_NAME = "com.osfans.trime.MainLauncherAlias"
        fun showAppIcon(context: Context, enable: Boolean) {
            val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(ComponentName(context, SETTINGS_ACTIVITY_NAME), state, PackageManager.DONT_KILL_APP)
        }
    }
}
