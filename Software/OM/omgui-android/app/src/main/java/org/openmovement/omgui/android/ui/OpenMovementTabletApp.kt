package org.openmovement.omgui.android.ui

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openmovement.omgui.android.R
import org.openmovement.omgui.android.data.AppFile
import org.openmovement.omgui.android.data.AppFileKind
import org.openmovement.omgui.android.data.Ax3DeviceSnapshot
import org.openmovement.omgui.android.data.Ax3RecordingConfig
import org.openmovement.omgui.android.data.ExportJob
import org.openmovement.omgui.android.data.ExportRequest
import org.openmovement.omgui.android.data.ExportType
import org.openmovement.omgui.android.util.MetadataCodec
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class AppScreen {
    DEVICES,
    FILES,
    DIAGNOSE,
    SETTINGS,
}

private const val UI_TAG = "OpenMovementTabletUI"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenMovementTabletApp(
    viewModel: AppViewModel,
    requestUsbPermission: (String) -> Unit,
    sendSupportEmail: (String, String) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val busyMessage by viewModel.busyMessage.collectAsStateWithLifecycle()
    val workflowStep by viewModel.workflowStep.collectAsStateWithLifecycle()
    val supportEmailBody by viewModel.supportEmailBody.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.DEVICES) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.notices.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    AX3AppTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentScreen) {
                                AppScreen.DEVICES -> run {
                                    val activeDevice = devices.firstOrNull { it.hasPermission } ?: devices.firstOrNull()
                                    when {
                                        workflowStep == WorkflowStep.ERROR -> "Sensor Setup"
                                        activeDevice == null || !activeDevice.hasPermission -> "Step 1 / 4: Connect"
                                        workflowStep == WorkflowStep.CONNECT -> "Step 2 / 4: Download"
                                        workflowStep == WorkflowStep.DOWNLOAD -> "Step 2 / 4: Download"
                                        workflowStep == WorkflowStep.RESET -> "Step 3 / 4: Reset"
                                        workflowStep == WorkflowStep.DONE -> "Step 4 / 4: Done"
                                        else -> "Sensor Setup"
                                    }
                                }
                                AppScreen.FILES -> "Files"
                                AppScreen.DIAGNOSE -> "Diagnose"
                                AppScreen.SETTINGS -> "Settings"
                            },
                        )
                    },
                    actions = {
                        TextButton(onClick = viewModel::refreshAll) {
                            Text("Refresh")
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Text("...")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Devices") },
                                    onClick = {
                                        currentScreen = AppScreen.DEVICES
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Files") },
                                    onClick = {
                                        currentScreen = AppScreen.FILES
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Diagnose") },
                                    onClick = {
                                        currentScreen = AppScreen.DIAGNOSE
                                        menuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        currentScreen = AppScreen.SETTINGS
                                        menuExpanded = false
                                    },
                                )
                            }
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
                if (isBusy) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (busyMessage.isBlank()) "Working..." else busyMessage,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Please keep AX3 connected. Do not unplug until completion message appears.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                when (currentScreen) {
                    AppScreen.DEVICES -> DevicesScreen(
                        devices = devices,
                        status = status,
                        workflowStep = workflowStep,
                        supportEmailBody = supportEmailBody,
                        onGrantUsb = requestUsbPermission,
                        onDownloadAndExportCsv = viewModel::downloadAndExportCsv,
                        onReset = viewModel::resetDevice,
                        onDismissError = viewModel::dismissWorkflowError,
                        onSendSupportEmail = sendSupportEmail,
                        onFinish = viewModel::refreshAll,
                    )

                    AppScreen.FILES -> FilesScreen(
                        files = files,
                        onRefresh = viewModel::refreshFiles,
                    )

                    AppScreen.DIAGNOSE -> DiagnoseScreen(
                        devices = devices,
                        onGrantUsb = requestUsbPermission,
                        onDiagnose = viewModel::diagnose,
                        onDownload = viewModel::download,
                        onClear = { usbKey -> viewModel.clear(usbKey, wipe = false) },
                        onReset = viewModel::resetDevice,
                        onStopRecording = viewModel::stopRecording,
                    )

                    AppScreen.SETTINGS -> SettingsScreen(
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
}

@Composable
private fun DevicesScreen(
    devices: List<Ax3DeviceSnapshot>,
    status: String,
    workflowStep: WorkflowStep,
    supportEmailBody: String?,
    onGrantUsb: (String) -> Unit,
    onDownloadAndExportCsv: (String) -> Unit,
    onReset: (String, Long) -> Unit,
    onDismissError: () -> Unit,
    onSendSupportEmail: (String, String) -> Unit,
    onFinish: () -> Unit,
) {
    val activeDevice = devices.firstOrNull { it.hasPermission } ?: devices.firstOrNull()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    val effectiveStep = when {
        workflowStep == WorkflowStep.ERROR -> WorkflowStep.ERROR
        activeDevice == null || !activeDevice.hasPermission -> WorkflowStep.CONNECT
        workflowStep == WorkflowStep.CONNECT -> WorkflowStep.DOWNLOAD
        else -> workflowStep
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (effectiveStep) {
            WorkflowStep.CONNECT -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (isLandscape) 20.dp else 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else 8.dp),
                    ) {
                        Text(
                            "Step 1 / 4: Connect Your Sensor",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Use the USB cable to plug your AX3 sensor into the tablet. You may need a USB-C adapter.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isLandscape) 28.dp else 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = if (isLandscape) 980.dp else 900.dp)
                                .weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(if (isLandscape) 10.dp else 12.dp),
                                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 10.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(if (isLandscape) 0.72f else 1f),
                                    horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else 8.dp),
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(1f)
                                                    .heightIn(min = 120.dp, max = if (isLandscape) 150.dp else 180.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Image(
                                                        painter = painterResource(id = R.drawable.ax3),
                                                        contentDescription = "AX3 Sensor",
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(if (isLandscape) 28.dp else 34.dp),
                                                        contentScale = ContentScale.Fit,
                                                    )
                                                }
                                            }
                                            Text(
                                                "AX3 Sensor",
                                                style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(1f)
                                                    .heightIn(min = 120.dp, max = if (isLandscape) 150.dp else 180.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Image(
                                                        painter = painterResource(id = R.drawable.cable),
                                                        contentDescription = "USB Cable",
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(if (isLandscape) 28.dp else 34.dp),
                                                        contentScale = ContentScale.Fit,
                                                    )
                                                }
                                            }
                                            Text(
                                                "USB Cable",
                                                style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = if (isLandscape) 76.dp else 92.dp)
                                            .padding(horizontal = 24.dp, vertical = if (isLandscape) 12.dp else 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(if (isLandscape) 12.dp else 14.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    shape = androidx.compose.foundation.shape.CircleShape,
                                                ),
                                        )
                                        Text(
                                            if (activeDevice == null) "Waiting for connection..." else "Sensor connected. USB permission required.",
                                            style = when {
                                                activeDevice == null && isLandscape -> MaterialTheme.typography.headlineLarge
                                                activeDevice == null -> MaterialTheme.typography.displaySmall
                                                isLandscape -> MaterialTheme.typography.titleLarge
                                                else -> MaterialTheme.typography.headlineSmall
                                            },
                                            fontWeight = if (activeDevice == null) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }

                                if (activeDevice != null && !activeDevice.hasPermission) {
                                    Button(
                                        onClick = { onGrantUsb(activeDevice.usbKey) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (isLandscape) 42.dp else 44.dp),
                                    ) {
                                        Text("Allow USB Access", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    StepIndicator(current = 1, total = 4)
                }
            }

            WorkflowStep.DOWNLOAD -> {
                val device = activeDevice ?: return@Box
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 860.dp)
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(170.dp)
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = "Download",
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }

                            Text("Step 2 / 4: Download", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "Your data is ready to be saved to this tablet.",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Button(
                                onClick = { onDownloadAndExportCsv(device.usbKey) },
                                enabled = !device.isDownloading && (device.hasData || device.dataBytes == null),
                                modifier = Modifier
                                    .widthIn(max = 420.dp)
                                    .fillMaxWidth()
                                    .height(72.dp),
                            ) {
                                Text("DOWNLOAD DATA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                DeviceField("Battery", device.batteryLevel?.let { "$it%" } ?: "Not available")
                                DeviceField("Data", device.dataBytes?.let { "${it / 1024} KB" } ?: "Not available")
                            }
                        }
                    }

                    StepIndicator(current = 2, total = 4)
                }
            }

            WorkflowStep.RESET -> {
                val device = activeDevice ?: return@Box
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 860.dp)
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(170.dp)
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = "Reset",
                                        modifier = Modifier.size(80.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }

                            Text("Step 3 / 4: Reset", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "Resetting makes the sensor ready for a fresh recording.",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    val resetSessionId = when {
                                        (device.sessionId ?: 0L) > 0L -> device.sessionId!!
                                        (device.deviceId ?: 0L) > 0L -> device.deviceId!!
                                        else -> 1L
                                    }
                                    onReset(device.usbKey, resetSessionId)
                                },
                                modifier = Modifier
                                    .widthIn(max = 420.dp)
                                    .fillMaxWidth()
                                    .height(72.dp),
                            ) {
                                Text("RESET SENSOR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    StepIndicator(current = 3, total = 4)
                }
            }

            WorkflowStep.DONE -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // LEFT COLUMN: Badge + Hero Text
                        Column(
                            modifier = Modifier
                                .weight(0.4f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            // Mission Accomplished Badge
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(26.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "MISSION ACCOMPLISHED",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }

                            // Hero Text: "Well done!" + "You are all finished for today."
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Well done!",
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    "You are all finished for today.",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        // RIGHT COLUMN: Content Cards
                        Column(
                            modifier = Modifier
                                .weight(0.6f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Main Content Card: Unplug Instructions
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(28.dp),
                                    verticalArrangement = Arrangement.spacedBy(24.dp),
                                ) {
                                    // Unplug instruction
                                    Text(
                                        "You can now safely unplug the sensor.",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )

                                    // Blinking Green Indicator
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Blinking green dot
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(
                                                        color = Color(0xFF22C55E),
                                                        shape = androidx.compose.foundation.shape.CircleShape,
                                                    )
                                                    .border(
                                                        width = 2.dp,
                                                        color = Color.White,
                                                        shape = androidx.compose.foundation.shape.CircleShape,
                                                    ),
                                            )
                                            Text(
                                                "It should be blinking green to show it is recording.",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    // What Happens Next? Tip Card with Left Border
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(
                                                width = 4.dp,
                                                color = MaterialTheme.colorScheme.secondary,
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                            ),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Info,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(28.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    "What happens next?",
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            Text(
                                                "Your data has been securely saved and sent to the research team. You don't need to do anything else until your next scheduled check-in next week.",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 30.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    StepIndicator(current = 4, total = 4)
                }
            }

            WorkflowStep.ERROR -> {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(status, style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = {
                                onSendSupportEmail(
                                    "Open Movement Tablet support request",
                                    supportEmailBody ?: status,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            Text("Send support email")
                        }
                        TextButton(onClick = onDismissError) {
                            Text("Go back")
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
private fun StepIndicator(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 1..total) {
            val isActive = index == current
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Spacer(
                    modifier = Modifier
                        .width(if (isActive) 28.dp else 10.dp)
                        .height(8.dp),
                )
            }
        }
    }
}

@Composable
private fun FilesScreen(
    files: List<AppFile>,
    onRefresh: () -> Unit,
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
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnoseScreen(
    devices: List<Ax3DeviceSnapshot>,
    onGrantUsb: (String) -> Unit,
    onDiagnose: (String) -> Unit,
    onDownload: (String) -> Unit,
    onClear: (String) -> Unit,
    onReset: (String, Long) -> Unit,
    onStopRecording: (String) -> Unit,
) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Please connect your AX3 device to view details.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(devices, key = { it.usbKey }) { device ->
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    DeviceField("Device ID", device.deviceId?.let { "%05d".format(it) } ?: "Unknown")
                    DeviceField("Session", device.sessionId?.let { "%010d".format(it) } ?: "-")
                    DeviceField("Firmware", device.firmwareVersion?.toString() ?: "-")
                    DeviceField("Memory Health", device.memoryHealth?.toString() ?: "-")
                    DeviceField("Battery", device.batteryLevel?.let { "$it%" } ?: "-")
                    DeviceField("Rate", device.sampleRateHz?.let { "$it Hz" } ?: "-")
                    DeviceField("Range", device.accelRangeG?.let { "±${it}g" } ?: "-")
                    device.startTime?.let { DeviceField("Start", it.toString().replace('T', ' ')) }
                    device.stopTime?.let { DeviceField("Stop", it.toString().replace('T', ' ')) }
                    if (!device.warning.isNullOrBlank()) {
                        Text(device.warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!device.hasPermission) {
                        Button(onClick = { onGrantUsb(device.usbKey) }) {
                            Text("Allow USB access")
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = { onDiagnose(device.usbKey) }, enabled = !device.isDownloading) {
                                Text("Run Device Check")
                            }
                            OutlinedButton(onClick = { onDownload(device.usbKey) }, enabled = !device.isDownloading) {
                                Text("Download")
                            }
                            OutlinedButton(onClick = { onClear(device.usbKey) }, enabled = !device.isDownloading) {
                                Text("Clear")
                            }
                            OutlinedButton(
                                onClick = {
                                    val resetSessionId = when {
                                        (device.sessionId ?: 0L) > 0L -> device.sessionId!!
                                        (device.deviceId ?: 0L) > 0L -> device.deviceId!!
                                        else -> 1L
                                    }
                                    onReset(device.usbKey, resetSessionId)
                                },
                                enabled = !device.isDownloading,
                            ) {
                                Text("Reset")
                            }
                        }
                        OutlinedButton(
                            onClick = { onStopRecording(device.usbKey) },
                            enabled = !device.isDownloading,
                        ) {
                            Text("Stop Recording")
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
