package org.openmovement.omgui.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openmovement.omgui.android.data.AppFile
import org.openmovement.omgui.android.data.AppFileKind
import org.openmovement.omgui.android.data.Ax3DeviceSnapshot
import org.openmovement.omgui.android.data.Ax3RecordingConfig
import org.openmovement.omgui.android.data.ExportJob
import org.openmovement.omgui.android.data.ExportRequest
import org.openmovement.omgui.android.data.ExportType
import org.openmovement.omgui.android.util.MetadataCodec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenMovementTabletApp(
    viewModel: AppViewModel,
    requestUsbPermission: (String) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var configDevice by remember { mutableStateOf<Ax3DeviceSnapshot?>(null) }
    var exportTarget by remember { mutableStateOf<AppFile?>(null) }
    var exportType by remember { mutableStateOf<ExportType?>(null) }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Open Movement Tablet") },
                    actions = {
                        TextButton(onClick = viewModel::refreshAll) {
                            Text("Refresh")
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Text(
                        text = status,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("Devices", "Files", "Jobs", "Settings").forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
                when (selectedTab) {
                    0 -> DevicesScreen(
                        devices = devices,
                        onGrantUsb = requestUsbPermission,
                        onIdentify = viewModel::identify,
                        onSyncTime = viewModel::syncTime,
                        onConfigure = { configDevice = it },
                        onDownload = viewModel::download,
                        onClear = { usbKey, wipe -> viewModel.clear(usbKey, wipe) },
                    )

                    1 -> FilesScreen(
                        files = files,
                        onRefresh = viewModel::refreshFiles,
                        onExport = { file, type ->
                            exportTarget = file
                            exportType = type
                        },
                    )

                    2 -> JobsScreen(
                        jobs = jobs,
                        onClearCompleted = viewModel::clearCompletedJobs,
                    )

                    else -> SettingsScreen(
                        filenameTemplate = settings.filenameTemplate,
                        workingRoot = settings.workingRoot.absolutePath,
                        downloadsRoot = settings.downloadsRoot.absolutePath,
                        exportsRoot = settings.exportsRoot.absolutePath,
                        onTemplateSaved = viewModel::updateFilenameTemplate,
                    )
                }
            }
        }
    }

    configDevice?.let { device ->
        RecordingConfigDialog(
            device = device,
            onDismiss = { configDevice = null },
            onSave = { config ->
                viewModel.configure(device.usbKey, config)
                configDevice = null
            },
        )
    }

    if (exportTarget != null && exportType != null) {
        ExportDialog(
            file = exportTarget!!,
            type = exportType!!,
            onDismiss = {
                exportTarget = null
                exportType = null
            },
            onRun = { request ->
                viewModel.export(request)
                exportTarget = null
                exportType = null
            },
        )
    }
}

