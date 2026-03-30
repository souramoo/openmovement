package org.openmovement.omgui.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.openmovement.omgui.android.ui.AppViewModel
import org.openmovement.omgui.android.ui.OpenMovementTabletApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    private lateinit var usbManager: UsbManager
    private val requestedPermissionDeviceIds = mutableSetOf<Int>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> {
                    val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { requestedPermissionDeviceIds.remove(it.deviceId) }
                    viewModel.refreshAll()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                -> {
                    val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        maybeRequestPermission(device)
                    }
                    viewModel.refreshAll()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { requestedPermissionDeviceIds.remove(it.deviceId) }
                    viewModel.refreshAll()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver()
        requestPermissionsForConnectedAx3Devices()

        setContent {
            OpenMovementTabletApp(
                viewModel = viewModel,
                requestUsbPermission = ::requestUsbPermission,
                sendSupportEmail = ::sendSupportEmail,
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun requestUsbPermission(usbKey: String) {
        val device = viewModel.findUsbDevice(usbKey) ?: return
        requestedPermissionDeviceIds.remove(device.deviceId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(usbPermissionAction).setPackage(packageName),
            flags,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun requestPermissionsForConnectedAx3Devices() {
        usbManager.deviceList.values
            .filter { it.vendorId == ax3VendorId && it.productId == ax3ProductId }
            .forEach { maybeRequestPermission(it) }
    }

    private fun sendSupportEmail(subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, "Send email"))
    }

    private fun maybeRequestPermission(device: UsbDevice) {
        if (device.vendorId != ax3VendorId || device.productId != ax3ProductId) {
            return
        }
        if (usbManager.hasPermission(device)) {
            requestedPermissionDeviceIds.remove(device.deviceId)
            return
        }
        if (!requestedPermissionDeviceIds.add(device.deviceId)) {
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(usbPermissionAction).setPackage(packageName),
            flags,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    companion object {
        private const val usbPermissionAction = "org.openmovement.omgui.android.USB_PERMISSION"
        private const val ax3VendorId = 0x04d8
        private const val ax3ProductId = 0x0057
    }
}

