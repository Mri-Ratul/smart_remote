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

    // Standard HID Report Descriptor for Mouse
    private val hidReportDescriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
        0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        0x85.toByte(), 0x01.toByte(), //   REPORT_ID (1)
        0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
        0xa1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
        0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
        0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
        0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs)
        0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
        0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
        0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs)
        0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     USAGE (X)
        0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
        0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
        0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7f.toByte(), //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
        0xc0.toByte(),                //   END_COLLECTION
        0xc0.toByte()                 // END_COLLECTION
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
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bluetoothAdapter?.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerApp() {
        // 0x02.toByte() represents SUBCLASS_MOUSE in Bluetooth HID profile specification
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
    }

    fun sendMouseMovement(dx: Int, dy: Int, leftButton: Boolean = false, rightButton: Boolean = false) {
        val device = hostDevice ?: return
        val clampedX = dx.coerceIn(-127, 127).toByte()
        val clampedY = dy.coerceIn(-127, 127).toByte()

        var buttons: Byte = 0
        if (leftButton) buttons = (buttons.toInt() or 0x01).toByte()
        if (rightButton) buttons = (buttons.toInt() or 0x02).toByte()

        val report = byteArrayOf(buttons, clampedX, clampedY, 0)
        hidDevice?.sendReport(device, 1, report)
    }

    fun sendMouseClick(isRightClick: Boolean = false) {
        val device = hostDevice ?: return
        val buttonByte = if (isRightClick) 0x02.toByte() else 0x01.toByte()

        // Button Down
        hidDevice?.sendReport(device, 1, byteArrayOf(buttonByte, 0, 0, 0))
        // Button Up
        hidDevice?.sendReport(device, 1, byteArrayOf(0, 0, 0, 0))
    }

    fun cleanup() {
        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
    }
}
