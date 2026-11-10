package com.ratul.samsungremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvManager: SamsungTvManager
    private var speechRecognizer: SpeechRecognizer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvManager = SamsungTvManager(this)
        webView = findViewById(R.id.webViewRemote)

        // ওয়েবভিউ সেটিংস কনফিগারেশন
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // এইচটিএমএল-এর সাথে কোটলিনের ব্রিজ কানেকশন
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.webViewClient = WebViewClient()

        // assets ফোল্ডারের index.html লোড করা
        webView.loadUrl("file:///android_asset/index.html")

        checkVoicePermission()

        // টিভি ডিসকভারি লিসেনার
        tvManager.onTvDiscovered = { ip ->
            runOnUiThread {
                webView.evaluateJavascript("if(window.onTvConnected) window.onTvConnected('$ip');", null)
            }
        }

        // অটো কানেক্ট আগের আইপি থাকলে
        val savedIp = tvManager.getSavedIp()
        if (savedIp.isNotEmpty()) {
            tvManager.connect(savedIp)
        } else {
            tvManager.autoDiscoverTv()
        }
    }

    // JavaScript Interface: HTML থেকে যেসব ফাংশন কল হবে
    inner class WebAppInterface {

        @JavascriptInterface
        fun sendKey(key: String) {
            tvManager.sendKey(key)
        }

        @JavascriptInterface
        fun launchApp(appId: String) {
            tvManager.launchApp(appId)
        }

        @JavascriptInterface
        fun triggerSoundShift() {
            // মুভির অ্যাকশন সিনে ভলিউম ৮০ থেকে ৫০-এ নামানো
            CoroutineScope(Dispatchers.IO).launch {
                repeat(30) {
                    tvManager.sendKey("KEY_VOLDOWN")
                    delay(35)
                }
            }
        }

        @JavascriptInterface
        fun sendMouseMove(dx: Int, dy: Int) {
            tvManager.sendWifiMouseMove(dx, dy)
        }

        @JavascriptInterface
        fun sendMouseClick() {
            tvManager.sendWifiMouseClick()
        }

        @JavascriptInterface
        fun connectTv(ip: String) {
            tvManager.connect(ip)
        }

        @JavascriptInterface
        fun disconnectTv() {
            tvManager.disconnect()
        }

        @JavascriptInterface
        fun autoDiscoverTv() {
            tvManager.autoDiscoverTv()
        }

        @JavascriptInterface
        fun sendText(text: String) {
            tvManager.sendText(text)
        }

        @JavascriptInterface
        fun startVoice(lang: String) {
            runOnUiThread {
                startSpeechListening(lang)
            }
        }

        @JavascriptInterface
        fun startCast(type: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Starting $type via Wi-Fi...", Toast.LENGTH_SHORT).show()
                // ওয়াই-ফাই স্ক্রিন কাস্টিং ও স্ট্রিমিং হ্যান্ডলার
            }
        }
    }

    // ভয়েস ইনপুট প্রসেসিং
    private fun startSpeechListening(lang: String) {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val transcribedText = matches[0]
                    // সরাসরি HTML-এর ইনপুট বক্সে ভয়েস টেক্সট পাঠিয়ে দেওয়া
                    webView.evaluateJavascript("document.getElementById('voiceField').value = '$transcribedText';", null)
                }
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
