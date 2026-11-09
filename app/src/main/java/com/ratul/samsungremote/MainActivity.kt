package com.ratul.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import kotlin.math.abs

object SamsungKeyMap {
    const val POWER = "KEY_POWER"
    const val SOURCE = "KEY_SOURCE"
    const val NUM_0 = "KEY_0"
    const val NUM_1 = "KEY_1"
    const val NUM_2 = "KEY_2"
    const val NUM_3 = "KEY_3"
    const val NUM_4 = "KEY_4"
    const val NUM_5 = "KEY_5"
    const val NUM_6 = "KEY_6"
    const val NUM_7 = "KEY_7"
    const val NUM_8 = "KEY_8"
    const val NUM_9 = "KEY_9"
    const val TTX_MIX = "KEY_TTX_MIX"
    const val PRE_CH = "KEY_PRECH"
    const val VOL_UP = "KEY_VOLUP"
    const val VOL_DOWN = "KEY_VOLDOWN"
    const val MUTE = "KEY_MUTE"
    const val CH_LIST = "KEY_CHLIST"
    const val CH_UP = "KEY_CHUP"
    const val CH_DOWN = "KEY_CHDOWN"
    const val SETTINGS = "KEY_MENU"
    const val HOME = "KEY_HOME"
    const val GUIDE = "KEY_GUIDE"
    const val INFO = "KEY_INFO"
    const val UP = "KEY_UP"
    const val DOWN = "KEY_DOWN"
    const val LEFT = "KEY_LEFT"
    const val RIGHT = "KEY_RIGHT"
    const val ENTER = "KEY_ENTER"
    const val RETURN = "KEY_RETURN"
    const val EXIT = "KEY_EXIT"
    const val REWIND = "KEY_REWIND"
    const val PLAY = "KEY_PLAY"
    const val PAUSE = "KEY_PAUSE"
    const val STOP = "KEY_STOP"
    const val FAST_FORWARD = "KEY_FF"
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvManager: SamsungTvManager
    private var speechRecognizer: SpeechRecognizer? = null

    private lateinit var tvTvTitle: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var containerMainRemote: View
    private lateinit var containerTouchpadHub: View
    private lateinit var splashOverlay: View
    private lateinit var llAppsContainer: LinearLayout

    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPointerTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvManager = SamsungTvManager(this)

        tvTvTitle = findViewById(R.id.tvTvTitle)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        containerMainRemote = findViewById(R.id.containerMainRemote)
        containerTouchpadHub = findViewById(R.id.containerTouchpadHub)
        splashOverlay = findViewById(R.id.splashOverlay)
        llAppsContainer = findViewById(R.id.llAppsContainer)

        // স্প্ল্যাশ এনিমেশন
        CoroutineScope(Dispatchers.Main).launch {
            delay(1600)
            splashOverlay.animate().alpha(0f).setDuration(400).withEndAction {
                splashOverlay.visibility = View.GONE
            }
        }

        tvManager.onStatusChanged = { status ->
            runOnUiThread {
                tvConnectionStatus.text = status
            }
        }

        tvManager.onTvDiscovered = { ip ->
            runOnUiThread {
                tvTvTitle.text = "● [TV] Samsung 4 Series (32)"
                tvConnectionStatus.text = "Connected ($ip)"
            }
        }

        setupHeaderAndPairing()
        setupModeSwitcher()
        setupButtons()
        setupProcessMouseTouchpad()
        setupMediaControls()
        loadInstalledApps()
        checkVoicePermission()

