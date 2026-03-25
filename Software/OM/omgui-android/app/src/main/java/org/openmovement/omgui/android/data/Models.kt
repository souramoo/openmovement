package org.openmovement.omgui.android.data

import java.io.File
import java.time.Instant
import java.time.LocalDateTime

enum class RecordingState {
    STOPPED,
    ALWAYS,
    INTERVAL,
}

data class CwaMetadata(
    val deviceId: Long?,
    val sessionId: Long?,
    val samplingRateHz: Int?,
    val samplingRangeG: Int?,
    val startTime: LocalDateTime?,
    val endTime: LocalDateTime?,
    val values: Map<String, String>,
)

enum class AppFileKind {
    RAW_CWA,
    WAV,
    CSV,
    OTHER,
}

data class AppFile(
    val file: File,
    val kind: AppFileKind,
    val metadata: CwaMetadata? = null,
    val modifiedAt: Instant = Instant.ofEpochMilli(file.lastModified()),
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

data class AppSettings(
    val workingRoot: File,
    val downloadsRoot: File,
    val exportsRoot: File,
    val filenameTemplate: String = "{DeviceId}_{SessionId}",
)

enum class ExportStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class ExportType {
    WAV,
    CSV,
    SVM,
    WTV,
    CUT,
    SLEEP,
}

data class ExportRequest(
    val inputFile: File,
    val type: ExportType,
    val rateHz: Int = 100,
    val autoCalibrate: Boolean = true,
    val epochSeconds: Int = 60,
    val filterMode: Int = 1,
    val svmMode: Int = 1,
    val cutPointModel: String = "0.046 0.093 0.419",
)

data class ExportJob(
    val id: String,
    val request: ExportRequest,
    val outputFile: File,
    val status: ExportStatus,
    val createdAt: Instant = Instant.now(),
    val message: String? = null,
)

data class Ax3DeviceSnapshot(
    val usbKey: String,
    val usbDeviceId: Int,
    val usbName: String,
    val hasPermission: Boolean,
    val serialId: String? = null,
    val deviceType: String? = null,
    val deviceId: Long? = null,
    val sessionId: Long? = null,
    val firmwareVersion: Int? = null,
    val hardwareVersion: Int? = null,
    val batteryLevel: Int? = null,
    val batteryHealth: Int? = null,
    val memoryHealth: Int? = null,
    val deviceTime: LocalDateTime? = null,
    val startTime: LocalDateTime? = null,
    val stopTime: LocalDateTime? = null,
    val sampleRateHz: Int? = null,
    val accelRangeG: Int? = null,
    val gyroRangeDps: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
    val dataBytes: Long? = null,
    val hasData: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val recordingState: RecordingState = RecordingState.STOPPED,
    val warning: String? = null,
) {
    val displayName: String
        get() = when {
            serialId != null -> serialId
            deviceId != null -> "AX3-${"%05d".format(deviceId)}"
            else -> usbName
        }
}

data class Ax3RecordingConfig(
    val sessionId: Long,
    val alwaysRecord: Boolean,
    val startTime: LocalDateTime,
    val stopTime: LocalDateTime,
    val samplingFrequencyHz: Int,
    val accelRangeG: Int,
    val gyroRangeDps: Int = 0,
    val lowPower: Boolean = false,
    val flashDuringRecording: Boolean = false,
    val syncTime: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
)

