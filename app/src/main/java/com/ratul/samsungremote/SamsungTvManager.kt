package com.ratul.samsungremote

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SamsungTvManager(private val context: Context) {

    private val tag = "SamsungTvManager"
    private val prefs = context.getSharedPreferences("samsung_remote_prefs", Context.MODE_PRIVATE)
    private var webSocket: WebSocket? = null
    private var isConnected = false

    var onStatusChanged: ((String) -> Unit)? = null

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun connect(tvIp: String) {
        prefs.edit().putString("tv_ip", tvIp).apply()
        val savedToken = prefs.getString("auth_token", "") ?: ""

        val appNameBase64 = Base64.encodeToString("Samsung Smart Remote".toByteArray(), Base64.NO_WRAP)
        val url = if (savedToken.isNotEmpty()) {
            "wss://$tvIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64&token=$savedToken"
        } else {
            "wss://$tvIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64"
        }

        onStatusChanged?.invoke("Connecting to $tvIp...")
        val request = Request.Builder().url(url).build()

        webSocket?.close(1000, "Reconnecting")
        webSocket = getUnsafeOkHttpClient().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onStatusChanged?.invoke("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "Message from TV: $text")
                try {
                    val json = JSONObject(text)
                    if (json.optString("event") == "ms.channel.connect") {
                        val data = json.optJSONObject("data")
                        val token = data?.optString("token", "") ?: ""
                        if (token.isNotEmpty()) {
                            prefs.edit().putString("auth_token", token).apply()
                            onStatusChanged?.invoke("Connected & Paired")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                onStatusChanged?.invoke("Disconnected: ${t.localizedMessage}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onStatusChanged?.invoke("Disconnected")
            }
        })
    }

    fun sendKey(key: String) {
        if (webSocket == null || !isConnected) {
            onStatusChanged?.invoke("Not connected to TV!")
            return
        }

        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", key)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        webSocket?.send(payload.toString())
    }

    // সরাসরি টিভির টেক্সট বক্সে স্ট্রিং পাঠানো
    fun sendText(text: String) {
        if (webSocket == null || !isConnected) return

        val encodedText = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", encodedText)
                put("DataOfCmd", "base64")
                put("TypeOfRemote", "SendInputString")
            })
        }
        webSocket?.send(payload.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "App closed")
        webSocket = null
        isConnected = false
    }

    fun getSavedIp(): String = prefs.getString("tv_ip", "") ?: ""
}
