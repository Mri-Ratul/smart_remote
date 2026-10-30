package com.ratul.samsungremote

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu

class MainActivity : AppCompatActivity() {

    private lateinit var tvManager: SamsungTvManager
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvManager = SamsungTvManager(this)
        tvStatus = findViewById(R.id.tvTvStatus)

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

        // Three-dot Action Menu Setup
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

        // অটো-কানেক্ট যদি আগে থেকে আইপি থাকে
        val savedIp = tvManager.getSavedIp()
        if (savedIp.isNotEmpty()) {
            tvManager.connect(savedIp)
        } else {
            tvManager.autoDiscoverTv()
        }

        setupButtons()
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

    private fun setupButtons() {
        // Row 1: Power & Source
        findViewById<Button>(R.id.btnPower).setOnClickListener { tvManager.sendKey("KEY_POWER") }
        findViewById<Button>(R.id.btnSource).setOnClickListener { tvManager.sendKey("KEY_SOURCE") }

        // Row 2: TTX, YouTube, Pre-CH
        findViewById<Button>(R.id.btnTtxMix).setOnClickListener { tvManager.sendKey("KEY_TTX_MIX") }
        findViewById<Button>(R.id.btnYouTube).setOnClickListener { tvManager.launchYouTube() }
        findViewById<Button>(R.id.btnPreCh).setOnClickListener { tvManager.sendKey("KEY_PRECH") }

        // Row 3: Vol, Mute, Ch, ChList
        findViewById<Button>(R.id.btnVolUp).setOnClickListener { tvManager.sendKey("KEY_VOLUP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { tvManager.sendKey("KEY_VOLDOWN") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { tvManager.sendKey("KEY_MUTE") }
        findViewById<Button>(R.id.btnChList).setOnClickListener { tvManager.sendKey("KEY_CHLIST") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { tvManager.sendKey("KEY_CHUP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { tvManager.sendKey("KEY_CHDOWN") }

        // Row 4: Settings, Home, Guide
        findViewById<Button>(R.id.btnSettings).setOnClickListener { tvManager.sendKey("KEY_MENU") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tvManager.sendKey("KEY_HOME") }
        findViewById<Button>(R.id.btnGuide).setOnClickListener { tvManager.sendKey("KEY_GUIDE") }

        // Row 5: E-Manual, Info
        findViewById<Button>(R.id.btnEManual).setOnClickListener { tvManager.sendKey("KEY_CONTENTS") }
        findViewById<Button>(R.id.btnInfo).setOnClickListener { tvManager.sendKey("KEY_INFO") }

        // Row 6: D-Pad
        findViewById<Button>(R.id.btnUp).setOnClickListener { tvManager.sendKey("KEY_UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { tvManager.sendKey("KEY_DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { tvManager.sendKey("KEY_LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { tvManager.sendKey("KEY_RIGHT") }
        findViewById<Button>(R.id.btnEnter).setOnClickListener { tvManager.sendKey("KEY_ENTER") }

        // Row 7: Return, Exit
        findViewById<Button>(R.id.btnReturn).setOnClickListener { tvManager.sendKey("KEY_RETURN") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { tvManager.sendKey("KEY_EXIT") }

        // Row 8: Pic Size, Picture, Ad/Subt, Stop
        findViewById<Button>(R.id.btnPicSize).setOnClickListener { tvManager.sendKey("KEY_PICTURE_SIZE") }
        findViewById<Button>(R.id.btnPicture).setOnClickListener { tvManager.sendKey("KEY_PICTURE_MODE") }
        findViewById<Button>(R.id.btnAdSubt).setOnClickListener { tvManager.sendKey("KEY_SUBTITLE") }
        findViewById<Button>(R.id.btnStop).setOnClickListener { tvManager.sendKey("KEY_STOP") }

        // Row 9: Media Controls
        findViewById<Button>(R.id.btnRewind).setOnClickListener { tvManager.sendKey("KEY_REWIND") }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { tvManager.sendKey("KEY_PLAY") }
        findViewById<Button>(R.id.btnPause).setOnClickListener { tvManager.sendKey("KEY_PAUSE") }
        findViewById<Button>(R.id.btnFastForward).setOnClickListener { tvManager.sendKey("KEY_FF") }
    }

    override fun onDestroy() {
        super.onDestroy()
        tvManager.disconnect()
    }
}
