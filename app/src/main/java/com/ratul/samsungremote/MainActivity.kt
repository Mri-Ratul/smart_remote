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
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvManager: SamsungTvManager
    private var speechRecognizer: SpeechRecognizer? = null

    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var isAirMouseActive = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var tvStatus: TextView
    private lateinit var containerButtons: ScrollView
    private lateinit var containerTouchpad: View
    private lateinit var containerAirMouse: View
    private lateinit var containerTvApps: View
    private lateinit var containerAiVoice: View
    private lateinit var lvTvApps: ListView

    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerTime = 0L

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
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        } catch (_: Exception) {}

        tvStatus = findViewById(R.id.tvTvStatus)
        containerButtons = findViewById(R.id.containerButtons)
        containerTouchpad = findViewById(R.id.containerTouchpad)
        containerAirMouse = findViewById(R.id.containerAirMouse)
        containerTvApps = findViewById(R.id.containerTvApps)
        containerAiVoice = findViewById(R.id.containerAiVoice)
        lvTvApps = findViewById(R.id.lvTvApps)

        tvManager.onStatusChanged = { status ->
            runOnUiThread {
                val ip = tvManager.getSavedIp()
                tvStatus.text = if (ip.isNotEmpty()) "$status ($ip)" else status
            }
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("Scan Network for TV (Wi-Fi)")
            popup.menu.add("Enter IP Manually")
            popup.menu.add("Disconnect TV")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Scan Network for TV (Wi-Fi)" -> tvManager.autoDiscoverTv()
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
        setupWifiTouchpad()
        setupWifiAirMouse()
        setupDualVoiceInput()
        setupAppsList()
        setupTabSwitching()
        checkPermissions()
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

    // ওয়াই-ফাই মাউস টাচপ্যাড
    @SuppressLint("ClickableViewAccessibility")
    private fun setupWifiTouchpad() {
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
                    if (now - lastPointerTime > 16) {
                        val dx = ((event.x - lastPointerX) * 2.0f).toInt()
                        val dy = ((event.y - lastPointerY) * 2.0f).toInt()

                        if (dx != 0 || dy != 0) {
                            tvManager.sendWifiMouseMove(dx, dy)
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
                        tvManager.sendWifiMouseClick()
                    }
                    true
                }
                else -> false
            }
        }

        findViewById<Button>(R.id.btnLeftClick).setOnClickListener {
            tvManager.sendWifiMouseClick()
        }
        findViewById<Button>(R.id.btnRightClick).setOnClickListener {
            tvManager.sendKey("KEY_RETURN")
        }
    }

    // ওয়াই-ফাই এয়ার মাউস
    @SuppressLint("ClickableViewAccessibility")
    private fun setupWifiAirMouse() {
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
            tvManager.sendWifiMouseClick()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isAirMouseActive) return
        val now = System.currentTimeMillis()
        if (now - lastPointerTime > 20) {
            val rotZ = event.values.getOrNull(2) ?: 0f
            val rotX = event.values.getOrNull(0) ?: 0f

            val sensitivity = 20f
            val dx = (-rotZ * sensitivity).toInt()
            val dy = (-rotX * sensitivity).toInt()

            if (dx != 0 || dy != 0) {
                tvManager.sendWifiMouseMove(dx, dy)
                lastPointerTime = now
            }
        }
    }

    private fun setupAppsList() {
        val btnRefresh = findViewById<Button>(R.id.btnRefreshApps)

        fun loadApps() {
            tvManager.fetchInstalledApps { apps ->
                val appTitles = apps.map { it.name }
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

        // অতিরিক্ত এন্টার ছাড়া শুধু টেক্সট পুশ
        btnSendUniversal.setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isNotEmpty()) {
                tvManager.sendText(text)
                Toast.makeText(this, "Pushed: \"$text\"", Toast.LENGTH_SHORT).show()
            }
        }

        // ইউটিউব সার্চ
        btnSendYouTube.setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isNotEmpty()) {
                tvManager.launchYouTubeWithSearch(text)
                Toast.makeText(this, "Searching YouTube: \"$text\"", Toast.LENGTH_SHORT).show()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
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
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        tvManager.disconnect()
    }
}
