package org.openmovement.omgui.android.device

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice.Companion.getMassStorageDevices
import me.jahnen.libaums.core.fs.UsbFileInputStream
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
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class Ax3Repository(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val serialProber = UsbSerialProber(
        UsbSerialProber.getDefaultProbeTable().apply {
            // Match the Windows OMAPI approach: identify the AX3 by VID/PID and bind it to CDC explicitly.
            addProduct(AX3_VENDOR_ID, AX3_PRODUCT_ID, CdcAcmSerialDriver::class.java)
        },
    )
    private val mutableDevices = MutableStateFlow<List<Ax3DeviceSnapshot>>(emptyList())
    private val mutableFiles = MutableStateFlow<List<AppFile>>(emptyList())
    private val mutableStatus = MutableStateFlow("Ready")

    val devices: StateFlow<List<Ax3DeviceSnapshot>> = mutableDevices.asStateFlow()
    val files: StateFlow<List<AppFile>> = mutableFiles.asStateFlow()
    val status: StateFlow<String> = mutableStatus.asStateFlow()

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
                            Ax3DeviceSnapshot(
                                usbKey = usbKey(device),
                                usbDeviceId = device.deviceId,
                                usbName = device.productName ?: "AX3 USB device",
                                hasPermission = true,
                                warning = error.message ?: "Unable to query device",
                            )
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

    suspend fun clear(usbKey: String, wipe: Boolean) {
        val device = requireUsbDevice(usbKey)
        withProtocol(device) { protocol ->
            protocol.setSession(0)
            protocol.setMetadata(emptyMap())
            protocol.setDelays(null, null)
            protocol.setAccelConfig(100, 8, false, 0)
            protocol.erase(if (wipe) EraseLevel.WIPE else EraseLevel.QUICK_FORMAT)
        }
        mutableStatus.value = if (wipe) "Wiped device." else "Cleared device."
        refreshDevices()
    }

    suspend fun configure(usbKey: String, config: Ax3RecordingConfig) {
        val device = requireUsbDevice(usbKey)
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
            protocol.commit()
        }
        mutableStatus.value = "Configured device for recording."
        refreshDevices()
    }

    suspend fun download(usbKey: String, settings: AppSettings) {
        val device = requireUsbDevice(usbKey)
        val snapshot = if (usbManager.hasPermission(device)) readSnapshot(device) else throw IOException("USB permission missing")
        val filenameValues = MetadataCodec.defaultFilenameValues(snapshot.metadata, snapshot.deviceId, snapshot.sessionId)
        val baseName = MetadataCodec.renderFilename(settings.filenameTemplate, filenameValues)
        val outputFile = uniqueDestination(settings.downloadsRoot, "$baseName.cwa")
        val partialFile = File(outputFile.absolutePath + ".part")
        partialFile.ensureParentDirectories()

        val massStorageDevices = device.getMassStorageDevices(appContext)
        val storage = massStorageDevices.firstOrNull() ?: throw IOException("AX3 mass storage interface not available")
        try {
            storage.init()
            val partition = storage.partitions.firstOrNull() ?: throw IOException("No AX3 partitions found")
            val root = partition.fileSystem.rootDirectory
            val usbFile = root.search("CWA-DATA.CWA") ?: throw IOException("CWA-DATA.CWA not found on device")
            val totalBytes = usbFile.length
            updateDownloadState(usbKey, true, 0)
            UsbFileInputStream(usbFile).use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(partition.fileSystem.chunkSize.coerceAtLeast(4096))
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        copied += read
                        val progress = if (totalBytes <= 0L) 0 else ((copied * 100L) / totalBytes).toInt()
                        updateDownloadState(usbKey, true, progress)
                    }
                }
            }
            if (outputFile.exists()) {
                outputFile.delete()
            }
            partialFile.renameTo(outputFile)
            mutableStatus.value = "Downloaded ${outputFile.name}."
        } finally {
            updateDownloadState(usbKey, false, 100)
            runCatching { storage.close() }
        }
        refreshDevices()
        refreshFiles(settings)
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
        val dataBytes = readDataFileLength(device)
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

    private fun readDataFileLength(device: UsbDevice): Long? {
        val massStorageDevices = device.getMassStorageDevices(appContext)
        val storage = massStorageDevices.firstOrNull() ?: return null
        return try {
            storage.init()
            val partition = storage.partitions.firstOrNull() ?: return null
            val usbFile = partition.fileSystem.rootDirectory.search("CWA-DATA.CWA") ?: return null
            usbFile.length
        } finally {
            runCatching { storage.close() }
        }
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
            val driver = serialProber.probeDevice(device)
                ?: throw IOException("No serial driver found for AX3 control interface")
            val port = driver.ports.firstOrNull() ?: throw IOException("No serial ports exposed by AX3")
            val connection = usbManager.openDevice(device) ?: throw IOException("Unable to open USB device")
            port.open(connection)
            runCatching {
                // AX3 firmware accepts host line-coding changes, but OMAPI does not depend on a real UART baud.
                // Use the historical CDC default from Microchip's stack rather than an arbitrary high rate.
                port.setParameters(19200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }.recoverCatching {
                port.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }.getOrThrow()
            runCatching { port.setDTR(false) }
            runCatching { port.setRTS(false) }
            try {
                val protocol = Ax3Protocol(port)
                protocol.primeConnection()
                block(protocol)
            } finally {
                runCatching { port.close() }
            }
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

    private inner class Ax3Protocol(private val port: UsbSerialPort) {
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

        fun startTime(): LocalDateTime? = parseNullableDate(command("\r\nHIBERNATE\r\n", "HIBERNATE="))

        fun stopTime(): LocalDateTime? = parseNullableDate(command("\r\nSTOP\r\n", "STOP="))

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

        private fun parseNullableDate(line: String): LocalDateTime? {
            val raw = splitParts(line).getOrNull(1) ?: return null
            return AxivityDateTime.parseDeviceResponse(raw)
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

    companion object {
        const val AX3_VENDOR_ID = 0x04d8
        const val AX3_PRODUCT_ID = 0x0057

        val alwaysStart: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
        val alwaysStop: LocalDateTime = LocalDateTime.of(2063, 12, 31, 23, 59, 59)
    }
}
