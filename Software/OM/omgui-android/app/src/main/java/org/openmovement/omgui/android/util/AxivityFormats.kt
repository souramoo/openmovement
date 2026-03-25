package org.openmovement.omgui.android.util

import org.openmovement.omgui.android.data.CwaMetadata
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object AxivityDateTime {
    private val deviceFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd,HH:mm:ss")
    private val commandFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun unpack(value: Long): LocalDateTime? {
        if (value <= 0L || value == 0xffff_ffffL) {
            return null
        }
        val year = 2000 + ((value shr 26) and 0x3f).toInt()
        val month = ((value shr 22) and 0x0f).toInt()
        val day = ((value shr 17) and 0x1f).toInt()
        val hour = ((value shr 12) and 0x1f).toInt()
        val minute = ((value shr 6) and 0x3f).toInt()
        val second = (value and 0x3f).toInt()
        return runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull()
    }

    fun pack(value: LocalDateTime): Long {
        return (((value.year % 100L) and 0x3f) shl 26) or
            ((value.monthValue.toLong() and 0x0f) shl 22) or
            ((value.dayOfMonth.toLong() and 0x1f) shl 17) or
            ((value.hour.toLong() and 0x1f) shl 12) or
            ((value.minute.toLong() and 0x3f) shl 6) or
            (value.second.toLong() and 0x3f)
    }

    fun parseDeviceResponse(value: String?): LocalDateTime? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { LocalDateTime.parse(value.trim(), deviceFormatter) }.getOrNull()
    }

    fun formatCommand(value: LocalDateTime): String = value.format(commandFormatter)

    fun recordingState(start: LocalDateTime?, stop: LocalDateTime?, now: LocalDateTime = LocalDateTime.now()): org.openmovement.omgui.android.data.RecordingState {
        if (start == null || stop == null) {
            return org.openmovement.omgui.android.data.RecordingState.STOPPED
        }
        if (!stop.isAfter(start) || !stop.isAfter(now)) {
            return org.openmovement.omgui.android.data.RecordingState.STOPPED
        }
        if (start.year <= 2000 && stop.year >= 2063) {
            return org.openmovement.omgui.android.data.RecordingState.ALWAYS
        }
        return org.openmovement.omgui.android.data.RecordingState.INTERVAL
    }

    fun nowRoundedToSecond(): LocalDateTime =
        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
}

object MetadataCodec {
    val metadataFields: List<Pair<String, String>> = listOf(
        "StudyCentre" to "Study Centre",
        "StudyCode" to "Study Code",
        "StudyInvestigator" to "Investigator",
        "StudyExerciseType" to "Exercise Type",
        "StudyOperator" to "Operator",
        "StudyNotes" to "Study Notes",
        "SubjectSite" to "Subject Site",
        "SubjectCode" to "Subject Code",
        "SubjectSex" to "Sex",
        "SubjectHeight" to "Height",
        "SubjectWeight" to "Weight",
        "SubjectHandedness" to "Handedness",
        "SubjectNotes" to "Subject Notes",
    )

    private val shorthandToLonghand = mapOf(
        "_c" to "StudyCentre",
        "_s" to "StudyCode",
        "_i" to "StudyInvestigator",
        "_x" to "StudyExerciseType",
        "_so" to "StudyOperator",
        "_n" to "StudyNotes",
        "_p" to "SubjectSite",
        "_sc" to "SubjectCode",
        "_se" to "SubjectSex",
        "_h" to "SubjectHeight",
        "_w" to "SubjectWeight",
        "_ha" to "SubjectHandedness",
        "_sn" to "SubjectNotes",
    )

    private val longhandToShorthand = shorthandToLonghand.entries.associate { (k, v) -> v to k }

    fun parseMetadata(source: String?): Map<String, String> {
        if (source.isNullOrBlank()) {
            return emptyMap()
        }
        return source.split("&")
            .mapNotNull { part ->
                if (part.isBlank()) {
                    null
                } else {
                    val pieces = part.split("=", limit = 2)
                    val key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8)
                    val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                    if (key.isBlank() && value.isBlank()) null else key to value
                }
            }
            .associate { (rawKey, value) ->
                (shorthandToLonghand[rawKey] ?: rawKey) to value
            }
    }

    fun createMetadata(values: Map<String, String>): String {
        return values.entries
            .filter { (key, value) -> key.isNotBlank() || value.isNotBlank() }
            .joinToString("&") { (key, value) ->
                val rawKey = longhandToShorthand[key] ?: key
                "${URLEncoder.encode(rawKey, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
    }

    fun renderFilename(template: String, values: Map<String, String>): String {
        var rendered = template
        for ((key, value) in values) {
            rendered = rendered.replace("{$key}", value)
        }
        val cleaned = buildString {
            for (char in rendered) {
                append(
                    when {
                        char.isLetterOrDigit() || char == '-' || char == '_' -> char
                        else -> '_'
                    },
                )
            }
        }.trim('_')
        return cleaned.ifEmpty { "download" }
    }

    fun defaultFilenameValues(metadata: Map<String, String>, deviceId: Long?, sessionId: Long?): Map<String, String> {
        val values = metadata.toMutableMap()
        if (deviceId != null) values["DeviceId"] = "%05d".format(deviceId)
        if (sessionId != null) values["SessionId"] = "%010d".format(sessionId)
        return values
    }
}

