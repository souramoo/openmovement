package org.openmovement.omgui.android.export

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.openmovement.omgui.android.data.AppSettingsStore
import org.openmovement.omgui.android.data.ExportJob
import org.openmovement.omgui.android.data.ExportRequest
import org.openmovement.omgui.android.data.ExportStatus
import org.openmovement.omgui.android.data.ExportType
import java.io.File
import java.util.UUID

class ExportManager(
    context: Context,
    private val settingsStore: AppSettingsStore,
    private val refreshFiles: suspend () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mutableJobs = MutableStateFlow<List<ExportJob>>(emptyList())

    val jobs: StateFlow<List<ExportJob>> = mutableJobs.asStateFlow()

    suspend fun runExport(request: ExportRequest) {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val outputFile = uniqueOutput(settings.exportsRoot, request)
            val jobId = UUID.randomUUID().toString()
            val queued = ExportJob(
                id = jobId,
                request = request,
                outputFile = outputFile,
                status = ExportStatus.QUEUED,
            )
            prependJob(queued)
            updateJob(jobId) { it.copy(status = ExportStatus.RUNNING, message = "Running ${request.type} export") }
            val exitCode = runCatching {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                OmConvertNative.runOmconvert(buildArgs(request, outputFile))
            }.getOrElse { throwable ->
                updateJob(jobId) { it.copy(status = ExportStatus.FAILED, message = throwable.message ?: "Export failed") }
                return@withContext
            }
            if (exitCode == 0) {
                updateJob(jobId) { it.copy(status = ExportStatus.COMPLETED, message = outputFile.name) }
            } else {
                updateJob(jobId) { it.copy(status = ExportStatus.FAILED, message = "omconvert exit code $exitCode") }
            }
            refreshFiles()
        }
    }

    fun clearCompleted() {
        mutableJobs.value = mutableJobs.value.filterNot { it.status == ExportStatus.COMPLETED }
    }

    private fun prependJob(job: ExportJob) {
        mutableJobs.value = listOf(job) + mutableJobs.value
    }

    private fun updateJob(id: String, transform: (ExportJob) -> ExportJob) {
        mutableJobs.value = mutableJobs.value.map { if (it.id == id) transform(it) else it }
    }

    private fun uniqueOutput(directory: File, request: ExportRequest): File {
        directory.mkdirs()
        val desiredName = when (request.type) {
            ExportType.WAV -> request.inputFile.nameWithoutExtension + ".wav"
            ExportType.CSV -> request.inputFile.nameWithoutExtension + ".resampled.csv"
            ExportType.SVM -> request.inputFile.nameWithoutExtension + ".svm.csv"
            ExportType.WTV -> request.inputFile.nameWithoutExtension + ".wtv.csv"
            ExportType.CUT -> request.inputFile.nameWithoutExtension + ".cut.csv"
            ExportType.SLEEP -> request.inputFile.nameWithoutExtension + ".sleep.csv"
        }
        var candidate = File(directory, desiredName)
        var index = 1
        while (candidate.exists()) {
            val stem = desiredName.substringBeforeLast('.')
            val ext = desiredName.substringAfterLast('.')
            candidate = File(directory, "${stem}_$index.$ext")
            index++
        }
        return candidate
    }

    private fun buildArgs(request: ExportRequest, outputFile: File): Array<String> {
        val args = mutableListOf<String>()
        args += request.inputFile.absolutePath
        when (request.type) {
            ExportType.WAV -> {
                if (request.rateHz > 0) {
                    args += "-resample"
                    args += request.rateHz.toString()
                }
                args += "-calibrate"
                args += if (request.autoCalibrate) "1" else "0"
                args += "-out"
                args += outputFile.absolutePath
            }

            ExportType.CSV -> {
                args += "-csv-file"
                args += outputFile.absolutePath
            }

            ExportType.SVM -> {
                args += "-svm-epoch"
                args += request.epochSeconds.toString()
                args += "-svm-filter"
                args += request.filterMode.toString()
                args += "-svm-mode"
                args += request.svmMode.toString()
                args += "-svm-file"
                args += outputFile.absolutePath
            }

            ExportType.WTV -> {
                args += "-wtv-epoch"
                args += request.epochSeconds.toString()
                args += "-wtv-file"
                args += outputFile.absolutePath
            }

            ExportType.CUT -> {
                args += "-paee-epoch"
                args += request.epochSeconds.toString()
                args += "-paee-model"
                args += request.cutPointModel
                args += "-paee-filter"
                args += request.filterMode.toString()
                args += "-paee-file"
                args += outputFile.absolutePath
            }

            ExportType.SLEEP -> {
                args += "-sleep-file"
                args += outputFile.absolutePath
            }
        }
        return args.toTypedArray()
    }
}

