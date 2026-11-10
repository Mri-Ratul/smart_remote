package com.ratul.samsungremote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), SamsungTvManager.TvEventListener {

    private lateinit var webView: WebView
    private lateinit var tvManager: SamsungTvManager
    private var pendingMediaType: String = "image"

    // গ্যালারি থেকে ছবি ও ভিডিও সিলেক্ট করার রেজাল্ট হ্যান্ডলার
    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                handleSelectedMediaUri(uri)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        tvManager = SamsungTvManager(this, this)

        // ১. ল্যাগ ১০০% দূর করতে জিপিইউ হার্ডওয়্যার অ্যাকসিলারেশন এনাবল করা
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // ডিফল্ট আইপি থাকলে অটো কানেক্ট ট্রাই করবে
                tvManager.connectTv("192.168.0.101")
            }
        }

        // ২. জাভাস্ক্রিপ্ট ইন্টারফেস বাইন্ড করা
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        // HTML লোড
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun connectTv(ip: String) {
            tvManager.connectTv(ip)
        }

        @JavascriptInterface
        fun sendKey(key: String) {
            tvManager.sendKey(key)
        }

        @JavascriptInterface
        fun sendMouseMove(dx: Int, dy: Int) {
            tvManager.sendMouseMove(dx, dy)
        }

        @JavascriptInterface
        fun sendMouseClick() {
            tvManager.sendMouseClick()
        }

        @JavascriptInterface
        fun launchApp(appId: String) {
            tvManager.launchApp(appId)
        }

        @JavascriptInterface
        fun triggerSoundShift(targetLevel: Int) {
            tvManager.triggerSoundShift(targetLevel)
        }

        @JavascriptInterface
        fun castOnlineImage(url: String) {
            tvManager.castOnlineImage(url)
        }

        @JavascriptInterface
        fun startCast(type: String) {
            runOnUiThread {
                pendingMediaType = type.lowercase()
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                if (pendingMediaType.contains("video")) {
                    intent.type = "video/*"
                } else {
                    intent.type = "image/*"
                }
                mediaPickerLauncher.launch(Intent.createChooser(intent, "Select Media to Cast"))
            }
        }

        @JavascriptInterface
        fun disconnectTv() {
            tvManager.disconnect()
        }
    }

    private fun handleSelectedMediaUri(uri: Uri) {
        try {
            val contentResolver = applicationContext.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val inputStream = contentResolver.openInputStream(uri)

            val cacheFile = File(cacheDir, "stream_media_temp")
            val outputStream = FileOutputStream(cacheFile)
            inputStream?.copyTo(outputStream)

            inputStream?.close()
            outputStream.close()

            // লোকাল সার্ভারে পুশ করে টিভিতে কাস্ট পাঠানো
            tvManager.startLocalMediaStream(cacheFile, mimeType)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // SamsungTvManager এর ইভেন্ট লিসেনার (WebView-এ ডেটা পুশ)
    override fun onTvConnected(name: String, ip: String, model: String) {
        webView.post {
            webView.evaluateJavascript("window.onTvConnected('$name', '$ip', '$model')", null)
        }
    }

    override fun onTvDisconnected() {
        webView.post {
            webView.evaluateJavascript("window.onTvDisconnected()", null)
        }
    }

    override fun onAppsLoaded(appsJson: String) {
        webView.post {
            webView.evaluateJavascript("window.setTvApps($appsJson)", null)
        }
    }

    override fun onMediaStreamStarted(title: String, thumbUrl: String, mediaType: String) {
        webView.post {
            webView.evaluateJavascript("window.onMediaCastStarted('$title', '$thumbUrl', '$mediaType')", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tvManager.disconnect()
    }
}
