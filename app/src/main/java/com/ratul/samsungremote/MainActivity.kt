package com.ratul.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvManager: SamsungTvManager
    private var btMouseManager: BluetoothMouseManager? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var isAirMouseActive = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var tvStatus: TextView
    private lateinit var containerButtons: ScrollView
    private lateinit var containerTouchpad: View
    private lateinit var containerAirMouse: View
    private lateinit var containerAiVoice: View

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

        tvStatus = findViewById(R.id.tvTvStatus)
        containerButtons = findViewById(R.id.containerButtons)
        containerTouchpad = findViewById(R.id.containerTouchpad)
        containerAirMouse = findViewById(R.id.containerAirMouse)
        containerAiVoice = findViewById(R.id.containerAiVoice)

        tvManager.onStatusChanged = { status ->
            runOnUiThread {
                val ip = tvManager.getSavedIp()
                tvStatus.text = if (ip.isNotEmpty()) "$status ($ip)" else status
            }
        }

        tvManager.onTvDiscovered = { ip ->
            runOnUiThread {
                Toast.makeText(this, "Discovered TV at $ip", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Scan Network for TV")
            popup.menu.add("Enter IP Manually")
            popup.menu.add("Disconnect TV")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Scan Network for TV" -> tvManager.autoDiscoverTv()
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
        setupTouchpad()
        setupAirMouse()
        setupFreeVoiceInput()
        setupTabSwitching()
        checkPermissions()
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.0.105"
            setText(tvManager.getSavedIp())
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Enter TV IP Address")
            .setMessage("Connect to any specific TV on your Wi-Fi:")
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

    // ফ্রি গুগল ভয়েস ইনপুট (কোনো API Key লাগবে না)
    private fun setupFreeVoiceInput() {
        val btnMicTrigger = findViewById<Button>(R.id.btnMicTrigger)
        val tvVoiceFeedback = findViewById<TextView>(R.id.tvVoiceFeedback)
        val etTranscribedText = findViewById<EditText>(R.id.etTranscribedText)
        val btnSendVoiceToTv = findViewById<Button>(R.id.btnSendVoiceToTv)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvVoiceFeedback.text = "Listening... Speak now"
                btnMicTrigger.text = "🔴\nLISTENING"
            }

            override fun onResults(results: Bundle?) {
                btnMicTrigger.text = "🎙\nTAP TO\nSPEAK"
                tvVoiceFeedback.text = "Voice recognized!"
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    etTranscribedText.setText(text)
                }
            }

            override fun onError(error: Int) {
                btnMicTrigger.text = "🎙\nTAP TO\nSPEAK"
                tvVoiceFeedback.text = "Try again (Tap to speak)"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvVoiceFeedback.text = "Processing..."
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        btnMicTrigger.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 202)
                return@setOnClickListener
            }
            speechRecognizer?.startListening(speechIntent)
        }

        btnSendVoiceToTv.setOnClickListener {
            val textToSend = etTranscribedText.text.toString().trim()
            if (textToSend.isNotEmpty()) {
                tvManager.sendText(textToSend)
                Toast.makeText(this, "Sent: \"$textToSend\"", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No text to send!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpad() {
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAirMouse() {
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
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPower).setOnClickListener { tvManager.sendKey("KEY_POWER") }
        findViewById<Button>(R.id.btnSource).setOnClickListener { tvManager.sendKey("KEY_SOURCE") }

        findViewById<Button>(R.id.btnTtxMix).setOnClickListener { tvManager.sendKey("KEY_TTX_MIX") }
        findViewById<Button>(R.id.btnYouTube).setOnClickListener { tvManager.launchYouTube() }
        findViewById<Button>(R.id.btnPreCh).setOnClickListener { tvManager.sendKey("KEY_PRECH") }

        findViewById<Button>(R.id.btnVolUp).setOnClickListener { tvManager.sendKey("KEY_VOLUP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { tvManager.sendKey("KEY_VOLDOWN") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { tvManager.sendKey("KEY_MUTE") }
        findViewById<Button>(R.id.btnChList).setOnClickListener { tvManager.sendKey("KEY_CHLIST") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { tvManager.sendKey("KEY_CHUP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { tvManager.sendKey("KEY_CHDOWN") }

        findViewById<Button>(R.id.btnSettings).setOnClickListener { tvManager.sendKey("KEY_MENU") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tvManager.sendKey("KEY_HOME") }
        findViewById<Button>(R.id.btnGuide).setOnClickListener { tvManager.sendKey("KEY_GUIDE") }

        findViewById<Button>(R.id.btnEManual).setOnClickListener { tvManager.sendKey("KEY_CONTENTS") }
        findViewById<Button>(R.id.btnInfo).setOnClickListener { tvManager.sendKey("KEY_INFO") }

        findViewById<Button>(R.id.btnUp).setOnClickListener { tvManager.sendKey("KEY_UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { tvManager.sendKey("KEY_DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { tvManager.sendKey("KEY_LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { tvManager.sendKey("KEY_RIGHT") }
        findViewById<Button>(R.id.btnEnter).setOnClickListener { tvManager.sendKey("KEY_ENTER") }

        findViewById<Button>(R.id.btnReturn).setOnClickListener { tvManager.sendKey("KEY_RETURN") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { tvManager.sendKey("KEY_EXIT") }

        findViewById<Button>(R.id.btnPicSize).setOnClickListener { tvManager.sendKey("KEY_PICTURE_SIZE") }
        findViewById<Button>(R.id.btnPicture).setOnClickListener { tvManager.sendKey("KEY_PICTURE_MODE") }
        findViewById<Button>(R.id.btnAdSubt).setOnClickListener { tvManager.sendKey("KEY_SUBTITLE") }
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
        val navAiVoice = findViewById<Button>(R.id.navAiVoice)

        fun resetNavColors() {
            navButtons.setBackgroundColor(0xFF131620.toInt())
            navTouchpad.setBackgroundColor(0xFF131620.toInt())
            navAirMouse.setBackgroundColor(0xFF131620.toInt())
            navAiVoice.setBackgroundColor(0xFF131620.toInt())
            navButtons.setTextColor(0xFF8892B0.toInt())
            navTouchpad.setTextColor(0xFF8892B0.toInt())
            navAirMouse.setTextColor(0xFF8892B0.toInt())
            navAiVoice.setTextColor(0xFF8892B0.toInt())
        }

        navButtons.setOnClickListener {
            resetNavColors()
            navButtons.setBackgroundColor(0xFF1D2230.toInt())
            navButtons.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.VISIBLE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.GONE
            containerAiVoice.visibility = View.GONE
        }

        navTouchpad.setOnClickListener {
            resetNavColors()
            navTouchpad.setBackgroundColor(0xFF1D2230.toInt())
            navTouchpad.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.VISIBLE
            containerAirMouse.visibility = View.GONE
            containerAiVoice.visibility = View.GONE
        }

        navAirMouse.setOnClickListener {
            resetNavColors()
            navAirMouse.setBackgroundColor(0xFF1D2230.toInt())
            navAirMouse.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.VISIBLE
            containerAiVoice.visibility = View.GONE
        }

        navAiVoice.setOnClickListener {
            resetNavColors()
            navAiVoice.setBackgroundColor(0xFF1D2230.toInt())
            navAiVoice.setTextColor(0xFF00E5FF.toInt())
            containerButtons.visibility = View.GONE
            containerTouchpad.visibility = View.GONE
            containerAirMouse.visibility = View.GONE
            containerAiVoice.visibility = View.VISIBLE
        }
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            startBluetooth()
        }
    }

    private fun startBluetooth() {
        try {
            btMouseManager = BluetoothMouseManager(this).apply {
                init()
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startBluetooth()
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
        speechRecognizer?.destroy()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        tvManager.disconnect()
        btMouseManager?.cleanup()
    }
}
