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
import com.osfans.trime.R
import com.osfans.trime.ime.core.TrimeInputMethodService
import splitties.systemservices.inputMethodManager
import timber.log.Timber

object InputMethodUtils {
    private val serviceName = TrimeInputMethodService::class.java.name
    private val componentName =
        ComponentName(appContext, TrimeInputMethodService::class.java).flattenToShortString()

    private fun getSecureSettings(name: String) = Settings.Secure.getString(appContext.contentResolver, name)

    enum class VoiceInputMethodType {
        IME,
        RECOGNIZER,
    }

    data class VoiceInputMethod(
        val id: String,
        val label: String,
        val type: VoiceInputMethodType,
        val info: InputMethodInfo? = null,
        val subtype: InputMethodSubtype? = null,
        val packageName: String? = null,
        val activityName: String? = null,
    )

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

    fun voiceInputMethods(): List<VoiceInputMethod> {
        val pm = appContext.packageManager
        val methods = mutableListOf<VoiceInputMethod>()

        inputMethodManager.enabledInputMethodList.forEach { info ->
            for (i in 0 until info.subtypeCount) {
                val subType = info.getSubtypeAt(i)
                if (subType.mode.lowercase() in listOf("voice", "speech")) {
                    methods.add(
                        VoiceInputMethod(
                            id = "ime:${info.id}",
                            label = info.loadLabel(pm).toString(),
                            type = VoiceInputMethodType.IME,
                            info = info,
                            subtype = subType,
                        ),
                    )
                    break
                }
            }
        }

        val recognitionIntent = Intent("android.speech.action.RECOGNIZE_SPEECH")
        pm.queryIntentActivities(recognitionIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo
                val packageName = activityInfo.packageName
                val activityName = activityInfo.name
                val id = "recognizer:$packageName/$activityName"
                if (methods.none { it.id == id }) {
                    methods.add(
                        VoiceInputMethod(
                            id = id,
                            label = resolveInfo.loadLabel(pm).toString(),
                            type = VoiceInputMethodType.RECOGNIZER,
                            packageName = packageName,
                            activityName = activityName,
                        ),
                    )
                }
            }

        return methods
    }

    fun firstVoiceInput(): VoiceInputMethod? = voiceInputMethods().firstOrNull()

    fun findVoiceInputMethod(id: String): VoiceInputMethod? = voiceInputMethods().find { it.id == id }

    fun startVoiceInputMethod(service: TrimeInputMethodService, method: VoiceInputMethod) {
        when (method.type) {
            VoiceInputMethodType.IME -> {
                method.info?.let { info ->
                    method.subtype?.let { subtype ->
                        switchInputMethod(service, info.id, subtype)
                    }
                }
            }
            VoiceInputMethodType.RECOGNIZER -> {
                try {
                    val intent = Intent("android.speech.action.RECOGNIZE_SPEECH").apply {
                        component = ComponentName(
                            method.packageName ?: return,
                            method.activityName ?: return,
                        )
                        putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
                        putExtra("android.speech.extra.PROMPT", "语音输入")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    service.startActivity(intent)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to start voice recognizer: ${method.label}")
                    service.toast(R.string.no_voice_input_installed)
                }
            }
        }
    }

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
