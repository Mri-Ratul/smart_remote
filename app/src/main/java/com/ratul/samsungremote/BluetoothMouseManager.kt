package com.ratul.samsungremote

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log

@SuppressLint("MissingPermission")
class BluetoothMouseManager(private val context: Context) {

    private val tag = "BluetoothMouseManager"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null

    var onStatusChanged: ((String) -> Unit)? = null

    private val hidReportDescriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x02.toByte(),
        0xa1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x01.toByte(),
        0xa1.toByte(), 0x00.toByte(),
        0x05.toByte(), 0x09.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x03.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x05.toByte(),
        0x81.toByte(), 0x03.toByte(),
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x30.toByte(),
        0x09.toByte(), 0x31.toByte(),
        0x09.toByte(), 0x38.toByte(),
        0x15.toByte(), 0x81.toByte(),
        0x25.toByte(), 0x7f.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x03.toByte(),
        0x81.toByte(), 0x06.toByte(),
        0xc0.toByte(),
        0xc0.toByte()
    )

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                onStatusChanged?.invoke("Bluetooth Ready: Pair with TV")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    onStatusChanged?.invoke("Mouse Connected to ${device?.name ?: "TV"}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    hostDevice = null
                    onStatusChanged?.invoke("Mouse Disconnected")
                }
            }
        }
    }

    fun init() {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = manager.adapter
            bluetoothAdapter?.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            Log.e(tag, "Bluetooth init failed", e)
        }
    }

    private fun registerApp() {
        try {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                "Smart Remote Mouse",
                "Samsung TV Controller",
                "Ratul",
                0x02.toByte(),
                hidReportDescriptor
            )
            val qosSettings = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, -1
            )
            hidDevice?.registerApp(sdpSettings, null, qosSettings, context.mainExecutor, hidCallback)
        } catch (e: Exception) {
            Log.e(tag, "Register App failed", e)
        }
    }

    fun sendMouseMovement(dx: Int, dy: Int, leftButton: Boolean = false, rightButton: Boolean = false) {
        val device = hostDevice ?: return
        val clampedX = dx.coerceIn(-127, 127).toByte()
        val clampedY = dy.coerceIn(-127, 127).toByte()

        var buttons: Byte = 0
        if (leftButton) buttons = (buttons.toInt() or 0x01).toByte()
        if (rightButton) buttons = (buttons.toInt() or 0x02).toByte()

        val report = byteArrayOf(buttons, clampedX, clampedY, 0)
        try {
            hidDevice?.sendReport(device, 1, report)
        } catch (_: Exception) {}
    }

    fun sendMouseClick(isRightClick: Boolean = false) {
        val device = hostDevice ?: return
        val buttonByte = if (isRightClick) 0x02.toByte() else 0x01.toByte()

        try {
            hidDevice?.sendReport(device, 1, byteArrayOf(buttonByte, 0, 0, 0))
            hidDevice?.sendReport(device, 1, byteArrayOf(0, 0, 0, 0))
        } catch (_: Exception) {}
    }

    fun cleanup() {
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {}
    }
}
