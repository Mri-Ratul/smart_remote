package com.ratul.samsungremote

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvManager: SamsungTvManager
    private lateinit var gestureDetector: GestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvManager = SamsungTvManager(this)

        val etTvIp = findViewById<EditText>(R.id.etTvIp)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val etSearchText = findViewById<EditText>(R.id.etSearchText)
        val btnSendText = findViewById<Button>(R.id.btnSendText)
        val touchTrackpad = findViewById<View>(R.id.touchTrackpad)

        val savedIp = tvManager.getSavedIp()
        if (savedIp.isNotEmpty()) {
            etTvIp.setText(savedIp as CharSequence)
        }

        tvManager.onStatusChanged = { status: String ->
            runOnUiThread { tvStatus.text = "Status: $status" }
        }

        btnConnect.setOnClickListener {
            val ip = etTvIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Please enter your TV's IP", Toast.LENGTH_SHORT).show()
            } else {
                tvManager.connect(ip)
            }
        }

        btnSendText.setOnClickListener {
            val text = etSearchText.text.toString().trim()
            if (text.isNotEmpty()) {
                tvManager.sendText(text)
                etSearchText.setText("")
            }
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 50
            private val swipeVelocityThreshold = 50

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                tvManager.sendKey("KEY_ENTER")
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x

                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold) {
                        if (diffX > 0) {
                            tvManager.sendKey("KEY_RIGHT")
                        } else {
                            tvManager.sendKey("KEY_LEFT")
                        }
                        return true
                    }
                } else {
                    if (abs(diffY) > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                        if (diffY > 0) {
                            tvManager.sendKey("KEY_DOWN")
                        } else {
                            tvManager.sendKey("KEY_UP")
                        }
                        return true
                    }
                }
                return false
            }
        })

        touchTrackpad.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        findViewById<Button>(R.id.btnPower).setOnClickListener { tvManager.sendKey("KEY_POWER") }
        findViewById<Button>(R.id.btnSource).setOnClickListener { tvManager.sendKey("KEY_SOURCE") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { tvManager.sendKey("KEY_MUTE") }

        findViewById<Button>(R.id.btnBack).setOnClickListener { tvManager.sendKey("KEY_RETURN") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { tvManager.sendKey("KEY_HOME") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { tvManager.sendKey("KEY_EXIT") }

        findViewById<Button>(R.id.btnVolUp).setOnClickListener { tvManager.sendKey("KEY_VOLUP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { tvManager.sendKey("KEY_VOLDOWN") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { tvManager.sendKey("KEY_CHUP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { tvManager.sendKey("KEY_CHDOWN") }
    }

    override fun onDestroy() {
        super.onDestroy()
        tvManager.disconnect()
    }
}
