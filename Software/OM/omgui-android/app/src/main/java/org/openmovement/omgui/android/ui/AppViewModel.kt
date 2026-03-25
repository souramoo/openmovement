package org.openmovement.omgui.android.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.openmovement.omgui.android.data.AppFile
import org.openmovement.omgui.android.data.AppSettingsStore
import org.openmovement.omgui.android.data.Ax3RecordingConfig
import org.openmovement.omgui.android.data.ExportRequest
import org.openmovement.omgui.android.device.Ax3Repository
import org.openmovement.omgui.android.export.ExportManager

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = AppSettingsStore(application)
    private val repository = Ax3Repository(application)
    private val exportManager = ExportManager(
        context = application,
        settingsStore = settingsStore,
        refreshFiles = { repository.refreshFiles(settingsStore.load()) },
    )

    val settings = settingsStore.settings
    val devices = repository.devices
    val files = repository.files
    val jobs = exportManager.jobs
    val status = repository.status

    init {
        viewModelScope.launch {
            refreshAll()
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            repository.refreshDevices()
            repository.refreshFiles(settingsStore.load())
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            repository.refreshDevices()
        }
    }

    fun refreshFiles() {
        viewModelScope.launch {
            repository.refreshFiles(settingsStore.load())
        }
    }

    fun identify(usbKey: String) {
        viewModelScope.launch {
            repository.identify(usbKey)
        }
    }

    fun syncTime(usbKey: String) {
        viewModelScope.launch {
            repository.syncTime(usbKey)
        }
    }

    fun clear(usbKey: String, wipe: Boolean) {
        viewModelScope.launch {
            repository.clear(usbKey, wipe)
            repository.refreshFiles(settingsStore.load())
        }
    }

    fun configure(usbKey: String, config: Ax3RecordingConfig) {
        viewModelScope.launch {
            repository.configure(usbKey, config)
        }
    }

    fun download(usbKey: String) {
        viewModelScope.launch {
            repository.download(usbKey, settingsStore.load())
        }
    }

    fun export(request: ExportRequest) {
        viewModelScope.launch {
            exportManager.runExport(request)
        }
    }

    fun clearCompletedJobs() {
        exportManager.clearCompleted()
    }

    fun updateFilenameTemplate(value: String) {
        settingsStore.updateFilenameTemplate(value)
    }

    fun findUsbDevice(usbKey: String): UsbDevice? = repository.findUsbDevice(usbKey)
}