        val savedIp = tvManager.getSavedIp()
        if (savedIp.isNotEmpty()) {
            tvManager.connect(savedIp)
        } else {
            tvManager.autoDiscoverTv()
        }
    }

    private fun setupHeaderAndPairing() {
        findViewById<Button>(R.id.btnPowerModern).setOnClickListener {
            tvManager.sendKey(SamsungKeyMap.POWER)
        }
        findViewById<Button>(R.id.btnSettingsMod).setOnClickListener {
            tvManager.sendKey(SamsungKeyMap.SETTINGS)
        }
        findViewById<Button>(R.id.btnExitMod).setOnClickListener {
            tvManager.sendKey(SamsungKeyMap.EXIT)
        }
        findViewById<Button>(R.id.btnPairSheet).setOnClickListener {
            showPairingBottomSheet()
        }
    }

    private fun showPairingBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_pairing, null, false)
        dialog.setContentView(view)

        val tvCardTitle = view.findViewById<TextView>(R.id.tvSheetTvName)
        val tvCardSub = view.findViewById<TextView>(R.id.tvSheetTvIp)
        val btnSheetConnect = view.findViewById<Button>(R.id.btnSheetConnect)
        val btnSheetScan = view.findViewById<Button>(R.id.btnSheetScan)
        val btnSheetManual = view.findViewById<Button>(R.id.btnSheetManual)
        val btnSheetDisconnect = view.findViewById<Button>(R.id.btnSheetDisconnect)

        val currentIp = tvManager.getSavedIp()
        if (currentIp.isNotEmpty()) {
            tvCardTitle.text = "[TV] Samsung 4 Series (32)"
            tvCardSub.text = "$currentIp • UA32N4300"
        }

        btnSheetConnect.setOnClickListener {
            val ip = tvManager.getSavedIp()
            if (ip.isNotEmpty()) tvManager.connect(ip)
            dialog.dismiss()
        }

        btnSheetScan.setOnClickListener {
            tvManager.autoDiscoverTv()
            dialog.dismiss()
        }

        btnSheetManual.setOnClickListener {
            dialog.dismiss()
            showManualIpDialog()
        }

        btnSheetDisconnect.setOnClickListener {
            tvManager.disconnect()
            tvConnectionStatus.text = "Disconnected"
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.0.101"
            setText(tvManager.getSavedIp())
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Enter TV IP Address")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) tvManager.connect(ip)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupModeSwitcher() {
        val tabButtons = findViewById<Button>(R.id.tabButtons)
        val tabTouchpad = findViewById<Button>(R.id.tabTouchpad)

        tabButtons.setOnClickListener {
            tabButtons.setBackgroundResource(R.drawable.bg_dpad_center)
            tabButtons.setTextColor(0xFFFFFFFF.toInt())
            tabTouchpad.setBackgroundResource(android.R.color.transparent)
            tabTouchpad.setTextColor(0xFF64748B.toInt())

            containerMainRemote.visibility = View.VISIBLE
            containerTouchpadHub.visibility = View.GONE
        }

        tabTouchpad.setOnClickListener {
            tabTouchpad.setBackgroundResource(R.drawable.bg_dpad_center)
            tabTouchpad.setTextColor(0xFFFFFFFF.toInt())
            tabButtons.setBackgroundResource(android.R.color.transparent)
            tabButtons.setTextColor(0xFF64748B.toInt())

            containerMainRemote.visibility = View.GONE
            containerTouchpadHub.visibility = View.VISIBLE
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnChUpMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.CH_UP) }
        findViewById<Button>(R.id.btnChDownMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.CH_DOWN) }
        findViewById<Button>(R.id.btnVolUpMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.VOL_UP) }
        findViewById<Button>(R.id.btnVolDownMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.VOL_DOWN) }

        findViewById<Button>(R.id.btnDpadUp).setOnClickListener { tvManager.sendKey(SamsungKeyMap.UP) }
        findViewById<Button>(R.id.btnDpadDown).setOnClickListener { tvManager.sendKey(SamsungKeyMap.DOWN) }
        findViewById<Button>(R.id.btnDpadLeft).setOnClickListener { tvManager.sendKey(SamsungKeyMap.LEFT) }
        findViewById<Button>(R.id.btnDpadRight).setOnClickListener { tvManager.sendKey(SamsungKeyMap.RIGHT) }
        findViewById<Button>(R.id.btnDpadOk).setOnClickListener { tvManager.sendKey(SamsungKeyMap.ENTER) }

        findViewById<Button>(R.id.btnBackMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.RETURN) }
        findViewById<Button>(R.id.btnHomeMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.HOME) }
        findViewById<Button>(R.id.btnQuickMenuMod).setOnClickListener { tvManager.sendKey("KEY_TOOLS") }

        findViewById<Button>(R.id.btnNumpadTrigger).setOnClickListener { showNumpadDialog() }
        findViewById<Button>(R.id.btnVoiceKeyboardTrigger).setOnClickListener { showVoiceInputDialog() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupProcessMouseTouchpad() {
        val mouseSurface = findViewById<View>(R.id.modernMouseSurface)
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
                        val dx = ((event.x - lastPointerX) * 2.2f).toInt()
                        val dy = ((event.y - lastPointerY) * 2.2f).toInt()

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
                    if (diffX < 10 && diffY < 10) {
                        tvManager.sendWifiMouseClick()
                    }
                    true
                }
                else -> false
            }
        }

        findViewById<Button>(R.id.btnTouchpadLeftClick).setOnClickListener {
            tvManager.sendWifiMouseClick()
        }
    }

    private fun setupMediaControls() {
        findViewById<Button>(R.id.btnRewindMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.REWIND) }
        findViewById<Button>(R.id.btnPlayMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.PLAY) }
        findViewById<Button>(R.id.btnPauseMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.PAUSE) }
        findViewById<Button>(R.id.btnStopMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.STOP) }
        findViewById<Button>(R.id.btnFastForwardMod).setOnClickListener { tvManager.sendKey(SamsungKeyMap.FAST_FORWARD) }

        // Action Scene Volume Drop: ৮০ থেকে ৫০-এ নামানো
        findViewById<Button>(R.id.btnCustomSoundShift).setOnClickListener {
            Toast.makeText(this, "Action Scene: Dropping volume to 50", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                repeat(30) {
                    tvManager.sendKey(SamsungKeyMap.VOL_DOWN)
                    delay(35)
                }
            }
        }
    }

    private fun loadInstalledApps() {
        tvManager.fetchInstalledApps { apps ->
            llAppsContainer.removeAllViews()
            for (app in apps) {
                val appCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setPadding(10, 10, 10, 10)
                    setBackgroundResource(R.drawable.bg_neumorphic_card)
                    elevation = 4f
                }

                val appBtn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110)
                    setBackgroundResource(R.drawable.bg_dpad_center)
                    text = when {
                        app.name.contains("YouTube", true) -> "▶"
                        app.name.contains("Netflix", true) -> "N"
                        app.name.contains("Prime", true) -> "prime"
                        app.name.contains("Browser", true) -> "🌐"
                        else -> "📺"
                    }
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 18f
                    setOnClickListener {
                        tvManager.launchApp(app.id)
                        Toast.makeText(this@MainActivity, "Launching ${app.name}...", Toast.LENGTH_SHORT).show()
                    }
                }

                val appTitle = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    text = app.name
                    textSize = 10f
                    setTextColor(0xFF1E293B.toInt())
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 1
                    setPadding(0, 6, 0, 0)
                }

                appCard.addView(appBtn)
                appCard.addView(appTitle)
                llAppsContainer.addView(appCard)
            }
        }
    }

    private fun showNumpadDialog() {
        val numKeys = arrayOf(
            SamsungKeyMap.NUM_1, SamsungKeyMap.NUM_2, SamsungKeyMap.NUM_3,
            SamsungKeyMap.NUM_4, SamsungKeyMap.NUM_5, SamsungKeyMap.NUM_6,
            SamsungKeyMap.NUM_7, SamsungKeyMap.NUM_8, SamsungKeyMap.NUM_9,
            SamsungKeyMap.TTX_MIX, SamsungKeyMap.NUM_0, SamsungKeyMap.PRE_CH
        )
        val labels = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "TTX", "0", "PRE-CH")

        AlertDialog.Builder(this)
            .setTitle("Number Pad")
            .setItems(labels) { _, which ->
                tvManager.sendKey(numKeys[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVoiceInputDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_input, null)
        val etText = dialogView.findViewById<EditText>(R.id.etDialogVoiceText)
        val btnBn = dialogView.findViewById<Button>(R.id.btnDialogBn)
        val btnEn = dialogView.findViewById<Button>(R.id.btnDialogEn)
        val btnSend = dialogView.findViewById<Button>(R.id.btnDialogSend)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnBn.setOnClickListener { startListening(etText, "bn-BD") }
        btnEn.setOnClickListener { startListening(etText, "en-US") }

        btnSend.setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isNotEmpty()) {
                tvManager.sendText(text)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun startListening(target: EditText, lang: String) {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) target.setText(matches[0])
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onError(error: Int) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun checkVoicePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tvManager.disconnect()
    }
}
