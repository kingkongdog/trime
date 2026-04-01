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
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val originalGramTitle = "升级 wanxiang 模型"
    private val originalGramSummary = "下载最新的 wanxiang 模型文件到 rime 文件夹，并部署"

    private val originalSchemaTitle = "升级 wanxiang 输入方案"
    private val originalSchemaSummary = "下载最新的 wanxiang 输入方案到 rime 文件夹，并部署"

    private val originalThemeTitle = "升级 Q 主题"
    private val originalThemeSummary = "下载最新的 Q 主题到 rime 文件夹，并部署"

    private val dirCache = mutableMapOf<String, DocumentFile>()

    // java.lang.IllegalStateException: Fragment AdvancedSettingsFragment{7b76595} (05a0eac8-8cbc-45fb-a130-5f4a9ac5b3fa) not attached to a context.
    // private var rootUri = requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
    // 只有在第一次被使用时才会执行，此时 context 肯定已经存在了
    // lazy 只支持 val
//    private val rootUri: Uri? by lazy {
//        requireContext().contentResolver.persistedUriPermissions
//            .firstOrNull()?.uri
//    }

    private var _rootUri: Uri? = null // 幕后属性

    var rootUri: Uri?
        get() {
            // 如果还没值，就去系统查一次
            if (_rootUri == null) {
                _rootUri = requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
            }
            return _rootUri
        }
        set(value) {
            _rootUri = value // 允许手动修改
        }

    // 1. 用于授权 rime 目录的 Launcher (OpenDocumentTree)
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { treeUri ->
            // 严格 SAF 要求：必须持久化权限，否则重启后该 Uri 将失效
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            
            // 建议：此处可以将 treeUri.toString() 存入 AppPrefs，方便后续自动读取
            Toast.makeText(requireContext(), "rime 目录授权成功", Toast.LENGTH_SHORT).show()
            rootUri = requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
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
                if (rootUri == null) {
                    folderPickerLauncher.launch(null)
                } else {
                    Toast.makeText(context, "已经授权过 rime 目录，无需重复操作", Toast.LENGTH_SHORT).show()
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

        // 按钮 C：升级 Q 主题
        val upgradeQThemePref = Preference(context).apply {
            key = "upgrade_q_theme"
            title = originalThemeTitle
            summary = originalThemeSummary
            order = 1000
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (rootUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val originalVersion = readVersion(context, "q_version")

                        // 1. 先下载
                        downloadToTempFile(context,
                            "https://codeload.github.com/kingkongdog/trime-q-theme/zip/refs/heads/main",
                            "theme_temp.zip", this@apply, originalThemeTitle)

                        // 2. 再解压
//                        unzipToSAF(context, localZip, this@apply, originalThemeTitle)

                        // 2. 解压 zip 文件到 cacheDir
                        unzipToCacheDir(context, "theme_temp.zip", "theme_temp", this@apply, originalThemeTitle)

                        // 3. 同步文件到 SAF
                        syncToSAF(context, "theme_temp", this@apply, originalThemeTitle)

                        // 4. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalThemeTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalThemeTitle（部署完成）"
                                Toast.makeText(context, "Q 主题升级完成！", Toast.LENGTH_SHORT).show()
                                title = "$originalThemeTitle（版本：${readVersion(context, "q_version")} from $originalVersion）"
                                summary = originalThemeSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalThemeTitle（升级失败）"
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

        // 按钮 D：升级 wanxiang 模型
        val upgradeWanXiangGramPref = Preference(context).apply {
            key = "upgrade_wanxiang_gram"
            title = originalGramTitle
            summary = originalGramSummary
            order = 1001
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (rootUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val originalVersion = getGramFileDate(context)

                        // 1. 先下载
                        downloadToTempFile(context,
                            "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram",
                            "gram_temp.gram", this@apply, originalGramTitle)

                        // 2. 移动到 rime 文件夹
                        moveToSAF(context, "gram_temp.gram", "wanxiang-lts-zh-hans.gram", this@apply, originalGramTitle)

                        // 3. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalGramTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalGramTitle（部署完成）"
                                Toast.makeText(context, "wanxiang 模型升级完成！", Toast.LENGTH_SHORT).show()
                                title = "$originalGramTitle（版本：${getGramFileDate(context)} from $originalVersion）"
                                summary = originalGramSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalGramTitle（升级失败）"
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

        // 按钮 E：升级 wanxiang 输入方案
        val upgradeWanXiangSchemaPref = Preference(context).apply {
            key = "upgrade_wanxiang_schema"
            title = originalSchemaTitle
            summary = originalSchemaSummary
            order = 1002
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (rootUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val originalVersion = readVersion(context, "wanxiang_version")

                        // 1. 先下载
                        downloadToTempFile(context,
                            "https://codeload.github.com/kingkongdog/rime_wanxiang/zip/refs/heads/wanxiang", 
                            "schema_temp.zip", this@apply, originalSchemaTitle,)

                        // 2. 再解压
//                        unzipToSAF(context, localZip, this@apply, originalSchemaTitle)

                        // 2. 解压 zip 文件到 cacheDir
                        unzipToCacheDir(context, "schema_temp.zip","schema_temp", this@apply, originalSchemaTitle)

                        // 3. 同步文件到 SAF
                        syncToSAF(context, "schema_temp", this@apply, originalSchemaTitle)

                        // 3. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalSchemaTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalSchemaTitle（部署完成）"
                                Toast.makeText(context, "wanxiang 输入方案升级完成！", Toast.LENGTH_SHORT).show()
                                title = "$originalSchemaTitle（版本：${readVersion(context, "wanxiang_version")} from $originalVersion）"
                                summary = originalSchemaSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalSchemaTitle（升级失败）"
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

        // 按钮 D：升级 wanxiang 模型
        val upgradeWanXiangGramPrefDownloadToSAF = Preference(context).apply {
            key = "upgrade_wanxiang_gram"
            title = originalGramTitle + "test"
            summary = originalGramSummary
            order = 1001
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (rootUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val originalVersion = getGramFileDate(context)

                        performUpgradeGram(context, this@apply)

                        // 3. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalGramTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalGramTitle（部署完成）"
                                Toast.makeText(context, "wanxiang 模型升级完成！", Toast.LENGTH_SHORT).show()
                                title = "$originalGramTitle（版本：${getGramFileDate(context)} from $originalVersion）"
                                summary = originalGramSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalGramTitle（升级失败）"
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

        // 按钮 E：升级 wanxiang 输入方案
        val upgradeWanXiangSchemaPrefDownloadToSAF = Preference(context).apply {
            key = "upgrade_wanxiang_schema"
            title = originalSchemaTitle + "test"
            summary = originalSchemaSummary
            order = 1002
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (rootUri == null) {
                    Toast.makeText(context, "请先授权 rime 文件夹", Toast.LENGTH_LONG).show()
                    return@setOnPreferenceClickListener true
                }

                // 禁止重复点击
                isEnabled = false

                // 使用主线程协程
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val originalVersion = readVersion(context, "wanxiang_version")

                        performUpgradeSchemaAndTheme(context, this@apply, originalSchemaTitle, "https://codeload.github.com/kingkongdog/rime_wanxiang/zip/refs/heads/wanxiang")

                        // 3. 执行部署 (假设 Trime 有对应的 Service 接口)
                        viewModel.rime.launchOnReady {
                            // 切换到主线程更新“部署中”状态
                            launch(Dispatchers.Main) {
                                title = "$originalSchemaTitle（部署中。。。）"
                            }

                            it.deploy()

                            // 部署完成后，再次切回主线程更新结果
                            launch(Dispatchers.Main) {
                                title = "$originalSchemaTitle（部署完成）"
                                Toast.makeText(context, "wanxiang 输入方案升级完成！", Toast.LENGTH_SHORT).show()
                                title = "$originalSchemaTitle（版本：${readVersion(context, "wanxiang_version")} from $originalVersion）"
                                summary = originalSchemaSummary
                            }
                        }
                    } catch (e: Exception) {
                        title = "$originalSchemaTitle（升级失败）"
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
        preferenceScreen.addPreference(upgradeQThemePref)
        preferenceScreen.addPreference(upgradeWanXiangGramPref)
        preferenceScreen.addPreference(upgradeWanXiangGramPrefDownloadToSAF)
        preferenceScreen.addPreference(upgradeWanXiangSchemaPref)
                preferenceScreen.addPreference(upgradeWanXiangSchemaPrefDownloadToSAF)

        // viewLifecycleOwner.lifecycleScope 闪退：Can't access the Fragment View's LifecycleOwner
        lifecycleScope.launch {
            if (rootUri != null) {
                upgradeQThemePref.title = "$originalThemeTitle（版本：${readVersion(context, "q_version")}）"
            }
        }

        lifecycleScope.launch {
            if (rootUri != null) {
                upgradeWanXiangGramPref.title = "$originalGramTitle（版本：${getGramFileDate(context)}）"
            }
        }

        // viewLifecycleOwner.lifecycleScope 闪退：Can't access the Fragment View's LifecycleOwner
        lifecycleScope.launch {
            if (rootUri != null) {
                upgradeWanXiangSchemaPref.title = "$originalSchemaTitle（版本：${readVersion(context, "wanxiang_version")}）"
            }
        }
    }

    private suspend fun getGramFileDate(context: Context): String = withContext(Dispatchers.IO) {
        val fileName = "wanxiang-lts-zh-hans.gram"

        // 1. 定位根目录
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!)
            ?: return@withContext "无法访问 Rime 文件夹"

        // 2. 查找目标文件
        val gramFile = rootFolder.findFile(fileName)
            ?: return@withContext "未知版本"

        // 3. 获取最后修改时间 (毫秒值)
        val lastModified = gramFile.lastModified()

        if (lastModified == 0L) {
            "未知版本"
        } else {
            // 4. 格式化日期
            val sdf = SimpleDateFormat("MM/dd-HH:mm", Locale.getDefault())
            sdf.format(Date(lastModified))
        }
    }

    private suspend fun readVersion(context: Context, versionFile: String): String = withContext(Dispatchers.IO) {
        // 1. 获取根目录对象
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!)
            ?: throw Exception("无法访问 Rime 文件夹")

        // 2. 寻找名为 "version" 的文件
        val versionFile = rootFolder.findFile(versionFile)
            ?: return@withContext "未知版本" // 如果文件不存在，返回默认值

        try {
            // 3. 打开输入流并读取内容
            context.contentResolver.openInputStream(versionFile.uri)?.use { inputStream ->
                // 使用 BufferedReader 读取第一行，或者全量读取
                inputStream.bufferedReader().use { reader ->
                    reader.readLine()?.trim() ?: "空文件"
                }
            } ?: "无法读取"
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }

    @SuppressLint("DefaultLocale")
    private suspend fun performUpgradeGram(context: Context, pref: Preference) = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val url = "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!) ?: throw Exception("无法解析 Rime 目录")

        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36").build()
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
                // 始终显示已下载大小，超过 1MB 显示 MB，不足 1MB 显示 KB，保留两位小数
                val size = if (mb >= 1) String.format("%.2f MB", mb) else String.format("%.2f KB", kb)
                // 如果能获取总大小，显示百分比
                val percent = if (totalSize > 0) String.format("，%.1f%%", bytesRead * 100.0 / totalSize) else ""
                val title = "$originalGramTitle（下载中：$size$percent）"

                CoroutineScope(Dispatchers.Main).launch {
                    pref.title = title
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
    private suspend fun downloadToTempFile(context: Context, url: String, tempFileName: String, pref: Preference, originalTitle: String ) = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val tempFile = File(context.cacheDir, tempFileName)

        // 如果之前有残留，先删掉
        if (tempFile.exists()) tempFile.delete()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("网络请求失败: ${response.code}")
            val body = response.body ?: throw Exception("下载内容为空")
            val totalSize = body.contentLength()

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        // 计算 KB 和 MB
                        val kb = totalRead / 1024.0
                        val mb = kb / 1024.0
                        // 始终显示已下载大小，超过 1MB 显示 MB，不足 1MB 显示 KB，保留两位小数
                        val size = if (mb >= 1) String.format("%.2f MB", mb) else String.format("%.2f KB", kb)
                        // 如果能获取总大小，显示百分比
                        val percent = if (totalSize > 0) String.format("，%.1f%%", totalRead * 100.0 / totalSize) else ""
                        val title = "$originalTitle（下载中：$size$percent）"

                        withContext(Dispatchers.Main) {
                            pref.title = title
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private suspend fun moveToSAF(context: Context, sourceFileName: String, targetFileName: String, pref: Preference, originalTitle: String) = withContext(Dispatchers.IO) {
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!) ?: throw Exception("无法解析 Rime 目录")
        val sourceFile = File(context.cacheDir, sourceFileName)

        val targetFile = rootFolder.findFile(targetFileName)
            ?: rootFolder.createFile("application/octet-stream", targetFileName)

        targetFile?.uri?.let { uri ->
            sourceFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    val totalSize = sourceFile.length()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        // 计算 KB 和 MB
                        val kb = totalRead / 1024.0
                        val mb = kb / 1024.0
                        // 始终显示已下载大小，超过 1MB 显示 MB，不足 1MB 显示 KB，保留两位小数
                        val size = if (mb >= 1) String.format("%.2f MB", mb) else String.format("%.2f KB", kb)
                        // 如果能获取总大小，显示百分比
                        val percent = if (totalSize > 0) String.format("，%.1f%%", totalRead * 100.0 / totalSize) else ""
                        val title = "$originalTitle（移动中：$size$percent）"

                        withContext(Dispatchers.Main) {
                            pref.title = title
                        }
                    }
                }
            }
        }
        sourceFile.delete()
    }

    private suspend fun unzipToCacheDir(
        context: Context,
        zipFileName: String,
        folderName: String,
        pref: Preference,
        originalTitle: String
    ) = withContext(Dispatchers.IO) {
        val zipFile = File(context.cacheDir, zipFileName)
        val outputDir = File(context.cacheDir, folderName)
        if (outputDir.exists()) {
            outputDir.deleteRecursively() // 清理旧的残留数据
        }
        outputDir.mkdirs()

        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            val totalEntries = zip.size()
            var processed = 0

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()

                // 2. 过滤掉 GitHub 压缩包自带的顶层随机目录名（如 rime-wanxiang-main/）
                // 或者是处理空文件夹
                val entryPath = entry.name.substringAfter("/", "")
                if (entryPath.isNotEmpty() && !entryPath.startsWith(".")) {
                    val outFile = File(outputDir, entryPath)

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        // 确保父目录存在（处理深层路径）
                        outFile.parentFile?.mkdirs()

                        // 3. 执行解压：从本地文件流拷贝到本地文件流，速度极快
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                processed++
                withContext(Dispatchers.Main) {
                    pref.title = "$originalTitle（解压中：$processed / $totalEntries）"
                }
            }
        }

        zipFile.delete()
    }

    private suspend fun syncToSAF(
        context: Context,
        folderName: String,
        pref: Preference,
        originalTitle: String
    ) = withContext(Dispatchers.IO) {
        val localDir = File(context.cacheDir, folderName)
        val totalFiles = localDir.walk().count { it.isFile }
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!) ?: throw Exception("无法解析 Rime 目录")
        syncFolderToSAF(context, localDir, rootFolder, totalFiles, 0, pref, originalTitle)
        localDir.deleteRecursively()
    }
    /**
     * 递归同步本地文件夹到 SAF 目录
     */
    private suspend fun syncFolderToSAF(
        context: Context,
        localDir: File,
        safDir: DocumentFile,
        total: Int,
        processed: Int,
        pref: Preference,
        originalTitle: String
    ): Int = withContext(Dispatchers.IO) {
        val files = localDir.listFiles() ?: return@withContext 0
        var processed = processed

        files.forEach { file ->
            if (file.isDirectory) {
                // 如果是文件夹，递归创建并进入
                val nextSafDir = safDir.findFile(file.name) ?: safDir.createDirectory(file.name)
                if (nextSafDir != null) {
                    processed = syncFolderToSAF(context, file, nextSafDir, total, processed, pref, originalTitle)
                }
            } else {
                // 如果是文件，执行 SAF 写入
                val targetFile = safDir.findFile(file.name) ?: safDir.createFile("application/octet-stream", file.name)
                targetFile?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        file.inputStream().use { input ->
                            // 🚩 使用 64KB 缓冲区减少系统调用次数
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                }
                processed++
                withContext(Dispatchers.Main) {
                    pref.title = "$originalTitle（文件同步中：$processed / $total）"
                }
            }
        }

        return@withContext processed
    }

    private suspend fun unzipToSAF(context: Context, zipFile: File, pref: Preference, originalTitle: String) = withContext(Dispatchers.IO) {
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!) ?: throw Exception("无法解析 Rime 目录")

        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            val totalEntries = zip.size()
            var processed = 0

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()

                if(!entry.isDirectory) {
                    // 剥离 GitHub ZIP 默认的第一层目录 rime_wanxiang-wanxiang/
                    val entryPath = entry.name.substringAfter("/", "")

                    if (entryPath.isNotEmpty() && !entryPath.startsWith(".")) {
                        zip.getInputStream(entry).use { input ->
                            writeFileToSAF(context, rootFolder, entryPath, input)
                        }
                    }
                }

                // 更新解压进度
                processed++
                withContext(Dispatchers.Main) {
                    pref.title = "$originalTitle（解压中：$processed / $totalEntries）"
                }
            }
        }
        // 处理完后删除临时文件
        zipFile.delete()
    }

    @SuppressLint("DefaultLocale")
    private suspend fun performUpgradeSchemaAndTheme(context: Context, pref: Preference, originalTitle: String, url: String) = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val rootFolder = DocumentFile.fromTreeUri(context, rootUri!!) ?: throw Exception("无法解析 Rime 目录")

        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36").build()
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
                // 始终显示已下载大小，超过 1MB 显示 MB，不足 1MB 显示 KB，保留两位小数
                val size = if (mb >= 1) String.format("%.2f MB", mb) else String.format("%.2f KB", kb)
                // 如果能获取总大小，显示百分比
                val percent = if (totalSize > 0) String.format("，%.1f%%", bytesRead * 100.0 / totalSize) else ""
                val title = "$originalTitle（下载中：$size$percent）"

                CoroutineScope(Dispatchers.Main).launch {
                    pref.title = title
                }
            }

            val zipInputStream = ZipInputStream(progressStream)
            var entry = zipInputStream.nextEntry

            while (entry != null) {
                if(!entry.isDirectory) {
                    // 剥离 GitHub ZIP 默认的第一层目录 rime_wanxiang-wanxiang/
                    val entryPath = entry.name.substringAfter("/", "")

                    if (entryPath.isNotEmpty() && !entryPath.startsWith(".")) {
                        writeFileToSAF(context, rootFolder, entryPath, zipInputStream)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }
    }

    // 递归创建 SAF 目录
//    private fun createDirRecursive(root: DocumentFile, path: String): DocumentFile? {
//        var current = root
//        path.split("/").filter { it.isNotEmpty() }.forEach { segment ->
//            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
//        }
//        return current
//    }
    // createDirWithCache
    private fun createDirRecursive(root: DocumentFile, path: String): DocumentFile? {
        if (path.isEmpty()) return root

        // 1. 如果整个路径已经在缓存中，直接返回
        dirCache[path]?.let { return it }

        // 2. 否则，逐级检查并构建缓存
        var current = root
        val segments = path.split("/").filter { it.isNotEmpty() }
        val currentPath = StringBuilder()

        for (i in segments.indices) {
            val segment = segments[i]
            if (currentPath.isNotEmpty()) currentPath.append("/")
            currentPath.append(segment)

            val fullSegmentPath = currentPath.toString()

            // 逐级检查缓存，避免每一级都去 findFile
            current = dirCache[fullSegmentPath] ?: (
                    current.findFile(segment) ?: current.createDirectory(segment)
                    ) ?: return null

            // 将这一级存入缓存
            dirCache[fullSegmentPath] = current
        }

        return current
    }

    // 将解压流写入 SAF 文件
    private fun writeFileToSAF(context: Context, root: DocumentFile, filePath: String, input: InputStream) {
        val segments = filePath.split("/")
        val fileName = segments.last()
        val dirPath = segments.dropLast(1).joinToString("/")

        val targetDir = if (dirPath.isEmpty()) root else createDirRecursive(root, dirPath)

        targetDir?.let { dir ->
            // 使用 "wt" 模式：如果文件存在则覆盖并截断
            val file = dir.findFile(fileName) ?: dir.createFile("application/octet-stream", fileName)
            file?.uri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    input.copyTo(output)
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
