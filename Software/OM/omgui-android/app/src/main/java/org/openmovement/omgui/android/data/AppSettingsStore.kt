package org.openmovement.omgui.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class AppSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("omgui_android_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = mutableSettings

    fun load(): AppSettings {
        val workingRoot = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "openmovement").apply { mkdirs() }
        val downloadsRoot = File(workingRoot, "downloads").apply { mkdirs() }
        val exportsRoot = File(workingRoot, "exports").apply { mkdirs() }
        return AppSettings(
            workingRoot = workingRoot,
            downloadsRoot = downloadsRoot,
            exportsRoot = exportsRoot,
            filenameTemplate = prefs.getString(KEY_FILENAME_TEMPLATE, "{DeviceId}_{SessionId}") ?: "{DeviceId}_{SessionId}",
        )
    }

    fun updateFilenameTemplate(value: String) {
        prefs.edit().putString(KEY_FILENAME_TEMPLATE, value).apply()
        mutableSettings.value = load()
    }

    companion object {
        private const val KEY_FILENAME_TEMPLATE = "filename_template"
    }
}
