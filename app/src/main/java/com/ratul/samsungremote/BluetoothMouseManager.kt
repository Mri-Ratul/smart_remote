package com.ratul.samsungremote

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

@SuppressLint("MissingPermission")
class BluetoothHidMouseManager(private val context: Context) {

    private val tag = "BtHidMouseManager"
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    var connectedDevice: BluetoothDevice? = null
        private set

    var onConnectionChanged: ((Boolean, String) -> Unit)? = null

    // Standard HID Mouse Descriptor (Reports relative X, Y movement and 3 buttons)
    private val mouseDescriptor = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
        0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
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
        0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7f.toByte(), //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
        0x95.toByte(), 0x02.toByte(), //     REPORT_COUNT (2)
        0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
        0xc0.toByte(),                 //   END_COLLECTION
        0xc0.toByte()                  // END_COLLECTION
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            val devName = device?.name ?: "TV"
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                onConnectionChanged?.invoke(true, devName)
                Log.d(tag, "Paired & Connected as HID Mouse with $devName")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                onConnectionChanged?.invoke(false, devName)
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
            }
        }
    }

    fun initialize() {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Smart Remote Mouse",
            "Universal Smart Remote & Air Mouse",
            "Samsung Remote Pro",
            BluetoothHidDeviceAppSdpSettings.SUBCLASS1_COMBO,
            mouseDescriptor
        )
        hidDevice?.registerApp(sdp, null, null, context.mainExecutor, callback)
    }

    fun connectToDevice(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        val dev = connectedDevice ?: return
        val clampedX = dx.coerceIn(-127, 127).toByte()
        val clampedY = dy.coerceIn(-127, 127).toByte()
        val report = byteArrayOf(0x00, clampedX, clampedY)
        hidDevice?.sendReport(dev, 0, report)
    }

    fun sendMouseClick() {
        val dev = connectedDevice ?: return
        // Mouse Left Down
        hidDevice?.sendReport(dev, 0, byteArrayOf(0x01, 0x00, 0x00))
        Handler(Looper.getMainLooper()).postDelayed({
            // Mouse Left Up
            hidDevice?.sendReport(dev, 0, byteArrayOf(0x00, 0x00, 0x00))
        }, 50)
    }

    fun destroy() {
        try {
            hidDevice?.unregisterApp()
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (_: Exception) {}
    }
}
