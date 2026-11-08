package com.ratul.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvManager: SamsungTvManager
    private lateinit var btMouseManager: BluetoothHidMouseManager
    private var speechRecognizer: SpeechRecognizer? = null

    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var isAirMouseActive = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvMouseModeInfo: TextView
    private lateinit var containerButtons: ScrollView
    private lateinit var containerTouchpad: View
    private lateinit var containerAirMouse: View
    private lateinit var containerTvApps: View
    private lateinit var containerAiVoice: View
    private lateinit var lvTvApps: ListView

    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerTime = 0L

    private val discoveredBtDevices = mutableListOf<BluetoothDevice>()

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_FOUND == intent?.action) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (device != null && discoveredBtDevices.none { it.address == device.address }) {
                    discoveredBtDevices.add(device)
                }
            }
        }
    }

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
        btMouseManager = BluetoothHidMouseManager(this)
        btMouseManager.initialize()

        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        } catch (_: Exception) {}

        tvStatus = findViewById(R.id.tvTvStatus)
        tvMouseModeInfo = findViewById(R.id.tvMouseModeInfo)
        containerButtons = findViewById(R.id.containerButtons)
        containerTouchpad = findViewById(R.id.containerTouchpad)
        containerAirMouse = findViewById(R.id.containerAirMouse)
        containerTvApps = findViewById(R.id.containerTvApps)
        containerAiVoice = findViewById(R.id.containerAiVoice)
        lvTvApps = findViewById(R.id.lvTvApps)

        tvManager.onStatusChanged = { status: String ->
            runOnUiThread {
                val ip = tvManager.getSavedIp()
                tvStatus.text = if (ip.isNotEmpty()) "$status ($ip)" else status
            }
        }

        btMouseManager.onConnectionChanged = { isConnected: Boolean, devName: String ->
            runOnUiThread {
                if (isConnected) {
                    tvMouseModeInfo.text = "CONNECTED VIA BLUETOOTH HID: $devName"
                    tvMouseModeInfo.setTextColor(0xFF10B981.toInt())
                    Toast.makeText(this, "Paired as Bluetooth Mouse with $devName", Toast.LENGTH_SHORT).show()
                } else {
                    tvMouseModeInfo.text = "BLUETOOTH MOUSE DISCONNECTED"
                    tvMouseModeInfo.setTextColor(0xFF00E5FF.toInt())
                }
            }
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Scan Network for TV (Wi-Fi)")
            popup.menu.add("Scan & Pair Bluetooth TV (Mouse)")
            popup.menu.add("Enter IP Manually")
            popup.menu.add("Disconnect TV")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Scan Network for TV (Wi-Fi)" -> tvManager.autoDiscoverTv()
                    "Scan & Pair Bluetooth TV (Mouse)" -> startBluetoothScan()
                    "Enter IP Manually" -> showManualIpDialog()
                    "Disconnect TV" -> {
                        tvManager.disconnect()
                        tvStatus.text = "Status: Disconnected"
                    }
                }
                true
            }
            popup.show()
        }

        val savedIp = tvManager.getSavedIp()
        if (savedIp.isNotEmpty()) {
            tvManager.connect(savedIp)
        } else {
            tvManager.autoDiscoverTv()
        }

        setupButtons()
        setupPureMouseTouchpad()
        setupPureAirMouse()
        setupDualVoiceInput()
        setupAppsList()
        setupTabSwitching()
        checkPermissions()

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(btReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        discoveredBtDevices.clear()
        adapter.startDiscovery()
        Toast.makeText(this, "Scanning Bluetooth Devices...", Toast.LENGTH_SHORT).show()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            adapter.cancelDiscovery()
            showBluetoothDevicesDialog()
        }, 4000)
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDevicesDialog() {
        if (discoveredBtDevices.isEmpty()) {
            Toast.makeText(this, "No Bluetooth devices found. Put TV in Pairing Mode.", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = discoveredBtDevices.map { "${it.name ?: "Unknown Device"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select TV to Pair as HID Remote")
            .setItems(deviceNames) { _, index ->
                val selectedDevice = discoveredBtDevices[index]
                Toast.makeText(this, "Pairing with ${selectedDevice.name}...", Toast.LENGTH_SHORT).show()
                btMouseManager.connectDevice(selectedDevice)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.0.101"
            setText(tvManager.getSavedIp())
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Enter TV IP Address")
            .setMessage("Do not add ports or http (just the IP):")
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPureMouseTouchpad() {
        val mouseSurface = findViewById<View>(R.id.mouseSurface)
        mouseSurface.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastPointerX = event.x
                    lastPointerY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastPointerTime > 15) {
                        val dx = ((event.x - lastPointerX) * 2.2f).toInt()
                        val dy = ((event.y - lastPointerY) * 2.2f).toInt()

                        if (dx != 0 || dy != 0) {
                            if (btMouseManager.connectedDevice != null) {
                                btMouseManager.sendMouseMove(dx, dy)
                            }
                            lastPointerX = event.x
                            lastPointerY = event.y
                            lastPointerTime = now
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.x - lastPointerX)
                    val diffY = abs(event.y - lastPointerY)
                    if (diffX < 8 && diffY < 8) {
                        if (btMouseManager.connectedDevice != null) {
                            btMouseManager.sendMouseClick()
                        } else {
                            tvManager.sendKey("KEY_ENTER")
                        }
                    }
                    true
                }
                else -> false
            }
        }

        findViewById<Button>(R.id.btnLeftClick).setOnClickListener {
            if (btMouseManager.connectedDevice != null) {
                btMouseManager.sendMouseClick()
            } else {
                tvManager.sendKey("KEY_ENTER")
            }
        }
        findViewById<Button>(R.id.btnRightClick).setOnClickListener {
            tvManager.sendKey("KEY_RETURN")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPureAirMouse() {
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
            if (btMouseManager.connectedDevice != null) {
                btMouseManager.sendMouseClick()
            } else {
                tvManager.sendKey("KEY_ENTER")
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isAirMouseActive) return
        val now = System.currentTimeMillis()
        if (now - lastPointerTime > 20) {
            val rotZ = event.values.getOrNull(2) ?: 0f
            val rotX = event.values.getOrNull(0) ?: 0f

            val sensitivity = 22f
            val dx = (-rotZ * sensitivity).toInt()
            val dy = (-rotX * sensitivity).toInt()

            if (dx != 0 || dy != 0) {
                if (btMouseManager.connectedDevice != null) {
                    btMouseManager.sendMouseMove(dx, dy)
                }
                lastPointerTime = now
            }
        }
    }

    private fun setupAppsList() {
        val btnRefresh = findViewById<Button>(R.id.btnRefreshApps)

        fun loadApps() {
            tvManager.fetchInstalledApps { apps: List<TvAppItem> ->
                val appTitles: List<String> = apps.map { it.name }
                val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, appTitles)
                lvTvApps.adapter = adapter

                lvTvApps.setOnItemClickListener { _, _, position, _ ->
                    val selected = apps[position]
                    tvManager.launchApp(selected.id)
                    Toast.makeText(this, "Launching ${selected.name}...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRefresh.setOnClickListener { loadApps() }
        loadApps()
    }

    private fun setupDualVoiceInput() {
        val btnBangla = findViewById<Button>(R.id.btnMicBangla)
        val btnEnglish = findViewById<Button>(R.id.btnMicEnglish)
        val tvFeedback = findViewById<TextView>(R.id.tvVoiceFeedback)
        val etText = findViewById<EditText>(R.id.etTranscribedText)
        val btnSendUniversal = findViewById<Button>(R.id.btnSendVoiceUniversal)
        val btnSendYouTube = findViewById<Button>(R.id.btnSendVoiceYouTube)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        fun listenVoice(langCode: String) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 202)
                return
            }

            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, langCode)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    tvFeedback.text = if (langCode == "bn-BD") "বাংলা শুনছি... বলুন" else "Listening in English... Speak"
                }

                override fun onResults(results: Bundle?) {
                    tvFeedback.text = "Voice recognized!"
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        etText.setText(matches[0])
                    }
                }

                override fun onError(error: Int) {
                    tvFeedback.text = "Voice not captured. Tap to try again."
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    tvFeedback.text = "Processing..."
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(speechIntent)
        }

        btnBangla.setOnClickListener { listenVoice("bn-BD") }
        btnEnglish.setOnClickListener { listenVoice("en-US") }

        btnSendUniversal.setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isNotEmpty()) {
                tvManager.sendText(text)
                tvManager.sendKey("KEY_ENTER")
                Toast.makeText(this, "Pushed to TV: \"$text\"", Toast.LENGTH_SHORT).show()
            }
        }

        btnSendYouTube.setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isNotEmpty()) {
                tvManager.launchYouTubeWithSearch(text)
                Toast.makeText(this, "Playing on YouTube: \"$text\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPower).setOnClickListener { tvManager.sendKey("KEY_POWER") }
        findViewById<Button>(R.id.btnSource).setOnClickListener { tvManager.sendKey("KEY_SOURCE") }
        findViewById<Button>(R.id.btnYouTube).setOnClickListener { tvManager.launchApp("111299001912") }

        findViewById<Button>(R.id.btnVolUp).setOnClickListener { tvManager.sendKey("KEY_VOLUP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { tvManager.sendKey("KEY_VOLDOWN") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { tvManager.sendKey("KEY_MUTE") }
        findViewById<Button>(R.id.btnChList).setOnClickListener { tvManager.sendKey("KEY_CHLIST") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { tvManager.sendKey("KEY_CHUP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { tvManager.sendKey("KEY_CHDOWN") }

        findViewById<Button>(R.id.btnSettings).setOnClickListener { tvManager.sendKey("KEY_MENU") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tvManager.sendKey("KEY_HOME") }

        findViewById<Button>(R.id.btnUp).setOnClickListener { tvManager.sendKey("KEY_UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { tvManager.sendKey("KEY_DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { tvManager.sendKey("KEY_LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { tvManager.sendKey("KEY_RIGHT") }
        findViewById<Button>(R.id.btnEnter).setOnClickListener { tvManager.sendKey("KEY_ENTER") }

        findViewById<Button>(R.id.btnReturn).setOnClickListener { tvManager.sendKey("KEY_RETURN") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { tvManager.sendKey("KEY_EXIT") }

        findViewById<Button>(R.id.btnPicture).setOnClickListener { tvManager.cyclePictureMode() }
        findViewById<Button>(R.id.btnPicSize).setOnClickListener { tvManager.togglePicSize() }
        findViewById<Button>(R.id.btnAdSubt).setOnClickListener { tvManager.toggleSubtitle() }

        findViewById<Button>(R.id.btnStop).setOnClickListener { tvManager.sendKey("KEY_STOP") }
        findViewById<Button>(R.id.btnRewind).setOnClickListener { tvManager.sendKey("KEY_REWIND") }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { tvManager.sendKey("KEY_PLAY") }
        findViewById<Button>(R.id.btnPause).setOnClickListener { tvManager.sendKey("KEY_PAUSE") }
        findViewById<Button>(R.id.btnFastForward).setOnClickListener { tvManager.sendKey("KEY_FF") }
    }

    private fun setupTabSwitching() {
        val navButtons = findViewById<Button>(R.id.navButtons)
        val navTouchpad = findViewById<Button>(R.id.navTouchpad)
        val navAirMouse = findViewById<Button>(R.id.navAirMouse)
        val navApps = findViewById<Button>(R.id.navApps)
        val navAiVoice = findViewById<Button>(R.id.navAiVoice)

        fun resetNavColors() {
            navButtons.setBackgroundColor(0xFF131620.toInt())
            navTouchpad.setBackgroundColor(0xFF131620.toInt())
            navAirMouse.setBackgroundColor(0xFF131620.toInt())
            navApps.setBackgroundColor(0xFF131620.toInt())
            navAiVoice.setBackgroundColor(0xFF131620.toInt())
            navButtons.setTextColor(0xFF8892B0.toInt())
            navTouchpad.setTextColor(0xFF8892B0.toInt())
            navAirMouse.setTextColor(0xFF8892B0.toInt())
            navApps.setTextColor(0xFF8892B0.toInt())
            navAiVoice.setTextColor(0xFF8892B0.toInt())
        }

        navButtons.setOnClickListener {
            resetNavColors()
            navButtons.setBackgroundColor(0xFF1D2230.toInt())
            navButtons.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.VISIBLE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.GONE
            containerTvApps.visibility = View.GONE
            containerAiVoice.visibility = View.GONE
        }

        navTouchpad.setOnClickListener {
            resetNavColors()
            navTouchpad.setBackgroundColor(0xFF1D2230.toInt())
            navTouchpad.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.VISIBLE
            containerAirMouse.visibility = View.GONE
            containerTvApps.visibility = View.GONE
            containerAiVoice.visibility = View.GONE
        }

        navAirMouse.setOnClickListener {
            resetNavColors()
            navAirMouse.setBackgroundColor(0xFF1D2230.toInt())
            navAirMouse.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.VISIBLE
            containerTvApps.visibility = View.GONE
            containerAiVoice.visibility = View.GONE
        }

        navApps.setOnClickListener {
            resetNavColors()
            navApps.setBackgroundColor(0xFF1D2230.toInt())
            navApps.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.GONE
            containerTvApps.visibility = View.VISIBLE
            containerAiVoice.visibility = View.GONE
        }

        navAiVoice.setOnClickListener {
            resetNavColors()
            navAiVoice.setBackgroundColor(0xFF1D2230.toInt())
            navAiVoice.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.GONE
            containerTvApps.visibility = View.GONE
            containerAiVoice.visibility = View.VISIBLE
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        try {
            unregisterReceiver(btReceiver)
        } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        btMouseManager.destroy()
        tvManager.disconnect()
    }
}
