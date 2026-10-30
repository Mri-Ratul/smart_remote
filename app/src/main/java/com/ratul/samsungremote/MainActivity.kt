package com.ratul.samsungremote

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvManager: SamsungTvManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvManager = SamsungTvManager(this)

        val tvStatus = findViewById<TextView>(R.id.tvTvStatus)
        val btnScan = findViewById<Button>(R.id.btnScan)

        tvManager.onStatusChanged = { status ->
            runOnUiThread { tvStatus.text = status }
        }

        tvManager.onTvDiscovered = { ip ->
            runOnUiThread {
                Toast.makeText(this, "Found TV at $ip! Connecting...", Toast.LENGTH_SHORT).show()
            }
        }

        btnScan.setOnClickListener {
            tvManager.autoDiscoverTv()
        }

        // অ্যাপ অন হলেই স্বয়ংক্রিয়ভাবে লোকাল নেটওয়ার্কে টিভি খুঁজবে
        tvManager.autoDiscoverTv()

        // 1. Top Bar
        findViewById<Button>(R.id.btnPower).setOnClickListener { tvManager.sendKey("KEY_POWER") }
        findViewById<Button>(R.id.btnSource).setOnClickListener { tvManager.sendKey("KEY_SOURCE") }

        // 2. Apps & Quick Access
        findViewById<Button>(R.id.btnYouTube).setOnClickListener { tvManager.launchYouTube() }
        findViewById<Button>(R.id.btnChList).setOnClickListener { tvManager.sendKey("KEY_CHLIST") }
        findViewById<Button>(R.id.btnPreCh).setOnClickListener { tvManager.sendKey("KEY_PRECH") }

        // 3. Volume & Channel
        findViewById<Button>(R.id.btnVolUp).setOnClickListener { tvManager.sendKey("KEY_VOLUP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { tvManager.sendKey("KEY_VOLDOWN") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { tvManager.sendKey("KEY_MUTE") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { tvManager.sendKey("KEY_CHUP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { tvManager.sendKey("KEY_CHDOWN") }

        // 4. Utility Row
        findViewById<Button>(R.id.btnSettings).setOnClickListener { tvManager.sendKey("KEY_MENU") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tvManager.sendKey("KEY_HOME") }
        findViewById<Button>(R.id.btnGuide).setOnClickListener { tvManager.sendKey("KEY_GUIDE") }

        // 5. D-Pad Cluster
        findViewById<Button>(R.id.btnUp).setOnClickListener { tvManager.sendKey("KEY_UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { tvManager.sendKey("KEY_DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { tvManager.sendKey("KEY_LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { tvManager.sendKey("KEY_RIGHT") }
        findViewById<Button>(R.id.btnEnter).setOnClickListener { tvManager.sendKey("KEY_ENTER") }

        findViewById<Button>(R.id.btnInfo).setOnClickListener { tvManager.sendKey("KEY_INFO") }
        findViewById<Button>(R.id.btnEManual).setOnClickListener { tvManager.sendKey("KEY_CONTENTS") }
        findViewById<Button>(R.id.btnReturn).setOnClickListener { tvManager.sendKey("KEY_RETURN") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { tvManager.sendKey("KEY_EXIT") }

        // 6. Media Playback Controls
        findViewById<Button>(R.id.btnPlay).setOnClickListener { tvManager.sendKey("KEY_PLAY") }
        findViewById<Button>(R.id.btnPause).setOnClickListener { tvManager.sendKey("KEY_PAUSE") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { tvManager.sendKey("KEY_STOP") }
        findViewById<Button>(R.id.btnRewind).setOnClickListener { tvManager.sendKey("KEY_REWIND") }
        findViewById<Button>(R.id.btnFastForward).setOnClickListener { tvManager.sendKey("KEY_FF") }

        // 7. Navigation Tabs (View Switchers)
        findViewById<Button>(R.id.navButtons).setOnClickListener {
            Toast.makeText(this, "Buttons Remote Active", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.navTouchpad).setOnClickListener {
            Toast.makeText(this, "Touchpad View (Next Step)", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.navAirMouse).setOnClickListener {
            Toast.makeText(this, "Air Mouse (Next Step)", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.navAiVoice).setOnClickListener {
            Toast.makeText(this, "AI Voice (Next Step)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tvManager.disconnect()
    }
}
