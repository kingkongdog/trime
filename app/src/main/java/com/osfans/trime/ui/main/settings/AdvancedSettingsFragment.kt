package com.osfans.trime.ui.main.settings

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
import androidx.preference.Preference
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment

class AdvancedSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().advanced) {

    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode
    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon

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

        // 按钮 B：选择并导入文件
        val importPref = Preference(context).apply {
            key = "saf_import_file"
            title = "导入文件 (SAF)"
            summary = "将文件安全地拷贝至已授权的目录"
            order = 999
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                importFileLauncher.launch("*/*")
                true
            }
        }

        preferenceScreen.addPreference(authPref)
        preferenceScreen.addPreference(importPref)
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