object CwaMetadataParser {
    private const val dataSectorSize = 512
    private const val dataSectorsPerBlock = 256
    private const val dataEraseBlockSize = dataSectorSize * dataSectorsPerBlock
    private const val annotationOffset = 64
    private const val annotationLength = 14 * 32

    fun parse(file: File): CwaMetadata? {
        if (!file.exists() || !file.isFile) {
            return null
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val metadataBlock = ByteArray(dataEraseBlockSize)
                raf.seek(0L)
                raf.readFully(metadataBlock)

                val deviceIdLow = ushortLe(metadataBlock, 5).toLong()
                val sessionId = uintLe(metadataBlock, 7)
                val majorDeviceId = ushortLe(metadataBlock, 11).toLong()
                val deviceId = if (majorDeviceId in 1..0xfffe) {
                    deviceIdLow or (majorDeviceId shl 16)
                } else {
                    deviceIdLow
                }

                val sampleCode = metadataBlock[36].toInt() and 0xff
                val sampleRate = sampleRateFromCode(sampleCode)
                val sampleRange = sampleRangeFromCode(sampleCode)
                val annotation = metadataAnnotation(metadataBlock)
                val values = MetadataCodec.parseMetadata(annotation).toMutableMap()
                values["DeviceId"] = "%05d".format(deviceId)
                values["SessionId"] = "%010d".format(sessionId)
                values["SamplingRate"] = sampleRate.toString()
                values["SamplingRange"] = sampleRange.toString()

                val start = readSectorTimestamp(raf, 2L, sessionId)
                val end = binarySearchLastTimestamp(raf, 2L, (raf.length() / dataSectorSize) - 1, sessionId)

                if (start != null) {
                    values["StartTime"] = start.toString().replace('T', ' ')
                    values["StartTimeNumeric"] = start.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                }
                if (end != null) {
                    values["EndTime"] = end.toString().replace('T', ' ')
                    values["EndTimeNumeric"] = end.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                }

                CwaMetadata(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    samplingRateHz = sampleRate,
                    samplingRangeG = sampleRange,
                    startTime = start,
                    endTime = end,
                    values = values,
                )
            }
        }.getOrNull()
    }

    private fun metadataAnnotation(block: ByteArray): String {
        val slice = block.copyOfRange(annotationOffset, annotationOffset + annotationLength)
        return buildString {
            for (byte in slice) {
                val char = byte.toInt().toChar()
                if (byte.toInt() == 0xff || char == ' ') {
                    continue
                }
                append(if (char == '?') '&' else char)
            }
        }
    }

    private fun binarySearchLastTimestamp(
        raf: RandomAccessFile,
        startSector: Long,
        endSector: Long,
        sessionId: Long,
    ): LocalDateTime? {
        var left = startSector
        var right = endSector
        var best: LocalDateTime? = null
        while (left < right) {
            val mid = (left + right) / 2
            val timestamp = readSectorTimestamp(raf, mid, sessionId)
            if (timestamp != null) {
                best = timestamp
                if (left == mid) {
                    break
                }
                left = mid
            } else {
                if (right == mid) {
                    break
                }
                right = mid
            }
        }
        return best ?: readSectorTimestamp(raf, startSector, sessionId)
    }

    private fun readSectorTimestamp(raf: RandomAccessFile, sectorIndex: Long, sessionId: Long): LocalDateTime? {
        if (sectorIndex < 0) {
            return null
        }
        val buffer = ByteArray(dataSectorSize)
        raf.seek(sectorIndex * dataSectorSize)
        if (raf.read(buffer) != buffer.size) {
            return null
        }
        val header = ushortLe(buffer, 0)
        if (header != 0x5841) {
            return null
        }
        val sectorSessionId = uintLe(buffer, 6)
        if (sessionId != 0L && sectorSessionId != sessionId) {
            return null
        }
        return AxivityDateTime.unpack(uintLe(buffer, 14))
    }

    private fun sampleRateFromCode(code: Int): Int {
        val divisor = 1 shl (15 - (code and 0x0f))
        return 3200 / divisor
    }

    private fun sampleRangeFromCode(code: Int): Int = 16 shr (code shr 6)

    private fun ushortLe(buffer: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buffer, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun uintLe(buffer: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
}

fun File.ensureParentDirectories() {
    parentFile?.mkdirs()
}

fun Instant.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(this, ZoneId.systemDefault())

fun LocalDateTime.toInstant(): Instant =
    atZone(ZoneId.systemDefault()).toInstant()

fun LocalDateTime.isCloseTo(other: LocalDateTime, seconds: Long = 5): Boolean =
    abs(ChronoUnit.SECONDS.between(this, other)) <= seconds
