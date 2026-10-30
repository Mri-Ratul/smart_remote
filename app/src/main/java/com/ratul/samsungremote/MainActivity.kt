package com.ratul.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvManager: SamsungTvManager
    private var btMouseManager: BluetoothMouseManager? = null

    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var isAirMouseActive = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var containerButtons: ScrollView
    private lateinit var containerTouchpad: View
    private lateinit var containerAirMouse: View

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("samsung_remote_lock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        tvManager = SamsungTvManager(this)

        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        } catch (_: Exception) {}

        val tvStatus = findViewById<TextView>(R.id.tvTvStatus)
        val btnScan = findViewById<Button>(R.id.btnScan)

        containerButtons = findViewById(R.id.containerButtons)
        containerTouchpad = findViewById(R.id.containerTouchpad)
        containerAirMouse = findViewById(R.id.containerAirMouse)

        tvManager.onStatusChanged = { status ->
            runOnUiThread { tvStatus.text = status }
        }

        tvManager.onTvDiscovered = { ip ->
            runOnUiThread {
                Toast.makeText(this, "Found TV at $ip! Connecting...", Toast.LENGTH_SHORT).show()
            }
        }

        // স্ক্যান ফেইল করলে অটো ডায়ালগ ওপেন হবে
        tvManager.onScanFailed = {
            runOnUiThread {
                showManualIpDialog()
            }
        }

        // Scan বাটন লং-প্রেস করলে বা টিভি স্ট্যাটাসে ট্যাপ করলেও সরাসরি ম্যানুয়াল ইনপুট ডায়ালগ আসবে
        btnScan.setOnClickListener { tvManager.autoDiscoverTv() }
        btnScan.setOnLongClickListener {
            showManualIpDialog()
            true
        }
        tvStatus.setOnClickListener {
            showManualIpDialog()
        }

        val savedIp = tvManager.getSavedIp()
        if (savedIp.isNotEmpty()) {
            tvManager.connect(savedIp)
        } else {
            tvManager.autoDiscoverTv()
        }

        setupClassicButtons()
        checkBluetoothPermissionsAndInit()

        // Touchpad
        val mouseSurface = findViewById<View>(R.id.mouseSurface)
        mouseSurface.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ((event.x - lastTouchX) * 1.5f).toInt()
                    val dy = ((event.y - lastTouchY) * 1.5f).toInt()
                    if (dx != 0 || dy != 0) {
                        btMouseManager?.sendMouseMovement(dx, dy)
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            true
        }

        findViewById<Button>(R.id.btnLeftClick).setOnClickListener {
            btMouseManager?.sendMouseClick(isRightClick = false)
        }
        findViewById<Button>(R.id.btnRightClick).setOnClickListener {
            btMouseManager?.sendMouseClick(isRightClick = true)
        }

        // Air Mouse
        val btnAirTrigger = findViewById<Button>(R.id.btnAirTrigger)
        btnAirTrigger.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isAirMouseActive = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isAirMouseActive = false
                    true
                }
                else -> false
            }
        }

        findViewById<Button>(R.id.btnAirClick).setOnClickListener {
            btMouseManager?.sendMouseClick(isRightClick = false)
        }

        setupTabSwitching()
    }

    // ম্যানুয়াল IP ডায়ালগ: যেখানে ইউজার সরাসরি IP বসাতে পারবে
    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.0.105"
            setText(tvManager.getSavedIp())
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Samsung TV IP Address")
            .setMessage("Enter the local IP address of your TV:")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    tvManager.connect(ip)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkBluetoothPermissionsAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissionsNeeded = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }

            if (permissionsNeeded.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 101)
                return
            }
        }
        startBluetoothMouse()
    }

    private fun startBluetoothMouse() {
        try {
            btMouseManager = BluetoothMouseManager(this).apply {
                onStatusChanged = { status ->
                    runOnUiThread { Toast.makeText(this@MainActivity, status, Toast.LENGTH_SHORT).show() }
                }
                init()
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBluetoothMouse()
            }
        }
    }

    private fun setupTabSwitching() {
        val navButtons = findViewById<Button>(R.id.navButtons)
        val navTouchpad = findViewById<Button>(R.id.navTouchpad)
        val navAirMouse = findViewById<Button>(R.id.navAirMouse)
        val navAiVoice = findViewById<Button>(R.id.navAiVoice)

        fun resetNavColors() {
            navButtons.setBackgroundColor(0xFF12141A.toInt())
            navTouchpad.setBackgroundColor(0xFF12141A.toInt())
            navAirMouse.setBackgroundColor(0xFF12141A.toInt())
            navAiVoice.setBackgroundColor(0xFF12141A.toInt())
            navButtons.setTextColor(0xFF9CA3AF.toInt())
            navTouchpad.setTextColor(0xFF9CA3AF.toInt())
            navAirMouse.setTextColor(0xFF9CA3AF.toInt())
            navAiVoice.setTextColor(0xFF9CA3AF.toInt())
        }

        navButtons.setOnClickListener {
            resetNavColors()
            navButtons.setBackgroundColor(0xFF1E2330.toInt())
            navButtons.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.VISIBLE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.GONE
        }

        navTouchpad.setOnClickListener {
            resetNavColors()
            navTouchpad.setBackgroundColor(0xFF1E2330.toInt())
            navTouchpad.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.VISIBLE
            containerAirMouse.visibility = View.GONE
        }

        navAirMouse.setOnClickListener {
            resetNavColors()
            navAirMouse.setBackgroundColor(0xFF1E2330.toInt())
            navAirMouse.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.VISIBLE
        }

        navAiVoice.setOnClickListener {
            Toast.makeText(this, "AI Voice Tab Coming Up Next!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClassicButtons() {
        findViewById<Button>(R.id.btnPower).setOnClickListener { tvManager.sendKey("KEY_POWER") }
        findViewById<Button>(R.id.btnSource).setOnClickListener { tvManager.sendKey("KEY_SOURCE") }
        findViewById<Button>(R.id.btnYouTube).setOnClickListener { tvManager.launchYouTube() }
        findViewById<Button>(R.id.btnChList).setOnClickListener { tvManager.sendKey("KEY_CHLIST") }
        findViewById<Button>(R.id.btnPreCh).setOnClickListener { tvManager.sendKey("KEY_PRECH") }

        findViewById<Button>(R.id.btnVolUp).setOnClickListener { tvManager.sendKey("KEY_VOLUP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { tvManager.sendKey("KEY_VOLDOWN") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { tvManager.sendKey("KEY_MUTE") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { tvManager.sendKey("KEY_CHUP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { tvManager.sendKey("KEY_CHDOWN") }

        findViewById<Button>(R.id.btnSettings).setOnClickListener { tvManager.sendKey("KEY_MENU") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tvManager.sendKey("KEY_HOME") }
        findViewById<Button>(R.id.btnGuide).setOnClickListener { tvManager.sendKey("KEY_GUIDE") }

        findViewById<Button>(R.id.btnUp).setOnClickListener { tvManager.sendKey("KEY_UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { tvManager.sendKey("KEY_DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { tvManager.sendKey("KEY_LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { tvManager.sendKey("KEY_RIGHT") }
        findViewById<Button>(R.id.btnEnter).setOnClickListener { tvManager.sendKey("KEY_ENTER") }

        findViewById<Button>(R.id.btnInfo).setOnClickListener { tvManager.sendKey("KEY_INFO") }
        findViewById<Button>(R.id.btnEManual).setOnClickListener { tvManager.sendKey("KEY_CONTENTS") }
        findViewById<Button>(R.id.btnReturn).setOnClickListener { tvManager.sendKey("KEY_RETURN") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { tvManager.sendKey("KEY_EXIT") }

        findViewById<Button>(R.id.btnRewind).setOnClickListener { tvManager.sendKey("KEY_REWIND") }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { tvManager.sendKey("KEY_PLAY") }
        findViewById<Button>(R.id.btnPause).setOnClickListener { tvManager.sendKey("KEY_PAUSE") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { tvManager.sendKey("KEY_STOP") }
        findViewById<Button>(R.id.btnFastForward).setOnClickListener { tvManager.sendKey("KEY_FF") }
    }

    override fun onResume() {
        super.onResume()
        gyroscopeSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isAirMouseActive) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val speedFactor = 15f
            val dx = (-event.values[2] * speedFactor).toInt()
            val dy = (-event.values[0] * speedFactor).toInt()
            if (dx != 0 || dy != 0) {
                btMouseManager?.sendMouseMovement(dx, dy)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        tvManager.disconnect()
        btMouseManager?.cleanup()
    }
}
