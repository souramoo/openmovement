package org.openmovement.omgui.android.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice.Companion.getMassStorageDevices
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.partition.PartitionTableEntry
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import org.openmovement.omgui.android.data.AppFile
import org.openmovement.omgui.android.data.AppFileKind
import org.openmovement.omgui.android.data.AppSettings
import org.openmovement.omgui.android.data.Ax3DeviceSnapshot
import org.openmovement.omgui.android.data.Ax3RecordingConfig
import org.openmovement.omgui.android.data.CwaMetadata
import org.openmovement.omgui.android.data.RecordingState
import org.openmovement.omgui.android.util.AxivityDateTime
import org.openmovement.omgui.android.util.CwaMetadataParser
import org.openmovement.omgui.android.util.MetadataCodec
import org.openmovement.omgui.android.util.ensureParentDirectories
import org.openmovement.omgui.android.util.isCloseTo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

class Ax3Repository(context: Context) {
    private companion object {
        const val TAG = "Ax3Repository"
        const val AX3_VENDOR_ID = 0x04d8
        const val AX3_PRODUCT_ID = 0x0057
        // Maximum sectors per BOT READ10 command. Android usbfs limits bulk transfers
        // to ~16 KB per ioctl call, and the AX3 PIC firmware stalls on larger requests.
        // 32 sectors × 512 bytes = 16 KB, the safe conservative limit.
        const val MAX_SECTORS_PER_BOT_READ = 32
        const val CWA_SECTOR_SIZE = 512
        const val CWA_DATA_START_SECTOR = 2L
        const val CWA_DATA_HEADER_MAGIC = 0x5841

        val alwaysStart: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
        val alwaysStop: LocalDateTime = LocalDateTime.of(2063, 12, 31, 23, 59, 59)
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ax3SerialProber = UsbSerialProber(
        ProbeTable().apply {
            addProduct(AX3_VENDOR_ID, AX3_PRODUCT_ID, CdcAcmSerialDriver::class.java)
        },
    )
    private val mutableDevices = MutableStateFlow<List<Ax3DeviceSnapshot>>(emptyList())
    private val mutableFiles = MutableStateFlow<List<AppFile>>(emptyList())
    private val mutableStatus = MutableStateFlow("Ready")
    private var botTagCounter = 0x4f4d5000

    val devices: StateFlow<List<Ax3DeviceSnapshot>> = mutableDevices.asStateFlow()
    val files: StateFlow<List<AppFile>> = mutableFiles.asStateFlow()
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    fun reportStatus(message: String) {
        mutableStatus.value = message
    }

    fun findUsbDevice(usbKey: String): UsbDevice? = candidateDevices().firstOrNull { usbKey(it) == usbKey }

    suspend fun refreshDevices() {
        withContext(Dispatchers.IO) {
            mutableStatus.value = "Scanning for AX3 devices..."
            val snapshots = candidateDevices().map { device ->
                if (!usbManager.hasPermission(device)) {
                    Ax3DeviceSnapshot(
                        usbKey = usbKey(device),
                        usbDeviceId = device.deviceId,
                        usbName = device.productName ?: "AX3 USB device",
                        hasPermission = false,
                        warning = "USB permission required",
                    )
                } else {
                    runCatching { readSnapshot(device) }
                        .getOrElse { error ->
                            readFallbackSnapshot(device, error)
                        }
                }
            }.sortedBy { it.displayName }
            mutableDevices.value = snapshots
            mutableStatus.value = if (snapshots.isEmpty()) "No AX3 devices detected." else "Found ${snapshots.size} AX3 device(s)."
        }
    }

    suspend fun refreshFiles(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            val discovered = mutableListOf<AppFile>()
            listOf(settings.downloadsRoot, settings.exportsRoot)
                .filter { it.exists() }
                .forEach { root ->
                    root.listFiles()
                        ?.filter { it.isFile }
                        ?.sortedByDescending { it.lastModified() }
                        ?.forEach { file ->
                            val kind = when (file.extension.lowercase()) {
                                "cwa" -> AppFileKind.RAW_CWA
                                "wav" -> AppFileKind.WAV
                                "csv" -> AppFileKind.CSV
                                else -> AppFileKind.OTHER
                            }
                            val metadata = if (kind == AppFileKind.RAW_CWA) CwaMetadataParser.parse(file) else null
                            discovered += AppFile(file = file, kind = kind, metadata = metadata)
                        }
                }
            mutableFiles.value = discovered
        }
    }

    suspend fun identify(usbKey: String) {
        val device = requireUsbDevice(usbKey)
        withProtocol(device) { protocol ->
            protocol.setLed(7)
            Thread.sleep(1200)
            protocol.setLed(-1)
        }
        mutableStatus.value = "Identified ${mutableDevices.value.firstOrNull { it.usbKey == usbKey }?.displayName ?: usbKey}."
        refreshDevices()
    }

    suspend fun syncTime(usbKey: String) {
        val device = requireUsbDevice(usbKey)
        withProtocol(device) { protocol ->
            protocol.syncTime()
        }
        mutableStatus.value = "Synchronized time for ${device.productName ?: usbKey}."
        refreshDevices()
    }

    suspend fun stopRecording(usbKey: String) {
        val device = requireUsbDevice(usbKey)
        mutableStatus.value = "Stopping recording..."
        withProtocol(device) { protocol ->
            // Clear scheduled/always-record interval so recording stops immediately.
            protocol.setDelays(null, null)
            protocol.commit()
        }
        delay(300L)
        refreshDevices()
        mutableStatus.value = "Recording stopped."
    }

    suspend fun clear(usbKey: String, wipe: Boolean) {
        val device = requireUsbDevice(usbKey)
        mutableStatus.value = if (wipe) "Wiping device..." else "Clearing device..."
        withProtocol(device) { protocol ->
            protocol.setSession(0)
            protocol.setMetadata(emptyMap())
            protocol.setDelays(null, null)
            protocol.setAccelConfig(100, 8, false, 0)
            protocol.erase(if (wipe) EraseLevel.WIPE else EraseLevel.QUICK_FORMAT)
        }
        // After format commands the MSC side can lag before reporting the updated filesystem.
        delay(if (wipe) 1500L else 600L)
        mutableStatus.value = if (wipe) "Wiped device." else "Cleared device."
        refreshDevices()
    }

    suspend fun configure(usbKey: String, config: Ax3RecordingConfig) {
        val device = requireUsbDevice(usbKey)
        mutableStatus.value = "Configuring device..."
        withProtocol(device) { protocol ->
            protocol.setSession(config.sessionId.toInt())
            protocol.setMetadata(config.metadata)
            protocol.setMaxSamples(0)
            protocol.setAccelConfig(
                rateHz = config.samplingFrequencyHz,
                accelRange = config.accelRangeG,
                lowPower = config.lowPower,
                gyroRangeDps = config.gyroRangeDps,
            )
            if (config.syncTime) {
                protocol.syncTime()
            }
            protocol.setDebug(if (config.flashDuringRecording) 3 else 0)
            protocol.setDelays(
                if (config.alwaysRecord) alwaysStart else config.startTime,
                if (config.alwaysRecord) alwaysStop else config.stopTime,
            )
            // Match Windows OmDevice.SetInterval: setDelays + plain commit (no erase).
            // Erase is only done by the dedicated Clear/Wipe operation.
            protocol.commit()
        }
        delay(400L)
        refreshDevices()
        val requestedStart = if (config.alwaysRecord) "always" else config.startTime.toString()
        val requestedStop = if (config.alwaysRecord) "always" else config.stopTime.toString()
        mutableStatus.value = "Configured: ${config.samplingFrequencyHz}Hz/${config.accelRangeG}g, start=$requestedStart, stop=$requestedStop"
    }