@Composable
private fun DevicesScreen(
    devices: List<Ax3DeviceSnapshot>,
    onGrantUsb: (String) -> Unit,
    onIdentify: (String) -> Unit,
    onSyncTime: (String) -> Unit,
    onConfigure: (Ax3DeviceSnapshot) -> Unit,
    onDownload: (String) -> Unit,
    onClear: (String, Boolean) -> Unit,
) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Connect an AX3 over USB OTG to begin.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(devices, key = { it.usbKey }) { device ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (device.hasPermission) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DeviceField("Device", device.deviceId?.let { "%05d".format(it) } ?: "Unknown")
                        DeviceField("Session", device.sessionId?.let { "%010d".format(it) } ?: "-")
                        DeviceField("Battery", device.batteryLevel?.let { "$it%" } ?: "-")
                        DeviceField("Data", device.dataBytes?.let { "${it / 1024} KB" } ?: "-")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DeviceField("Firmware", device.firmwareVersion?.toString() ?: "-")
                        DeviceField("Memory", device.memoryHealth?.toString() ?: "-")
                        DeviceField("Rate", device.sampleRateHz?.let { "$it Hz" } ?: "-")
                        DeviceField("Range", device.accelRangeG?.let { "±${it}g" } ?: "-")
                    }
                    device.startTime?.let {
                        DeviceField("Start", it.toString().replace('T', ' '))
                    }
                    device.stopTime?.let {
                        DeviceField("Stop", it.toString().replace('T', ' '))
                    }
                    if (!device.warning.isNullOrBlank()) {
                        Text(device.warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (device.isDownloading) {
                        Text("Downloading ${device.downloadProgress}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!device.hasPermission) {
                            Button(onClick = { onGrantUsb(device.usbKey) }) {
                                Text("Grant USB")
                            }
                        } else {
                            Button(onClick = { onIdentify(device.usbKey) }) { Text("Identify") }
                            Button(onClick = { onSyncTime(device.usbKey) }) { Text("Sync Time") }
                            Button(onClick = { onConfigure(device) }) { Text("Configure") }
                            Button(onClick = { onDownload(device.usbKey) }, enabled = device.hasData && !device.isDownloading) { Text("Download") }
                            Button(onClick = { onClear(device.usbKey, false) }, enabled = !device.isDownloading) { Text("Clear") }
                            Button(onClick = { onClear(device.usbKey, true) }, enabled = !device.isDownloading) { Text("Wipe") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FilesScreen(
    files: List<AppFile>,
    onRefresh: () -> Unit,
    onExport: (AppFile, ExportType) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onRefresh) { Text("Refresh Files") }
        }
        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Downloads and exports will appear here.")
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(files, key = { it.file.absolutePath }) { file ->
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(file.file.absolutePath, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DeviceField("Type", file.kind.name)
                            DeviceField("Size", "${file.sizeBytes / 1024} KB")
                            DeviceField("Modified", file.modifiedAt.toString().replace('T', ' '))
                        }
                        file.metadata?.let { metadata ->
                            val summary = buildList {
                                metadata.values["SubjectCode"]?.takeIf { it.isNotBlank() }?.let { add("Subject $it") }
                                metadata.values["StudyCode"]?.takeIf { it.isNotBlank() }?.let { add("Study $it") }
                                metadata.values["StartTime"]?.takeIf { it.isNotBlank() }?.let { add(it) }
                            }.joinToString(" • ")
                            if (summary.isNotBlank()) {
                                Text(summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (file.kind == AppFileKind.RAW_CWA) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { onExport(file, ExportType.WAV) }) { Text("WAV") }
                                Button(onClick = { onExport(file, ExportType.CSV) }) { Text("CSV") }
                                Button(onClick = { onExport(file, ExportType.SVM) }) { Text("SVM") }
                                Button(onClick = { onExport(file, ExportType.WTV) }) { Text("WTV") }
                                Button(onClick = { onExport(file, ExportType.CUT) }) { Text("CUT") }
                                Button(onClick = { onExport(file, ExportType.SLEEP) }) { Text("Sleep") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobsScreen(
    jobs: List<ExportJob>,
    onClearCompleted: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onClearCompleted) { Text("Clear Completed") }
        }
        if (jobs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Export jobs will appear here.")
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            items(jobs, key = { it.id }) { job ->
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${job.request.type} • ${job.request.inputFile.name}", style = MaterialTheme.typography.titleMedium)
                        Text(job.outputFile.absolutePath, style = MaterialTheme.typography.bodySmall)
                        DeviceField("Status", job.status.name)
                        if (!job.message.isNullOrBlank()) {
                            Text(job.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    filenameTemplate: String,
    workingRoot: String,
    downloadsRoot: String,
    exportsRoot: String,
    onTemplateSaved: (String) -> Unit,
) {
    var draftTemplate by rememberSaveable(filenameTemplate) { mutableStateOf(filenameTemplate) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("The Android port uses an app-specific working root instead of arbitrary Windows folders.", style = MaterialTheme.typography.bodyMedium)
        DeviceField("Working Root", workingRoot)
        DeviceField("Downloads", downloadsRoot)
        DeviceField("Exports", exportsRoot)
        OutlinedTextField(
            value = draftTemplate,
            onValueChange = { draftTemplate = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Download Filename Template") },
            supportingText = { Text("Example: {DeviceId}_{SessionId}_{SubjectCode}") },
        )
        Button(onClick = { onTemplateSaved(draftTemplate) }) {
            Text("Save Template")
        }
    }
}

@Composable
private fun RecordingConfigDialog(
    device: Ax3DeviceSnapshot,
    onDismiss: () -> Unit,
    onSave: (Ax3RecordingConfig) -> Unit,
) {
    val now = remember { LocalDateTime.now().withSecond(0).withNano(0) }
    var sessionId by rememberSaveable(device.usbKey) { mutableStateOf((device.sessionId ?: device.deviceId ?: 1L).toString()) }
    var alwaysRecord by rememberSaveable(device.usbKey) { mutableStateOf(device.recordingState == org.openmovement.omgui.android.data.RecordingState.ALWAYS) }
    var startTime by rememberSaveable(device.usbKey) { mutableStateOf((device.startTime ?: now.plusMinutes(1)).format(isoFormatter)) }
    var stopTime by rememberSaveable(device.usbKey) { mutableStateOf((device.stopTime ?: now.plusDays(7)).format(isoFormatter)) }
    var sampleRate by rememberSaveable(device.usbKey) { mutableStateOf((device.sampleRateHz ?: 100).toString()) }
    var accelRange by rememberSaveable(device.usbKey) { mutableStateOf((device.accelRangeG ?: 8).toString()) }
    var gyroRange by rememberSaveable(device.usbKey) { mutableStateOf((device.gyroRangeDps ?: 0).toString()) }
    var lowPower by rememberSaveable(device.usbKey) { mutableStateOf(false) }
    var flash by rememberSaveable(device.usbKey) { mutableStateOf(false) }
    var syncTime by rememberSaveable(device.usbKey) { mutableStateOf(true) }
    val metadataState = remember(device.usbKey) {
        MetadataCodec.metadataFields.associate { (key, _) -> key to mutableStateOf(device.metadata[key].orEmpty()) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val metadata = metadataState.mapValues { it.value.value }.filterValues { it.isNotBlank() }
                    onSave(
                        Ax3RecordingConfig(
                            sessionId = sessionId.toLongOrNull() ?: 1L,
                            alwaysRecord = alwaysRecord,
                            startTime = LocalDateTime.parse(startTime, isoFormatter),
                            stopTime = LocalDateTime.parse(stopTime, isoFormatter),
                            samplingFrequencyHz = sampleRate.toIntOrNull() ?: 100,
                            accelRangeG = accelRange.toIntOrNull() ?: 8,
                            gyroRangeDps = gyroRange.toIntOrNull() ?: 0,
                            lowPower = lowPower,
                            flashDuringRecording = flash,
                            syncTime = syncTime,
                            metadata = metadata,
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Recording Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(value = sessionId, onValueChange = { sessionId = it }, label = { Text("Session ID") }, modifier = Modifier.fillMaxWidth())
                LabeledCheckbox("Always record", alwaysRecord) { alwaysRecord = it }
                if (!alwaysRecord) {
                    OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text("Start (YYYY-MM-DDTHH:MM:SS)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = stopTime, onValueChange = { stopTime = it }, label = { Text("Stop (YYYY-MM-DDTHH:MM:SS)") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = sampleRate, onValueChange = { sampleRate = it }, label = { Text("Sample Rate (Hz)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = accelRange, onValueChange = { accelRange = it }, label = { Text("Accel Range (g)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gyroRange, onValueChange = { gyroRange = it }, label = { Text("Gyro Range (dps, 0 to disable)") }, modifier = Modifier.fillMaxWidth())
                LabeledCheckbox("Low power mode", lowPower) { lowPower = it }
                LabeledCheckbox("Flash during recording", flash) { flash = it }
                LabeledCheckbox("Sync time before commit", syncTime) { syncTime = it }
                Text("Metadata", style = MaterialTheme.typography.titleSmall)
                MetadataCodec.metadataFields.forEach { (key, label) ->
                    OutlinedTextField(
                        value = metadataState.getValue(key).value,
                        onValueChange = { metadataState.getValue(key).value = it },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

@Composable
private fun ExportDialog(
    file: AppFile,
    type: ExportType,
    onDismiss: () -> Unit,
    onRun: (ExportRequest) -> Unit,
) {
    var rate by rememberSaveable(file.file.absolutePath, type) { mutableStateOf("100") }
    var autoCalibrate by rememberSaveable(file.file.absolutePath, type) { mutableStateOf(true) }
    var epoch by rememberSaveable(file.file.absolutePath, type) { mutableStateOf(if (type == ExportType.WTV || type == ExportType.SLEEP) "30" else "60") }
    var filter by rememberSaveable(file.file.absolutePath, type) { mutableStateOf("1") }
    var svmMode by rememberSaveable(file.file.absolutePath, type) { mutableStateOf("1") }
    var cutModel by rememberSaveable(file.file.absolutePath, type) { mutableStateOf("0.046 0.093 0.419") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onRun(
                        ExportRequest(
                            inputFile = file.file,
                            type = type,
                            rateHz = rate.toIntOrNull() ?: 100,
                            autoCalibrate = autoCalibrate,
                            epochSeconds = epoch.toIntOrNull() ?: 60,
                            filterMode = filter.toIntOrNull() ?: 1,
                            svmMode = svmMode.toIntOrNull() ?: 1,
                            cutPointModel = cutModel,
                        ),
                    )
                },
            ) {
                Text("Run")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Export ${type.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                if (type == ExportType.WAV) {
                    OutlinedTextField(value = rate, onValueChange = { rate = it }, label = { Text("Resample Rate (Hz)") }, modifier = Modifier.fillMaxWidth())
                    LabeledCheckbox("Auto calibrate", autoCalibrate) { autoCalibrate = it }
                }
                if (type == ExportType.SVM || type == ExportType.WTV || type == ExportType.CUT) {
                    OutlinedTextField(value = epoch, onValueChange = { epoch = it }, label = { Text("Epoch (seconds)") }, modifier = Modifier.fillMaxWidth())
                }
                if (type == ExportType.SVM || type == ExportType.CUT) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Filter Mode") }, modifier = Modifier.fillMaxWidth())
                }
                if (type == ExportType.SVM) {
                    OutlinedTextField(value = svmMode, onValueChange = { svmMode = it }, label = { Text("SVM Mode") }, modifier = Modifier.fillMaxWidth())
                }
                if (type == ExportType.CUT) {
                    OutlinedTextField(value = cutModel, onValueChange = { cutModel = it }, label = { Text("Cut Point Model") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    )
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
