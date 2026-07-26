/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import com.osfans.trime.BuildConfig
import com.osfans.trime.ime.core.TrimeInputMethodService
import splitties.systemservices.inputMethodManager
import timber.log.Timber

object InputMethodUtils {
    private val serviceName = TrimeInputMethodService::class.java.name
    private val componentName =
        ComponentName(appContext, TrimeInputMethodService::class.java).flattenToShortString()

    private fun getSecureSettings(name: String) = Settings.Secure.getString(appContext.contentResolver, name)

    fun checkIsTrimeEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        inputMethodManager.enabledInputMethodList
            .also {
                Timber.i("List of active IMEs: $it")
            }.any {
                it.packageName == BuildConfig.APPLICATION_ID && it.serviceName == serviceName
            }
    } else {
        val activeImeIds = getSecureSettings(Settings.Secure.ENABLED_INPUT_METHODS) ?: "(none)"
        Timber.i("List of active IMEs: $activeImeIds")
        activeImeIds.split(":").contains(componentName)
    }

    fun checkIsTrimeSelected(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        inputMethodManager.currentInputMethodInfo?.let {
            Timber.i("Selected IME: ${it.serviceName}")
            it.packageName == BuildConfig.APPLICATION_ID && it.serviceName == serviceName
        } ?: false
    } else {
        val selectedImeIds = getSecureSettings(Settings.Secure.DEFAULT_INPUT_METHOD) ?: "(none)"
        Timber.i("Selected IME: $selectedImeIds")
        selectedImeIds == componentName
    }

    fun showImeEnablerActivity(context: Context) = context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    fun showImePicker(): Boolean {
        inputMethodManager.showInputMethodPicker()
        return true
    }

    fun voiceInputMethods(): List<Pair<InputMethodInfo, InputMethodSubtype>> = inputMethodManager
        .enabledInputMethodList
        .mapNotNull { info ->
            for (i in 0 until info.subtypeCount) {
                val subType = info.getSubtypeAt(i)
                if (subType.mode.lowercase() in listOf("voice", "speech")) {
                    return@mapNotNull info to subType
                }
            }
            return@mapNotNull null
        }

    fun getVoiceRecognizers(): List<String> {
        val intent = Intent("android.speech.action.RECOGNIZE_SPEECH")
        return appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo.packageName }
            .distinct()
    }

    fun getAllVoiceInputOptions(): List<Pair<String, String>> {
        val pm = appContext.packageManager
        val options = mutableListOf<Pair<String, String>>()

        voiceInputMethods().forEach { (info, _) ->
            options.add(info.packageName to info.loadLabel(pm).toString())
        }

        getVoiceRecognizers().forEach { pkgName ->
            val optionId = "recognizer:$pkgName"
            if (options.none { it.first == optionId }) {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
                }.getOrElse { pkgName }
                options.add(optionId to label)
            }
        }

        return options
    }

    fun firstVoiceInput(): Pair<String, InputMethodSubtype>? = voiceInputMethods()
        .firstNotNullOfOrNull { (info, subType) -> info.id to subType }

    fun switchInputMethod(
        service: TrimeInputMethodService,
        id: String,
        subtype: InputMethodSubtype,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.switchInputMethod(id, subtype)
        } else {
            @Suppress("DEPRECATION")
            inputMethodManager
                .setInputMethodAndSubtype(service.window.window!!.attributes.token, id, subtype)
        }
    }
}