    suspend fun resetToPreset(usbKey: String, sessionId: Long) {
        val device = requireUsbDevice(usbKey)
        mutableStatus.value = "Reset: opening AX3 control channel..."
        var appliedSummary = ""
        var nonFatalWarning: String? = null
        withTimeout(60_000L) {
            // Phase 1: wipe and let the AX3 USB endpoints settle.
            withProtocol(device) { protocol ->
                mutableStatus.value = "Reset: wiping flash..."
                protocol.erase(EraseLevel.WIPE)
            }
            delay(1800L)

            // Phase 2: reopen protocol and apply core preset settings.
            withProtocol(device) { protocol ->
                mutableStatus.value = "Reset: applying preset session and metadata..."
                protocol.setSession(sessionId.toInt())
                protocol.setMetadata(emptyMap())
                protocol.setMaxSamples(0)

                mutableStatus.value = "Reset: applying 100Hz / 4g preset..."
                protocol.setAccelConfig(
                    rateHz = 100,
                    accelRange = 4,
                    lowPower = false,
                    gyroRangeDps = 0,
                )

                mutableStatus.value = "Reset: applying always-record delays..."
                protocol.setDelays(alwaysStart, alwaysStop)
                protocol.commit()
            }

            // Phase 3: optional extras in independent short sessions.
            mutableStatus.value = "Reset: syncing time..."
            runCatching {
                withProtocol(device) { protocol -> protocol.syncTime() }
            }.onFailure { error ->
                Log.w(TAG, "Reset time sync failed, continuing", error)
                nonFatalWarning = "time sync unavailable"
            }

            mutableStatus.value = "Reset: enabling flash debug..."
            runCatching {
                withProtocol(device) { protocol ->
                    protocol.setDebug(3)
                    protocol.commit()
                }
            }.onFailure { error ->
                Log.w(TAG, "Reset debug setting failed, continuing", error)
                nonFatalWarning = listOfNotNull(nonFatalWarning, "flash debug unavailable").joinToString(", ")
            }

            // Phase 4: verify in a fresh session.
            withProtocol(device) { protocol ->
                mutableStatus.value = "Reset: verifying applied settings..."
                val appliedRate = protocol.rateConfig()
                val appliedStart = protocol.startTime()
                val appliedStop = protocol.stopTime()
                if (appliedRate.rateHz != 100 || appliedRate.accelRangeG != 4) {
                    throw IOException("Reset verify failed: expected 100Hz/4g, got ${appliedRate.rateHz}Hz/${appliedRate.accelRangeG}g")
                }
                if (appliedStart != alwaysStart || appliedStop != alwaysStop) {
                    throw IOException("Reset verify failed: start=$appliedStart stop=$appliedStop")
                }
                appliedSummary = "${appliedRate.rateHz}Hz/${appliedRate.accelRangeG}g, start=$appliedStart, stop=$appliedStop"
            }
        }
        delay(600L)
        refreshDevices()
        mutableStatus.value = if (nonFatalWarning == null) {
            "Reset complete and verified: $appliedSummary"
        } else {
            "Reset complete and verified: $appliedSummary (${nonFatalWarning})"
        }
    }

    suspend fun diagnose(usbKey: String) {
        withContext(Dispatchers.IO) {
            val device = requireUsbDevice(usbKey)
            mutableStatus.value = "Running AX3 diagnostics..."
            val lines = mutableListOf<String>()
            lines += "VID:PID=${"%04x".format(device.vendorId)}:${"%04x".format(device.productId)}"
            lines += "interfaces=${device.interfaceCount}"

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val endpointSummary = buildString {
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (isNotEmpty()) append(';')
                        append("ep${e}:")
                        append(endpointTypeName(ep.type))
                        append('/')
                        append(endpointDirName(ep.direction))
                    }
                }
                lines += "if${i}[c=${iface.interfaceClass},s=${iface.interfaceSubclass},p=${iface.interfaceProtocol},eps=${iface.endpointCount}:${endpointSummary}]"
            }

            val serialResult = runCatching {
                val driver = probeSerialDriver(device) ?: throw IOException("no serial driver")
                "serial=${driver.javaClass.simpleName},ports=${driver.ports.size}"
            }.getOrElse { "serial=unavailable(${it.message ?: "error"})" }
            lines += serialResult

            val storageResult = runCatching {
                val handle = openMassStorageHandleWithTimeout(device, 10_000L)
                val fileLen = handle.length
                runCatching { handle.closeHandle() }
                "storage=ok,fileBytes=$fileLen"
            }.getOrElse { "storage=unavailable(${it.message ?: "error"})" }
            lines += storageResult

            val directResult = runCatching { directStorageDiagnosticWithTimeout(device, 10_000L) }
                .getOrElse { "direct=unavailable(${it.message ?: "error"})" }
            lines += directResult

            val botResult = runCatching { bulkOnlyInquiryDiagnostic(device) }
                .getOrElse { "bot=unavailable(${it.message ?: "error"})" }
            lines += botResult

