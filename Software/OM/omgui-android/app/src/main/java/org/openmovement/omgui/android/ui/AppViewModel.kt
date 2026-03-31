package org.openmovement.omgui.android.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openmovement.omgui.android.data.AppFile
import org.openmovement.omgui.android.data.AppSettingsStore
import org.openmovement.omgui.android.data.Ax3RecordingConfig
import org.openmovement.omgui.android.data.ExportRequest
import org.openmovement.omgui.android.device.Ax3Repository
import org.openmovement.omgui.android.export.ExportManager

enum class WorkflowStep {
    CONNECT,
    DOWNLOAD,
    RESET,
    DONE,
    ERROR,
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "AppViewModel"
    }

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
    private val mutableIsBusy = MutableStateFlow(false)
    private val mutableBusyMessage = MutableStateFlow("")
    private val mutableNotices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val mutableWorkflowStep = MutableStateFlow(WorkflowStep.CONNECT)
    private val mutableSupportEmailBody = MutableStateFlow<String?>(null)
    private var lastActionStep: WorkflowStep = WorkflowStep.DOWNLOAD
    val isBusy: StateFlow<Boolean> = mutableIsBusy.asStateFlow()
    val busyMessage: StateFlow<String> = mutableBusyMessage.asStateFlow()
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()
    val workflowStep: StateFlow<WorkflowStep> = mutableWorkflowStep.asStateFlow()
    val supportEmailBody: StateFlow<String?> = mutableSupportEmailBody.asStateFlow()

    init {
        launchGuarded("Initial refresh") {
            refreshAll()
        }
    }

    private suspend fun <T> runBusy(action: String, block: suspend () -> T): T {
        mutableIsBusy.value = true
        mutableBusyMessage.value = action
        return try {
            block()
        } finally {
            mutableBusyMessage.value = ""
            mutableIsBusy.value = false
        }
    }

    private fun launchGuarded(action: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                runBusy(action, block)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordError(action, error)
            }
        }
    }

    private fun syncWorkflowStepFromDevices() {
        val permittedDevicePresent = devices.value.any { it.hasPermission }
        if (!permittedDevicePresent) {
            mutableWorkflowStep.value = WorkflowStep.CONNECT
        } else if (mutableWorkflowStep.value == WorkflowStep.CONNECT) {
            mutableWorkflowStep.value = WorkflowStep.DOWNLOAD
        }
    }

    private fun recordError(action: String, error: Throwable) {
        Log.e(TAG, "$action failed", error)
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        repository.reportStatus("$action failed: $message")
        mutableWorkflowStep.value = WorkflowStep.ERROR
        mutableSupportEmailBody.value = buildSupportEmailBody(action, error)
    }

    private fun buildSupportEmailBody(action: String, error: Throwable): String {
        val deviceSummary = if (devices.value.isEmpty()) {
            "No AX3 devices detected."
        } else {
            devices.value.joinToString("\n\n") { device ->
                buildString {
                    append("Device: ${device.displayName}\n")
                    append("USB key: ${device.usbKey}\n")
                    append("Device ID: ${device.deviceId ?: "unknown"}\n")
                    append("Session: ${device.sessionId ?: "unknown"}\n")
                    append("Battery: ${device.batteryLevel ?: "unknown"}\n")
                    append("Data bytes: ${device.dataBytes ?: "unknown"}\n")
                    append("Rate: ${device.sampleRateHz ?: "unknown"}\n")
                    append("Range: ${device.accelRangeG ?: "unknown"}\n")
                    append("Warning: ${device.warning ?: "none"}")
                }
            }
        }
        return buildString {
            appendLine("Please describe what happened:")
            appendLine()
            appendLine("Action: $action")
            appendLine("Status: ${status.value}")
            appendLine("Workflow step: ${workflowStep.value}")
            appendLine()
            appendLine("Error log:")
            appendLine(Log.getStackTraceString(error))
            appendLine()
            appendLine("Device information:")
            appendLine(deviceSummary)
        }
    }

    fun dismissWorkflowError() {
        mutableSupportEmailBody.value = null
        mutableWorkflowStep.value = lastActionStep
        syncWorkflowStepFromDevices()
    }

    fun refreshAll() {
        launchGuarded("Refresh") {
            repository.refreshDevices()
            repository.refreshFiles(settingsStore.load())
            syncWorkflowStepFromDevices()
        }
    }

    fun refreshDevices() {
        launchGuarded("Refresh devices") {
            repository.refreshDevices()
            syncWorkflowStepFromDevices()
        }
    }

    fun refreshFiles() {
        launchGuarded("Refresh files") {
            repository.refreshFiles(settingsStore.load())
        }
    }

    fun identify(usbKey: String) {
        launchGuarded("Identify") {
            repository.identify(usbKey)
        }
    }

    fun syncTime(usbKey: String) {
        launchGuarded("Sync time") {
            repository.syncTime(usbKey)
        }
    }

    fun clear(usbKey: String, wipe: Boolean) {
        launchGuarded(if (wipe) "Wipe" else "Clear") {
            repository.clear(usbKey, wipe)
            repository.refreshFiles(settingsStore.load())
        }
    }

    fun configure(usbKey: String, config: Ax3RecordingConfig) {
        launchGuarded("Configure") {
            repository.configure(usbKey, config)
        }
    }

    fun download(usbKey: String) {
        launchGuarded("Download") {
            repository.download(usbKey, settingsStore.load())
        }
    }

    fun downloadAndExportCsv(usbKey: String) {
        lastActionStep = WorkflowStep.DOWNLOAD
        viewModelScope.launch {
            try {
                runBusy("Downloading and exporting CSV") {
                    val settings = settingsStore.load()
                    val downloadedFile = repository.download(usbKey, settings)
                    Log.i(TAG, "Downloaded AX3 file: ${downloadedFile.absolutePath}")
                    repository.reportStatus("Download complete. Exporting CSV...")
                    val csvFile = exportManager.exportCsv(downloadedFile)
                    Log.i(TAG, "Exported CSV file: ${csvFile.absolutePath}")
                    repository.refreshFiles(settings)
                    repository.reportStatus("Download + CSV complete: ${csvFile.name}")
                    mutableNotices.tryEmit("Download + CSV complete.")
                    mutableWorkflowStep.value = WorkflowStep.RESET
                    mutableSupportEmailBody.value = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordError("Download + CSV export", error)
            }
        }
    }

    fun resetDevice(usbKey: String, sessionId: Long) {
        lastActionStep = WorkflowStep.RESET
        val requestId = System.currentTimeMillis()
        Log.i(TAG, "Reset requested (#$requestId) usbKey=$usbKey sessionId=$sessionId")
        repository.reportStatus("Reset requested (#$requestId). Applying preset...")
        viewModelScope.launch {
            try {
                runBusy("Resetting device - this may take a while") {
                    repository.resetToPreset(
                        usbKey = usbKey,
                        sessionId = sessionId,
                    )
                    repository.refreshFiles(settingsStore.load())
                    repository.reportStatus("Reset complete: 100Hz/4g, flash on, always record (disconnect to start).")
                    mutableNotices.tryEmit("All done. You can unplug the device now.")
                    mutableWorkflowStep.value = WorkflowStep.DONE
                    mutableSupportEmailBody.value = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordError("Reset (#$requestId)", error)
            }
        }
    }

    fun diagnose(usbKey: String) {
        launchGuarded("Diagnose") {
            repository.diagnose(usbKey)
        }
    }

    fun stopRecording(usbKey: String) {
        launchGuarded("Stop recording") {
            repository.stopRecording(usbKey)
        }
    }

    fun export(request: ExportRequest) {
        launchGuarded("Export") {
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