            val report = lines.joinToString(" | ")
            mutableDevices.value = mutableDevices.value.map { snapshot ->
                if (snapshot.usbKey == usbKey) {
                    snapshot.copy(warning = report)
                } else {
                    snapshot
                }
            }
            mutableStatus.value = "AX3 diagnostics captured"
        }
    }

    suspend fun download(usbKey: String, settings: AppSettings): File {
        return withContext(Dispatchers.IO) {
            val device = requireUsbDevice(usbKey)
            mutableStatus.value = "Starting download..."
            var completed = false
            var failedReason: String? = null
            var closeStorage: (() -> Unit)? = null
            var downloadedFile: File? = null
            val snapshot = if (usbManager.hasPermission(device)) {
                runCatching { readSnapshot(device) }
                    .getOrElse { error -> readFallbackSnapshot(device, error) }
            } else {
                throw IOException("USB permission missing")
            }
            val filenameValues = MetadataCodec.defaultFilenameValues(snapshot.metadata, snapshot.deviceId, snapshot.sessionId)
            val baseName = MetadataCodec.renderFilename(settings.filenameTemplate, filenameValues)
            val outputFile = uniqueDestination(settings.downloadsRoot, "$baseName.cwa")
            val partialFile = File(outputFile.absolutePath + ".part")
            partialFile.ensureParentDirectories()

            try {
                val handle = openMassStorageHandleWithTimeout(device, 20_000L)
                closeStorage = handle.closeHandle
                mutableStatus.value = "Opening AX3 storage..."
                val totalBytes = handle.length
                updateDownloadState(usbKey, true, 0)
                coroutineScope {
                    var copied = 0L
                    var lastProgressAtMs = SystemClock.elapsedRealtime()
                    val watchdog = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(2000L)
                            val idleMs = SystemClock.elapsedRealtime() - lastProgressAtMs
                            if (copied == 0L && idleMs >= 8000L) {
                                mutableStatus.value = "Downloading 0%... waiting for AX3 data"
                            }
                            if (idleMs >= 25000L) {
                                mutableStatus.value = "Download failed: AX3 read timed out"
                                closeStorage?.let { runCatching { it() } }
                                break
                            }
                        }
                    }

                    try {
                        partialFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(handle.chunkSize.coerceIn(16 * 1024, 128 * 1024))
                            var readExecutor = Executors.newSingleThreadExecutor()
                            try {
                                while (copied < totalBytes) {
                                    val requestedRead = minOf(buffer.size.toLong(), totalBytes - copied).toInt()
                                    var readLength = requestedRead
                                    var attempt = 0
                                    var read = 0
                                    while (attempt < 4) {
                                        try {
                                            val timeoutMs = (10_000L + (readLength.toLong() * 2L)).coerceAtMost(30_000L)
                                            read = readChunkWithTimeout(readExecutor, handle, copied, buffer, readLength, timeoutMs)
                                            break
                                        } catch (io: IOException) {
                                            val isTimeout =
                                                io.cause is TimeoutException ||
                                                    io.message?.contains("timed out", ignoreCase = true) == true
                                            if (!isTimeout || attempt >= 3) {
                                                throw io
                                            }
                                            attempt += 1
                                            readLength = maxOf(4096, readLength / 2)
                                            mutableStatus.value = "AX3 read stalled, retrying (${attempt}/3)..."
                                            readExecutor.shutdownNow()
                                            readExecutor = Executors.newSingleThreadExecutor()
                                        }
                                    }
                                    if (read <= 0) {
                                        throw IOException("AX3 returned no data while downloading")
                                    }
                                    output.write(buffer, 0, read)
                                    copied += read
                                    lastProgressAtMs = SystemClock.elapsedRealtime()
                                    val progress = if (totalBytes <= 0L) 100 else ((copied * 100L) / totalBytes).toInt()
                                    updateDownloadState(usbKey, true, progress)
                                    mutableStatus.value = "Downloading ${progress.coerceIn(0, 100)}%..."
                                }
                            } finally {
                                readExecutor.shutdownNow()
                            }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                if (!partialFile.renameTo(outputFile)) {
                    throw IOException("Unable to finalize download file")
                }
                completed = true
                downloadedFile = outputFile
                Log.i(TAG, "Download complete for $usbKey -> ${outputFile.absolutePath}")
                mutableStatus.value = "Downloaded ${outputFile.name}."
            } catch (error: Throwable) {
                Log.e(TAG, "Download failed for $usbKey", error)
                val reason = when {
                    error.message?.contains("direct USB", ignoreCase = true) == true ->
                        error.message ?: "AX3 direct USB storage failed"
                    error.message?.contains("No AX3 partitions found", ignoreCase = true) == true ->
                        "AX3 storage layout is not mountable on this tablet"
                    error.message?.contains("Timed out", ignoreCase = true) == true ->
                        error.message ?: "USB operation timed out"
                    else -> error.message?.takeIf { it.isNotBlank() } ?: "unknown error"
                }
                failedReason = reason
                mutableStatus.value = "Download failed: $reason"
                runCatching { partialFile.delete() }
            } finally {
                updateDownloadState(usbKey, false, if (completed) 100 else 0)
                closeStorage?.let { runCatching { it() } }
            }
            failedReason?.let { reason ->
                mutableDevices.value = mutableDevices.value.map { snapshotEntry ->
                    if (snapshotEntry.usbKey == usbKey) {
                        snapshotEntry.copy(warning = "Download failed: $reason. Call 020 7594 6950")
                    } else {
                        snapshotEntry
                    }
                }
                throw IOException("$reason. Call 020 7594 6950")
            }
            refreshDevices()
            refreshFiles(settings)
            return@withContext downloadedFile ?: throw IOException("Download completed without an output file")
        }
    }

    private fun candidateDevices(): List<UsbDevice> =
        usbManager.deviceList.values
            .filter { it.vendorId == AX3_VENDOR_ID && it.productId == AX3_PRODUCT_ID }

    private fun requireUsbDevice(usbKey: String): UsbDevice =
        findUsbDevice(usbKey) ?: throw IOException("USB device $usbKey is no longer connected")

    private suspend fun readSnapshot(device: UsbDevice): Ax3DeviceSnapshot = withProtocol(device) { protocol ->
        val identity = protocol.identity()
        val metadata = protocol.metadata()
        val battery = protocol.batteryLevel()
        val batteryHealth = protocol.batteryHealth()
        val memoryHealth = protocol.memoryHealth()
        val deviceTime = protocol.time()
        val startTime = protocol.startTime()
        val stopTime = protocol.stopTime()
        val rateConfig = protocol.rateConfig()
        val dataBytes = readRecordedDataLength(device, identity.sessionId.toLong())
        Ax3DeviceSnapshot(
            usbKey = usbKey(device),
            usbDeviceId = device.deviceId,
            usbName = device.productName ?: identity.type,
            hasPermission = true,
            serialId = "${identity.type}${identity.hardwareVersion}_${"%05d".format(identity.deviceId)}",
            deviceType = identity.type,
            deviceId = identity.deviceId.toLong(),
            sessionId = identity.sessionId.toLong(),
            firmwareVersion = identity.firmwareVersion,
            hardwareVersion = identity.hardwareVersion,
            batteryLevel = battery,
            batteryHealth = batteryHealth,
            memoryHealth = memoryHealth,
            deviceTime = deviceTime,
            startTime = startTime,
            stopTime = stopTime,
            sampleRateHz = rateConfig.rateHz,
            accelRangeG = rateConfig.accelRangeG,
            gyroRangeDps = rateConfig.gyroRangeDps,
            metadata = MetadataCodec.defaultFilenameValues(metadata, identity.deviceId.toLong(), identity.sessionId.toLong()),
            dataBytes = dataBytes,
            hasData = (dataBytes ?: 0L) > 1024L,
            recordingState = AxivityDateTime.recordingState(startTime, stopTime),
            warning = deviceWarning(deviceTime, battery),
        )
    }

    private fun readFallbackSnapshot(device: UsbDevice, error: Throwable): Ax3DeviceSnapshot {
        return Ax3DeviceSnapshot(
            usbKey = usbKey(device),
            usbDeviceId = device.deviceId,
            usbName = device.productName ?: "AX3 USB device",
            hasPermission = true,
            warning = friendlyControlWarning(error),
        )
    }

    private fun friendlyControlWarning(error: Throwable): String {
        val message = error.message ?: return "Unable to query AX3 control interface"
        return when {
            message.contains("No control endpoint", ignoreCase = true) ->
                "AX3 control interface unavailable on this tablet. Download via mass storage is still available."
            message.contains("No serial driver", ignoreCase = true) ->
                "AX3 control interface driver unavailable on this device. Download via mass storage is still available."
            else -> message
        }
    }

    private fun readRecordedDataLength(device: UsbDevice, sessionId: Long): Long? {
        val handle = runCatching { openMassStorageHandleWithTimeout(device, 6_000L) }.getOrNull() ?: return null
        return try {
            estimateRecordedBytes(handle, sessionId)
        } finally {
            runCatching { handle.closeHandle() }
        }
    }

    private fun estimateRecordedBytes(handle: MassStorageHandle, sessionId: Long): Long {
        val totalSectors = handle.length / CWA_SECTOR_SIZE
        if (totalSectors <= CWA_DATA_START_SECTOR) {
            return 0L
        }
        if (!isValidDataSector(handle, CWA_DATA_START_SECTOR, sessionId)) {
            return 0L
        }
        var left = CWA_DATA_START_SECTOR
        var right = totalSectors - 1
        var best = CWA_DATA_START_SECTOR
        while (left <= right) {
            val mid = left + (right - left) / 2
            if (isValidDataSector(handle, mid, sessionId)) {
                best = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }
        return ((best + 1) * CWA_SECTOR_SIZE).coerceAtMost(handle.length)
    }

    private fun isValidDataSector(handle: MassStorageHandle, sectorIndex: Long, sessionId: Long): Boolean {
        if (sectorIndex < CWA_DATA_START_SECTOR) {
            return false
        }
        val buffer = ByteArray(CWA_SECTOR_SIZE)
        val read = runCatching { handle.readAt(sectorIndex * CWA_SECTOR_SIZE, buffer, buffer.size) }.getOrDefault(-1)
        if (read != buffer.size) {
            return false
        }
        if (u16le(buffer, 0) != CWA_DATA_HEADER_MAGIC) {
            return false
        }
        val sectorSession = u32le(buffer, 6)
        if (sessionId != 0L && sectorSession != sessionId) {
            return false
        }
        val timestamp = u32le(buffer, 14)
        return AxivityDateTime.unpack(timestamp) != null
    }

    private fun u16le(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xff) or ((buffer[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(buffer: ByteArray, offset: Int): Long =
        (buffer[offset].toLong() and 0xff) or
            ((buffer[offset + 1].toLong() and 0xff) shl 8) or
            ((buffer[offset + 2].toLong() and 0xff) shl 16) or
            ((buffer[offset + 3].toLong() and 0xff) shl 24)

    private fun openMassStorageHandleWithTimeout(device: UsbDevice, timeoutMs: Long): MassStorageHandle {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<MassStorageHandle> { openMassStorageHandle(device) }
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            executor.shutdownNow()
            throw IOException("AX3 mass storage probe timed out after ${timeoutMs}ms")
        } catch (execution: ExecutionException) {
            val cause = execution.cause
            when (cause) {
                is IOException -> throw cause
                null -> throw IOException("AX3 mass storage open failed")
                else -> throw IOException(cause.message ?: "AX3 mass storage open failed", cause)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun openMassStorageHandle(device: UsbDevice): MassStorageHandle {
        Log.d(TAG, "openMassStorageHandle: probing device ${device.deviceId}")
        val massStorageDevices = device.getMassStorageDevices(appContext)
        var sawPartitionTable = false
        var lastError: Throwable? = null

        for (storage in massStorageDevices) {
            try {
                Log.d(TAG, "openMassStorageHandle: trying libaums storage wrapper")
                storage.init()
                val partitions = storage.partitions
                Log.d(TAG, "openMassStorageHandle: partition count=${partitions.size}")
                if (partitions.isNotEmpty()) {
                    sawPartitionTable = true
                }
                for (partition in partitions) {
                    val usbFile = partition.fileSystem.rootDirectory.search("CWA-DATA.CWA")
                    if (usbFile != null) {
                        Log.d(TAG, "openMassStorageHandle: found CWA-DATA.CWA via libaums filesystem")
                        return MassStorageHandle(
                            length = usbFile.length,
                            chunkSize = partition.fileSystem.chunkSize,
                            readAt = { offset, target, length ->
                                val buffer = ByteBuffer.wrap(target, 0, length)
                                usbFile.read(offset, buffer)
                                buffer.position()
                            },
                            closeHandle = { storage.close() },
                        )
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "openMassStorageHandle: libaums path failed", error)
                lastError = error
            }
            runCatching { storage.close() }
        }

        val directHandle = runCatching { openDirectUsbStorageHandle(device) }
            .onFailure {
                Log.e(TAG, "openMassStorageHandle: direct USB path failed", it)
                lastError = it
            }
            .getOrNull()
        if (directHandle != null) {
            Log.d(TAG, "openMassStorageHandle: direct USB path succeeded")
            return directHandle
        }

        if (massStorageDevices.isEmpty()) {
            val reason = lastError?.message?.takeIf { it.isNotBlank() }
            if (reason != null) {
                throw IOException("AX3 mass storage interface not available ($reason)")
            }
            throw IOException("AX3 mass storage interface not available")
        }

        if (!sawPartitionTable) {
            val reason = lastError?.message?.takeIf { it.isNotBlank() }
            if (reason != null) {
                throw IOException("No AX3 partitions found ($reason)")
            }
            throw IOException("No AX3 partitions found")
        }

        throw IOException(lastError?.message ?: "CWA-DATA.CWA not found on AX3 storage")
    }

    private fun openDirectUsbStorageHandle(device: UsbDevice): MassStorageHandle {
        Log.d(TAG, "openDirectUsbStorageHandle: selecting mass-storage transport")
        val transport = findMassStorageTransport(device)
            ?: throw IOException("AX3 direct mass storage transport not available")
        Log.d(
            TAG,
            "openDirectUsbStorageHandle: interface=${transport.usbInterface.id}, inEp=${transport.inEndpoint.address}, outEp=${transport.outEndpoint.address}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IOException("AX3 direct USB openDevice failed")
        try {
            if (!connection.claimInterface(transport.usbInterface, true)) {
                throw IOException("AX3 direct USB claimInterface failed")
            }
            val fatVolume = runUsbStageWithTimeout("direct-fat-open", 8_000L) {
                openRawFatVolume(connection, transport)
            }
            val directoryEntry = runUsbStageWithTimeout("direct-root-search", 4_000L) {
                fatVolume.findRootFile("CWA-DATA", "CWA")
            } ?: throw IOException("CWA-DATA.CWA not found on AX3 FAT volume")
            val clusterChain = runUsbStageWithTimeout("direct-cluster-chain", 8_000L) {
                fatVolume.buildClusterChain(directoryEntry.startCluster, directoryEntry.sizeBytes)
            }
            val fileReader = RawFatFileReader(fatVolume, directoryEntry, clusterChain)
            Log.d(TAG, "openDirectUsbStorageHandle: found CWA-DATA.CWA on raw FAT volume size=${directoryEntry.sizeBytes}")
            return MassStorageHandle(
                length = directoryEntry.sizeBytes,
                chunkSize = fatVolume.preferredChunkSize,
                readAt = { offset, target, length -> fileReader.readAt(offset, target, length) },
                closeHandle = {
                    runCatching { connection.releaseInterface(transport.usbInterface) }
                    runCatching { connection.close() }
                },
            )
        } catch (error: Throwable) {
            runCatching { connection.releaseInterface(transport.usbInterface) }
            runCatching { connection.close() }
            throw when (error) {
                is IOException -> error
                else -> IOException(error.message ?: "AX3 direct USB storage open failed", error)
            }
        }
    }

    private fun directStorageDiagnosticWithTimeout(device: UsbDevice, timeoutMs: Long): String {
        return directStorageDiagnostic(device, timeoutMs)
    }

    private fun directStorageDiagnostic(device: UsbDevice, timeoutMs: Long): String {
        val transport = findMassStorageTransport(device)
            ?: return "direct=unavailable(no bulk transport)"
        Log.d(TAG, "directStorageDiagnostic: start for device ${device.deviceId}")
        val communication = try {
            runUsbStageWithTimeout("diag-comm-setup", minOf(timeoutMs, 4_000L)) {
                UsbCommunicationFactory.createUsbCommunication(
                    usbManager,
                    device,
                    transport.usbInterface,
                    transport.inEndpoint,
                    transport.outEndpoint,
                )
            }
        } catch (error: Throwable) {
            Log.e(TAG, "directStorageDiagnostic: communication setup failed", error)
            return "direct=failed(comm:${error.message ?: error.javaClass.simpleName})"
        }
        try {
            val blockDevice = ScsiBlockDevice(communication, 0)
            Log.d(TAG, "directStorageDiagnostic: calling ScsiBlockDevice.init()")
            runUsbStageWithTimeout("diag-scsi-init", minOf(timeoutMs, 6_000L)) {
                blockDevice.init()
            }
            val blocks = blockDevice.blocks
            val blockSize = blockDevice.blockSize
            if (blocks <= 0L) {
                Log.d(TAG, "directStorageDiagnostic: zero blocks returned")
                return "direct=scsi(blocks=0,blockSize=$blockSize)"
            }
            val entry = PartitionTableEntry(0x0b, 0L, blocks)
            Log.d(TAG, "directStorageDiagnostic: creating filesystem")
            val fileSystem = runUsbStageWithTimeout("diag-filesystem-open", minOf(timeoutMs, 4_000L)) {
                FileSystemFactory.createFileSystem(entry, blockDevice)
            }
            val names = runCatching {
                runUsbStageWithTimeout("diag-root-list", minOf(timeoutMs, 4_000L)) {
                    fileSystem.rootDirectory.list().take(6).joinToString(",")
                }
            }
                .getOrElse { "list-failed:${it.message ?: "error"}" }
            Log.d(TAG, "directStorageDiagnostic: filesystem ok root=$names")
            return "direct=scsi(blocks=$blocks,blockSize=$blockSize,root=$names)"
        } catch (error: Throwable) {
            Log.e(TAG, "directStorageDiagnostic: failed", error)
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            return "direct=failed($message)"
        } finally {
            runCatching { communication.close() }
        }
    }

    private fun bulkOnlyInquiryDiagnostic(device: UsbDevice): String {
        val transport = findMassStorageTransport(device)
            ?: return "bot=unavailable(no bulk transport)"
        val connection = usbManager.openDevice(device)
            ?: return "bot=failed(openDevice)"
        try {
            if (!connection.claimInterface(transport.usbInterface, true)) {
                return "bot=failed(claimInterface)"
            }
            var nextTag = 0x4f4d4158
            val inquiry = sendBotCommand(
                connection = connection,
                transport = transport,
                tag = nextTag++,
                cdb = byteArrayOf(0x12, 0, 0, 0, 36, 0),
                dataTransferLength = 36,
                directionIn = true,
                timeoutMs = 2000,
                stage = "inquiry",
            )
            val vendor = asciiSlice(inquiry.data, 8, 8)
            val product = asciiSlice(inquiry.data, 16, 16)

            val tur = sendBotCommand(
                connection = connection,
                transport = transport,
                tag = nextTag++,
                cdb = byteArrayOf(0x00, 0, 0, 0, 0, 0),
                dataTransferLength = 0,
                directionIn = false,
                timeoutMs = 1500,
                stage = "tur",
            )

            val sense = sendBotCommand(
                connection = connection,
                transport = transport,
                tag = nextTag++,
                cdb = byteArrayOf(0x03, 0, 0, 0, 18, 0),
                dataTransferLength = 18,
                directionIn = true,
                timeoutMs = 2000,
                stage = "sense",
            )
            val senseKey = if (sense.data.size > 2) sense.data[2].toInt() and 0x0f else -1
            val asc = if (sense.data.size > 12) sense.data[12].toInt() and 0xff else -1
            val ascq = if (sense.data.size > 13) sense.data[13].toInt() and 0xff else -1

            val capacity = sendBotCommand(
                connection = connection,
                transport = transport,
                tag = nextTag++,
                cdb = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                dataTransferLength = 8,
                directionIn = true,
                timeoutMs = 2500,
                stage = "read-capacity",
            )
            val capacityBuffer = ByteBuffer.wrap(capacity.data).order(ByteOrder.BIG_ENDIAN)
            val lastLba = capacityBuffer.int.toLong() and 0xffffffffL
            val blockSize = capacityBuffer.int

            val sector0 = sendBotCommand(
                connection = connection,
                transport = transport,
                tag = nextTag,
                cdb = byteArrayOf(0x28, 0, 0, 0, 0, 0, 0, 0, 1, 0),
                dataTransferLength = blockSize,
                directionIn = true,
                timeoutMs = 3000,
                stage = "read10-lba0",
            )
            val oem = asciiSlice(sector0.data, 3, 8)
            val signature = if (sector0.data.size >= 512) {
                String.format("%02x%02x", sector0.data[510].toInt() and 0xff, sector0.data[511].toInt() and 0xff)
            } else {
                "short"
            }

            return buildString {
                append("bot=inquiry(status=${inquiry.status},residue=${inquiry.residue},vendor=$vendor,product=$product)")
                append(",tur(status=${tur.status})")
                append(",sense(key=${senseKey.toString(16)},asc=${asc.toString(16)},ascq=${ascq.toString(16)})")
                append(",capacity(lastLba=$lastLba,blockSize=$blockSize,status=${capacity.status})")
                append(",boot(oem=$oem,sig=$signature,status=${sector0.status})")
            }
        } catch (error: Throwable) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            return "bot=failed($message)"
        } finally {
            runCatching { connection.releaseInterface(transport.usbInterface) }
            runCatching { connection.close() }
        }
    }

    private fun sendBotCommand(
        connection: UsbDeviceConnection,
        transport: MassStorageTransport,
        tag: Int,
        cdb: ByteArray,
        dataTransferLength: Int,
        directionIn: Boolean,
        timeoutMs: Int,
        stage: String,
    ): BotCommandResult {
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355)
            putInt(tag)
            putInt(dataTransferLength)
            put(if (directionIn) 0x80.toByte() else 0x00.toByte())
            put(0)
            put(cdb.size.toByte())
            put(cdb)
            repeat(16 - cdb.size) { put(0) }
        }.array()

        bulkTransferOrThrow(connection, transport.outEndpoint, cbw, cbw.size, timeoutMs, "$stage-cbw")

        val data = when {
            dataTransferLength <= 0 -> ByteArray(0)
            directionIn -> {
                val response = ByteArray(dataTransferLength)
                val read = bulkTransferOrThrow(
                    connection,
                    transport.inEndpoint,
                    response,
                    response.size,
                    timeoutMs,
                    "$stage-data-in",
                )
                if (read < response.size) {
                    throw IOException("bulk-$stage short-data:$read/${response.size}")
                }
                response
            }
            else -> throw IOException("bulk-$stage data-out unsupported")
        }

        val csw = ByteArray(13)
        val cswRead = bulkTransferOrThrow(connection, transport.inEndpoint, csw, csw.size, timeoutMs, "$stage-csw")
        if (cswRead < csw.size) {
            throw IOException("bulk-$stage short-csw:$cswRead/${csw.size}")
        }
        val cswBuffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val signature = cswBuffer.int
        val responseTag = cswBuffer.int
        val residue = cswBuffer.int
        val status = cswBuffer.get().toInt() and 0xff
        if (signature != 0x53425355) {
            throw IOException("bulk-$stage bad-csw-signature:${signature.toUInt().toString(16)}")
        }
        if (responseTag != tag) {
            throw IOException("bulk-$stage tag-mismatch:${responseTag.toUInt().toString(16)}")
        }
        return BotCommandResult(data = data, residue = residue, status = status)
    }

    private fun bulkTransferOrThrow(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int,
        stage: String,
    ): Int {
        val transferred = connection.bulkTransfer(endpoint, buffer, length, timeoutMs)
        if (transferred < 0) {
            throw IOException("bulk-$stage timeout/error")
        }
        return transferred
    }

    private fun asciiSlice(buffer: ByteArray, offset: Int, length: Int): String =
        buildString(length) {
            for (index in offset until (offset + length).coerceAtMost(buffer.size)) {
                val value = buffer[index].toInt() and 0xff
                append(if (value in 32..126) value.toChar() else ' ')
            }
        }.trim()

    private fun openRawFatVolume(
        connection: UsbDeviceConnection,
        transport: MassStorageTransport,
    ): RawFatVolume {
        val capacity = sendBotCommand(
            connection = connection,
            transport = transport,
            tag = nextBotTag(),
            cdb = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            dataTransferLength = 8,
            directionIn = true,
            timeoutMs = 2500,
            stage = "fat-read-capacity",
        )
        val capacityBuffer = ByteBuffer.wrap(capacity.data).order(ByteOrder.BIG_ENDIAN)
        val totalBlocks = (capacityBuffer.int.toLong() and 0xffffffffL) + 1L
        val blockSize = capacityBuffer.int
        val sector0 = readSectors(connection, transport, 0L, 1, blockSize, 3000, "fat-sector0")
        val partitionStartLba = when {
            looksLikeFatBootSector(sector0) -> 0L
            else -> findFatPartitionStart(connection, transport, sector0, blockSize)
                ?: throw IOException("AX3 FAT partition not found")
        }
        val bootSector = if (partitionStartLba == 0L) {
            sector0
        } else {
            readSectors(connection, transport, partitionStartLba, 1, blockSize, 3000, "fat-boot")
        }

        val bytesPerSector = le16(bootSector, 11)
        val sectorsPerCluster = u8(bootSector, 13)
        val reservedSectors = le16(bootSector, 14)
        val fatCount = u8(bootSector, 16)
        val rootEntryCount = le16(bootSector, 17)
        val totalSectors16 = le16(bootSector, 19)
        val fatSize16 = le16(bootSector, 22)
        val totalSectors32 = le32(bootSector, 32)
        val fatSize32 = le32(bootSector, 36)
        val rootCluster = le32(bootSector, 44).toLong() and 0xffffffffL

        if (bytesPerSector <= 0 || sectorsPerCluster <= 0 || fatCount <= 0) {
            throw IOException("AX3 FAT boot sector invalid")
        }

        val totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32.toLong() and 0xffffffffL
        val fatSizeSectors = if (fatSize16 != 0) fatSize16.toLong() else fatSize32.toLong() and 0xffffffffL
        val rootDirSectors = ((rootEntryCount * 32) + (bytesPerSector - 1)) / bytesPerSector
        val firstDataSector = partitionStartLba + reservedSectors.toLong() + fatCount.toLong() * fatSizeSectors + rootDirSectors.toLong()
        val dataSectors = totalSectors - (reservedSectors.toLong() + fatCount.toLong() * fatSizeSectors + rootDirSectors.toLong())
        val clusterCount = dataSectors / sectorsPerCluster.toLong()
        val fatType = when {
            clusterCount < 4085L -> throw IOException("AX3 FAT12 volumes are unsupported")
            clusterCount < 65525L -> FatType.FAT16
            else -> FatType.FAT32
        }

        return RawFatVolume(
            connection = connection,
            transport = transport,
            blockSize = blockSize,
            totalBlocks = totalBlocks,
            partitionStartLba = partitionStartLba,
            bytesPerSector = bytesPerSector,
            sectorsPerCluster = sectorsPerCluster,
            reservedSectors = reservedSectors,
            fatCount = fatCount,
            fatSizeSectors = fatSizeSectors,
            rootDirSectors = rootDirSectors,
            firstDataSector = firstDataSector,
            rootCluster = if (fatType == FatType.FAT32) rootCluster else 0L,
            fatType = fatType,
            clusterSizeBytes = bytesPerSector * sectorsPerCluster,
        )
    }

    private fun looksLikeFatBootSector(sector: ByteArray): Boolean {
        if (sector.size < 64) {
            return false
        }
        val signatureOk = u8(sector, 510) == 0x55 && u8(sector, 511) == 0xaa
        val bytesPerSector = le16(sector, 11)
        val sectorsPerCluster = u8(sector, 13)
        val reservedSectors = le16(sector, 14)
        val fatCount = u8(sector, 16)
        val media = u8(sector, 21)
        return signatureOk &&
            bytesPerSector in setOf(512, 1024, 2048, 4096) &&
            sectorsPerCluster in setOf(1, 2, 4, 8, 16, 32, 64, 128) &&
            reservedSectors > 0 &&
            fatCount in 1..4 &&
            media != 0
    }

    private fun findFatPartitionStart(
        connection: UsbDeviceConnection,
        transport: MassStorageTransport,
        sector0: ByteArray,
        blockSize: Int,
    ): Long? {
        for (index in 0 until 4) {
            val offset = 446 + index * 16
            val partitionType = u8(sector0, offset + 4)
            if (partitionType == 0x00 || partitionType !in setOf(0x04, 0x06, 0x0b, 0x0c, 0x0e, 0x1b, 0x1c, 0x1e)) {
                continue
            }
            val startLba = le32(sector0, offset + 8).toLong() and 0xffffffffL
            if (startLba <= 0L) {
                continue
            }
            val bootSector = readSectors(connection, transport, startLba, 1, blockSize, 3000, "fat-partition-$index")
            if (looksLikeFatBootSector(bootSector)) {
                return startLba
            }
        }
        return null
    }

    private fun readSectors(
        connection: UsbDeviceConnection,
        transport: MassStorageTransport,
        startLba: Long,
        sectorCount: Int,
        blockSize: Int,
        timeoutMs: Int,
        stage: String,
    ): ByteArray {
        if (sectorCount <= 0) {
            return ByteArray(0)
        }
        // Android usbfs limits bulk transfers to ~16 KB per ioctl; the AX3 PIC firmware
        // also stalls if asked for too many sectors at once. Cap each BOT READ10 to
        // MAX_SECTORS_PER_BOT_READ sectors and issue multiple commands if needed.
        val maxSectors = MAX_SECTORS_PER_BOT_READ
        if (sectorCount <= maxSectors) {
            return readSectorsDirect(connection, transport, startLba, sectorCount, blockSize, timeoutMs, stage)
        }
        val result = ByteArray(sectorCount * blockSize)
        var written = 0
        var remaining = sectorCount
        var lba = startLba
        while (remaining > 0) {
            val batch = minOf(remaining, maxSectors)
            val chunk = readSectorsDirect(connection, transport, lba, batch, blockSize, timeoutMs, "$stage@$lba")
            System.arraycopy(chunk, 0, result, written, chunk.size)
            written += chunk.size
            remaining -= batch
            lba += batch.toLong()
        }
        return result
    }

    private fun readSectorsDirect(
        connection: UsbDeviceConnection,
        transport: MassStorageTransport,
        startLba: Long,
        sectorCount: Int,
        blockSize: Int,
        timeoutMs: Int,
        stage: String,
    ): ByteArray {
        val cdb = byteArrayOf(
            0x28,
            0,
            ((startLba shr 24) and 0xff).toByte(),
            ((startLba shr 16) and 0xff).toByte(),
            ((startLba shr 8) and 0xff).toByte(),
            (startLba and 0xff).toByte(),
            0,
            ((sectorCount shr 8) and 0xff).toByte(),
            (sectorCount and 0xff).toByte(),
            0,
        )
        return sendBotCommand(
            connection = connection,
            transport = transport,
            tag = nextBotTag(),
            cdb = cdb,
            dataTransferLength = sectorCount * blockSize,
            directionIn = true,
            timeoutMs = timeoutMs,
            stage = stage,
        ).data
    }

    private fun le16(buffer: ByteArray, offset: Int): Int =
        u8(buffer, offset) or (u8(buffer, offset + 1) shl 8)

    private fun le32(buffer: ByteArray, offset: Int): Int =
        u8(buffer, offset) or
            (u8(buffer, offset + 1) shl 8) or
            (u8(buffer, offset + 2) shl 16) or
            (u8(buffer, offset + 3) shl 24)

    private fun u8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt() and 0xff

    private fun nextBotTag(): Int {
        botTagCounter += 1
        if (botTagCounter == 0 || botTagCounter == Int.MIN_VALUE) {
            botTagCounter = 1
        }
        return botTagCounter
    }

    private fun <T> runUsbStageWithTimeout(stage: String, timeoutMs: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit(Callable<T> { block() })
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            throw IOException("AX3 direct USB stage '$stage' timed out after ${timeoutMs}ms", timeout)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("AX3 direct USB stage '$stage' interrupted", interrupted)
        } catch (execution: ExecutionException) {
            val cause = execution.cause
            when (cause) {
                is IOException -> throw cause
                null -> throw IOException("AX3 direct USB stage '$stage' failed")
                else -> throw IOException(cause.message ?: "AX3 direct USB stage '$stage' failed", cause)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun findMassStorageTransport(device: UsbDevice): MassStorageTransport? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) {
                continue
            }
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue
                }
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> inEndpoint = endpoint
                    UsbConstants.USB_DIR_OUT -> outEndpoint = endpoint
                }
            }
            if (inEndpoint != null && outEndpoint != null) {
                return MassStorageTransport(usbInterface, inEndpoint, outEndpoint)
            }
        }
        return null
    }

    private fun readChunkWithTimeout(
        executor: ExecutorService,
        handle: MassStorageHandle,
        offset: Long,
        target: ByteArray,
        length: Int,
        timeoutMs: Long,
    ): Int {
        val future = executor.submit<Int> {
            handle.readAt(offset, target, length)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw IOException("AX3 read timed out after ${timeoutMs}ms", timeout)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("AX3 read interrupted", interrupted)
        } catch (execution: ExecutionException) {
            val cause = execution.cause
            when (cause) {
                is IOException -> throw cause
                null -> throw IOException("AX3 read failed")
                else -> throw IOException(cause.message ?: "AX3 read failed", cause)
            }
        }
    }

    private fun endpointTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
        else -> "unknown"
    }

    private fun endpointDirName(direction: Int): String = when (direction) {
        UsbConstants.USB_DIR_IN -> "in"
        UsbConstants.USB_DIR_OUT -> "out"
        else -> "?"
    }

    private fun deviceWarning(deviceTime: LocalDateTime?, batteryPercent: Int?): String? {
        if (deviceTime == null || batteryPercent == null) {
            return null
        }
        val now = LocalDateTime.now()
        val daysBehind = ChronoUnit.DAYS.between(deviceTime, now)
        return when {
            daysBehind > 3650 && batteryPercent >= 70 -> "Clock reset while battery appears charged."
            daysBehind > 3650 -> "Device clock appears reset."
            else -> null
        }
    }

    private fun updateDownloadState(usbKey: String, downloading: Boolean, progress: Int) {
        mutableDevices.value = mutableDevices.value.map { snapshot ->
            if (snapshot.usbKey == usbKey) {
                snapshot.copy(isDownloading = downloading, downloadProgress = progress)
            } else {
                snapshot
            }
        }
    }

    private fun uniqueDestination(directory: File, filename: String): File {
        var candidate = File(directory, filename)
        if (!candidate.exists()) {
            return candidate
        }
        val stem = filename.substringBeforeLast('.')
        val ext = filename.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, if (ext.isBlank()) "${stem}_$index" else "${stem}_$index.$ext")
            index++
        }
        return candidate
    }

    private suspend fun <T> withProtocol(device: UsbDevice, block: (Ax3Protocol) -> T): T =
        withContext(Dispatchers.IO) {
            val cdcTransport = findDirectCdcTransport(device)
            if (cdcTransport != null) {
                val connection = usbManager.openDevice(device) ?: throw IOException("Unable to open USB device")
                try {
                    if (!connection.claimInterface(cdcTransport.usbInterface, true)) {
                        throw IOException("Unable to claim AX3 CDC interface")
                    }
                    val serialPort = DirectCdcPort(connection, cdcTransport.inEndpoint, cdcTransport.outEndpoint)
                    val protocol = Ax3Protocol(serialPort)
                    protocol.primeConnection()
                    block(protocol)
                } finally {
                    runCatching { connection.releaseInterface(cdcTransport.usbInterface) }
                    runCatching { connection.close() }
                }
            } else {
                val driver = probeSerialDriver(device)
                    ?: throw IOException("No serial driver found for AX3 control interface")
                val usbSerialPort = driver.ports.firstOrNull() ?: throw IOException("No serial ports exposed by AX3")
                val connection = usbManager.openDevice(device) ?: throw IOException("Unable to open USB device")
                usbSerialPort.open(connection)
                runCatching {
                    usbSerialPort.setParameters(19200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                }.recoverCatching {
                    usbSerialPort.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                }.getOrThrow()
                try {
                    val protocol = Ax3Protocol(LibUsbSerialPort(usbSerialPort))
                    protocol.primeConnection()
                    block(protocol)
                } finally {
                    runCatching { usbSerialPort.close() }
                }
            }
        }

    private fun probeSerialDriver(device: UsbDevice) =
        ax3SerialProber.probeDevice(device) ?: UsbSerialProber.getDefaultProber().probeDevice(device)

    private fun findDirectCdcTransport(device: UsbDevice): CdcTransport? {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) continue
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (epIndex in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(epIndex)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> inEp = ep
                    UsbConstants.USB_DIR_OUT -> outEp = ep
                }
            }
            if (inEp != null && outEp != null) {
                return CdcTransport(iface, inEp, outEp)
            }
        }
        return null
    }

    private fun usbKey(device: UsbDevice): String = device.deviceId.toString()

    private data class Identity(
        val type: String,
        val hardwareVersion: Int,
        val firmwareVersion: Int,
        val deviceId: Int,
        val sessionId: Int,
    )

    private data class RateConfig(
        val rateHz: Int,
        val accelRangeG: Int,
        val gyroRangeDps: Int?,
    )

    private data class CdcTransport(
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
    )

    private data class MassStorageHandle(
        val length: Long,
        val chunkSize: Int,
        val readAt: (offset: Long, target: ByteArray, length: Int) -> Int,
        val closeHandle: () -> Unit,
    )

    private enum class FatType {
        FAT16,
        FAT32,
    }

    private data class FatDirectoryEntry(
        val startCluster: Long,
        val sizeBytes: Long,
    )

    private inner class RawFatVolume(
        private val connection: UsbDeviceConnection,
        private val transport: MassStorageTransport,
        val blockSize: Int,
        val totalBlocks: Long,
        val partitionStartLba: Long,
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val reservedSectors: Int,
        val fatCount: Int,
        val fatSizeSectors: Long,
        val rootDirSectors: Int,
        val firstDataSector: Long,
        val rootCluster: Long,
        val fatType: FatType,
        val clusterSizeBytes: Int,
    ) {
        private val fatSectorCache = mutableMapOf<Long, ByteArray>()
        val preferredChunkSize: Int = (clusterSizeBytes * 8).coerceIn(16 * 1024, 128 * 1024)

        fun findRootFile(name: String, ext: String): FatDirectoryEntry? {
            return when (fatType) {
                FatType.FAT16 -> findInDirectoryBuffer(
                    readSectors(
                        connection,
                        transport,
                        partitionStartLba + reservedSectors.toLong() + fatCount.toLong() * fatSizeSectors,
                        rootDirSectors,
                        blockSize,
                        4000,
                        "fat-root16",
                    ),
                    name,
                    ext,
                )

                FatType.FAT32 -> {
                    val rootChain = buildClusterChain(rootCluster, Long.MAX_VALUE, stopAtZeroEntry = true)
                    for (cluster in rootChain) {
                        val entry = findInDirectoryBuffer(readCluster(cluster), name, ext)
                        if (entry != null) {
                            return entry
                        }
                    }
                    null
                }
            }
        }

        fun buildClusterChain(startCluster: Long, sizeBytes: Long, stopAtZeroEntry: Boolean = false): List<Long> {
            if (startCluster < 2) {
                return emptyList()
            }
            val maxClusters = if (sizeBytes == Long.MAX_VALUE) Int.MAX_VALUE else
                ((sizeBytes + clusterSizeBytes - 1) / clusterSizeBytes).coerceAtLeast(1).toInt()
            val chain = ArrayList<Long>()
            var cluster = startCluster
            while (cluster >= 2 && !isEndOfClusterChain(cluster) && chain.size < maxClusters) {
                chain += cluster
                val next = readFatEntry(cluster)
                if (next == cluster) {
                    break
                }
                if (stopAtZeroEntry && isEndOfClusterChain(next)) {
                    break
                }
                cluster = next
            }
            return chain
        }

        fun readCluster(cluster: Long): ByteArray =
            readSectors(
                connection,
                transport,
                firstSectorOfCluster(cluster),
                sectorsPerCluster,
                blockSize,
                4000,
                "fat-cluster-$cluster",
            )

        fun readClusters(startCluster: Long, clusterCount: Int): ByteArray =
            readSectors(
                connection,
                transport,
                firstSectorOfCluster(startCluster),
                sectorsPerCluster * clusterCount,
                blockSize,
                5000,
                "fat-clusters-$startCluster-$clusterCount",
            )

        private fun firstSectorOfCluster(cluster: Long): Long =
            firstDataSector + ((cluster - 2L) * sectorsPerCluster.toLong())

        private fun readFatEntry(cluster: Long): Long {
            val entryOffset = when (fatType) {
                FatType.FAT16 -> cluster * 2L
                FatType.FAT32 -> cluster * 4L
            }
            val fatSector = partitionStartLba + reservedSectors.toLong() + (entryOffset / bytesPerSector)
            val sectorOffset = (entryOffset % bytesPerSector).toInt()
            val sector = fatSectorCache.getOrPut(fatSector) {
                readSectors(connection, transport, fatSector, 1, blockSize, 2500, "fat-table-$fatSector")
            }
            return when (fatType) {
                FatType.FAT16 -> le16(sector, sectorOffset).toLong()
                FatType.FAT32 -> le32(sector, sectorOffset).toLong() and 0x0fffffffL
            }
        }

        private fun isEndOfClusterChain(cluster: Long): Boolean = when (fatType) {
            FatType.FAT16 -> cluster >= 0xfff8L
            FatType.FAT32 -> cluster >= 0x0ffffff8L
        }

        private fun findInDirectoryBuffer(buffer: ByteArray, name: String, ext: String): FatDirectoryEntry? {
            val shortName = name.padEnd(8, ' ').take(8)
            val shortExt = ext.padEnd(3, ' ').take(3)
            var offset = 0
            while (offset + 32 <= buffer.size) {
                val firstByte = u8(buffer, offset)
                if (firstByte == 0x00) {
                    return null
                }
                val attr = u8(buffer, offset + 11)
                if (firstByte != 0xe5 && attr != 0x0f && (attr and 0x08) == 0) {
                    val entryName = String(buffer, offset, 8, Charsets.US_ASCII)
                    val entryExt = String(buffer, offset + 8, 3, Charsets.US_ASCII)
                    if (entryName == shortName && entryExt == shortExt && (attr and 0x10) == 0) {
                        val high = le16(buffer, offset + 20)
                        val low = le16(buffer, offset + 26)
                        val cluster = ((high shl 16) or low).toLong() and 0xffffffffL
                        val size = le32(buffer, offset + 28).toLong() and 0xffffffffL
                        return FatDirectoryEntry(cluster, size)
                    }
                }
                offset += 32
            }
            return null
        }
    }

    private inner class RawFatFileReader(
        private val volume: RawFatVolume,
        private val entry: FatDirectoryEntry,
        private val clusterChain: List<Long>,
    ) {
        fun readAt(offset: Long, target: ByteArray, length: Int): Int {
            if (offset >= entry.sizeBytes || length <= 0) {
                return 0
            }
            var remaining = minOf(length.toLong(), entry.sizeBytes - offset).toInt()
            var targetOffset = 0
            var cursor = offset
            while (remaining > 0) {
                val clusterIndex = (cursor / volume.clusterSizeBytes).toInt()
                if (clusterIndex !in clusterChain.indices) {
                    break
                }
                val clusterOffset = (cursor % volume.clusterSizeBytes).toInt()
                var contiguousCount = 1
                val maxNeededClusters = ((clusterOffset + remaining + volume.clusterSizeBytes - 1) / volume.clusterSizeBytes)
                    .coerceAtLeast(1)
                while (
                    clusterIndex + contiguousCount < clusterChain.size &&
                    contiguousCount < maxNeededClusters &&
                    clusterChain[clusterIndex + contiguousCount] == clusterChain[clusterIndex + contiguousCount - 1] + 1L
                ) {
                    contiguousCount += 1
                }
                val clusterData = if (contiguousCount == 1) {
                    volume.readCluster(clusterChain[clusterIndex])
                } else {
                    volume.readClusters(clusterChain[clusterIndex], contiguousCount)
                }
                val toCopy = minOf(remaining, clusterData.size - clusterOffset)
                System.arraycopy(clusterData, clusterOffset, target, targetOffset, toCopy)
                remaining -= toCopy
                targetOffset += toCopy
                cursor += toCopy.toLong()
            }
            return targetOffset
        }
    }

    private data class MassStorageTransport(
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
    )

    private data class BotCommandResult(
        val data: ByteArray,
        val residue: Int,
        val status: Int,
    )

    private interface Ax3SerialPort {
        fun write(src: ByteArray, timeout: Int)
        fun read(dest: ByteArray, timeout: Int): Int
    }

    private inner class LibUsbSerialPort(private val port: UsbSerialPort) : Ax3SerialPort {
        override fun write(src: ByteArray, timeout: Int) = port.write(src, timeout)
        override fun read(dest: ByteArray, timeout: Int): Int = port.read(dest, timeout)
    }

    private inner class DirectCdcPort(
        private val connection: UsbDeviceConnection,
        private val inEndpoint: UsbEndpoint,
        private val outEndpoint: UsbEndpoint,
    ) : Ax3SerialPort {
        override fun write(src: ByteArray, timeout: Int) {
            var offset = 0
            while (offset < src.size) {
                val sent = connection.bulkTransfer(outEndpoint, src, offset, src.size - offset, timeout)
                if (sent <= 0) throw IOException("CDC bulk write failed (result=$sent, offset=$offset)")
                offset += sent
            }
        }
        override fun read(dest: ByteArray, timeout: Int): Int =
            connection.bulkTransfer(inEndpoint, dest, dest.size, timeout)
    }

    private inner class Ax3Protocol(private val port: Ax3SerialPort) {
        fun identity(): Identity {
            val line = command("\r\nID\r\n", "ID=")
            val parts = splitParts(line)
            require(parts.size >= 6) { "Unexpected ID response: $line" }
            return Identity(
                type = parts[1],
                hardwareVersion = parts[2].toInt(),
                firmwareVersion = parts[3].toInt(),
                deviceId = parts[4].toInt(),
                sessionId = parts[5].toInt(),
            )
        }

        fun batteryLevel(): Int {
            val line = command("\r\nSAMPLE 1\r\n", "\$BATT=")
            val parts = splitParts(line)
            require(parts.size >= 6) { "Unexpected battery response: $line" }
            val devicePercent = parts[4].toInt()
            return if (devicePercent != 0) devicePercent else adcBatteryToPercent(parts[1].toInt())
        }

        fun batteryHealth(): Int = splitParts(command("\r\nSTATUS 2\r\n", "BATTHEALTH=")).getOrElse(1) { "0" }.toInt()

        fun memoryHealth(): Int {
            val parts = splitParts(command("\r\nSTATUS 3\r\n", "FTL="))
            require(parts.size >= 6) { "Unexpected memory health response" }
            val reserved = parts[3].toInt()
            val planes = parts[4].toInt()
            var worstPlane = 0
            for (index in 0 until planes) {
                worstPlane = maxOf(worstPlane, parts[5 + index].toInt())
            }
            return (reserved - worstPlane - 6).coerceAtLeast(0)
        }

        fun time(): LocalDateTime? = AxivityDateTime.parseDeviceResponse(splitParts(command("\r\nTIME\r\n", "\$TIME=")).getOrNull(1))

        fun startTime(): LocalDateTime? {
            val raw = splitParts(command("\r\nHIBERNATE\r\n", "HIBERNATE=")).getOrNull(1)?.trim() ?: return null
            return when (raw) {
                "0" -> alwaysStart
                "-1" -> null
                else -> AxivityDateTime.parseDeviceResponse(raw)
            }
        }

        fun stopTime(): LocalDateTime? {
            val raw = splitParts(command("\r\nSTOP\r\n", "STOP=")).getOrNull(1)?.trim() ?: return null
            return when (raw) {
                "-1" -> alwaysStop
                "0" -> alwaysStart
                else -> AxivityDateTime.parseDeviceResponse(raw)
            }
        }

        fun metadata(): Map<String, String> {
            val raw = buildString {
                repeat(14) { segment ->
                    val line = command("\r\nANNOTATE${segment.toString().padStart(2, '0')}\r\n", "ANNOTATE${segment.toString().padStart(2, '0')}=")
                    val payload = splitParts(line).getOrNull(1).orEmpty()
                    append(payload.trimEnd())
                }
            }
            return MetadataCodec.parseMetadata(raw.trim())
        }

        fun rateConfig(): RateConfig {
            val parts = splitParts(command("\r\nRATE\r\n", "RATE="))
            require(parts.size >= 3) { "Unexpected RATE response" }
            var value = parts[1].toInt()
            var rate = when (value and 0x0f) {
                0x0f -> 3200
                0x0e -> 1600
                0x0d -> 800
                0x0c -> 400
                0x0b -> 200
                0x0a -> 100
                0x09 -> 50
                0x08 -> 25
                0x07 -> 12
                0x06 -> 6
                else -> 100
            }
            if ((value and 0x10) != 0) {
                rate *= -1
            }
            val accelRange = 16 shr (value shr 6)
            val gyroRange = parts.getOrNull(3)?.toIntOrNull()
            return RateConfig(
                rateHz = abs(rate),
                accelRangeG = accelRange,
                gyroRangeDps = gyroRange,
            )
        }

        fun setLed(led: Int) {
            command("\r\nLED $led\r\n", "LED=")
        }

        fun setSession(value: Int) {
            command("\r\nSESSION $value\r\n", "SESSION=")
        }

        fun setMetadata(values: Map<String, String>) {
            val raw = MetadataCodec.createMetadata(values)
            repeat(14) { segment ->
                val chunk = raw.padEnd(14 * 32, ' ').chunked(32)[segment]
                command("\r\nANNOTATE${segment.toString().padStart(2, '0')}=$chunk\r\n", "ANNOTATE${segment.toString().padStart(2, '0')}=")
            }
        }

        fun setMaxSamples(value: Int) {
            command("\r\nMAXSAMPLES $value\r\n", "MAXSAMPLES=")
        }

        fun setAccelConfig(rateHz: Int, accelRange: Int, lowPower: Boolean, gyroRangeDps: Int) {
            var encoded = when (rateHz) {
                3200 -> 0x0f
                1600 -> 0x0e
                800 -> 0x0d
                400 -> 0x0c
                200 -> 0x0b
                100 -> 0x0a
                50 -> 0x09
                25 -> 0x08
                12 -> 0x07
                6 -> 0x06
                else -> throw IllegalArgumentException("Unsupported sample rate $rateHz")
            }
            if (lowPower) {
                encoded = encoded or 0x10
            }
            encoded = encoded or when (accelRange) {
                16 -> 0x00
                8 -> 0x40
                4 -> 0x80
                2 -> 0xc0
                else -> throw IllegalArgumentException("Unsupported range $accelRange")
            }
            val command = if (gyroRangeDps > 0) {
                "\r\nRATE $encoded,$gyroRangeDps\r\n"
            } else {
                "\r\nRATE $encoded\r\n"
            }
            this.command(command, "RATE=")
        }

        fun setDebug(debugCode: Int) {
            command("\r\nDEBUG $debugCode\r\n", "DEBUG=")
        }

        fun setDelays(start: LocalDateTime?, stop: LocalDateTime?) {
            val hibernateCommand = when (start) {
                null -> "\r\nHIBERNATE -1\r\n"
                alwaysStart -> "\r\nHIBERNATE 0\r\n"
                else -> "\r\nHIBERNATE ${AxivityDateTime.formatCommand(start)}\r\n"
            }
            val stopCommand = when (stop) {
                null -> "\r\nSTOP -1\r\n"
                alwaysStop -> "\r\nSTOP -1\r\n"
                else -> "\r\nSTOP ${AxivityDateTime.formatCommand(stop)}\r\n"
            }
            command(hibernateCommand, "HIBERNATE=")
            command(stopCommand, "STOP=")
        }

        fun commit() {
            command("\r\ncommit\r\n", "COMMIT", timeoutMs = 6000)
        }

        fun erase(level: EraseLevel) {
            when (level) {
                EraseLevel.NONE -> commit()
                EraseLevel.DELETE -> command("\r\nCLEAR DATA\r\n", "COMMIT", timeoutMs = 6000)
                EraseLevel.QUICK_FORMAT -> command("\r\nFORMAT QC\r\n", "COMMIT", timeoutMs = 6000)
                EraseLevel.WIPE -> command("\r\nFORMAT WC\r\n", "COMMIT", timeoutMs = 15000)
            }
        }

        fun syncTime() {
            repeat(12) {
                val second = System.currentTimeMillis() / 1000L
                while ((System.currentTimeMillis() / 1000L) == second) {
                    Thread.sleep(5)
                }
                val target = AxivityDateTime.nowRoundedToSecond()
                command("\r\nTIME ${AxivityDateTime.formatCommand(target)}\r\n", "\$TIME=")
                Thread.sleep(1200)
                val observed = time()
                if (observed != null && observed.isCloseTo(target, 5)) {
                    // Match the desktop flow by also checking that the device clock is advancing after the set.
                    val checkStart = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - checkStart < 4000) {
                        val current = time()
                        if (current != null && current.isAfter(observed) && current.isCloseTo(LocalDateTime.now(), 5)) {
                            return
                        }
                    }
                }
            }
            throw IOException("Time synchronization failed")
        }

        fun primeConnection() {
            // Linux OMAPI sends an initial CRLF and flushes early CDC output before issuing real commands.
            runCatching {
                port.write("\r\n".toByteArray(Charsets.US_ASCII), 250)
            }
            flushInput(250)
        }

        private fun splitParts(line: String): List<String> = line.split('=', ',')

        private fun command(command: String, expectedPrefix: String, timeoutMs: Int = 2000): String {
            flushInput()
            port.write(command.toByteArray(Charsets.US_ASCII), timeoutMs)
            val start = SystemClock.elapsedRealtime()
            val buffer = ByteArray(256)
            val line = StringBuilder()
            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                val read = port.read(buffer, minOf(250, (timeoutMs - (SystemClock.elapsedRealtime() - start)).toInt().coerceAtLeast(1)))
                if (read <= 0) {
                    continue
                }
                for (index in 0 until read) {
                    val char = buffer[index].toInt().toChar()
                    if (char == '\r' || char == '\n') {
                        if (line.isEmpty()) {
                            continue
                        }
                        val candidate = line.toString()
                        line.setLength(0)
                        if (candidate.startsWith(expectedPrefix)) {
                            return candidate
                        }
                        if (candidate.startsWith("ERROR:")) {
                            throw IOException(candidate)
                        }
                    } else {
                        line.append(char)
                    }
                }
            }
            throw IOException("Timed out waiting for $expectedPrefix")
        }

        private fun flushInput(timeoutMs: Int = 25) {
            val buffer = ByteArray(128)
            while (true) {
                val read = runCatching { port.read(buffer, timeoutMs) }.getOrElse { 0 }
                if (read <= 0) {
                    return
                }
            }
        }
    }

    private fun adcBatteryToPercent(raw: Int): Int {
        var adjusted = raw
        if (adjusted > 12) {
            adjusted -= 12
        }
        return when {
            adjusted > 708 -> 100
            adjusted < 614 -> 0
            adjusted > 666 -> ((150L * (adjusted - 538)) shr 8).toInt()
            else -> ((375L * (adjusted - 614)) shr 8).toInt()
        }
    }

    private enum class EraseLevel {
        NONE,
        DELETE,
        QUICK_FORMAT,
        WIPE,
    }

}
