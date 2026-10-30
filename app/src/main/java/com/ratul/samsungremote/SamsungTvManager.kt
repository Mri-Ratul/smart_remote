package com.ratul.samsungremote

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    var isConnected = false
        private set

    var onStatusChanged: ((String) -> Unit)? = null
    var onTvDiscovered: ((String) -> Unit)? = null

    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // লোকাল নেটওয়ার্কে স্যামসাং টিভি অটো-ডিটেকশন (SSDP Protocol)
    fun autoDiscoverTv() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatusChanged?.invoke("Scanning network for Samsung TV...")
                val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n"

                val socket = DatagramSocket()
                socket.soTimeout = 3000
                val sendData = ssdpMessage.toByteArray()
                val sendPacket = DatagramPacket(
                    sendData,
                    sendData.size,
                    InetAddress.getByName("239.255.255.250"),
                    1900
                )
                socket.send(sendPacket)

                val buffer = ByteArray(1024)
                val receivePacket = DatagramPacket(buffer, buffer.size)

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 3500) {
                    try {
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length)
                        if (response.contains("Samsung") || response.contains("SEC")) {
                            val tvIp = receivePacket.address.hostAddress ?: ""
                            if (tvIp.isNotEmpty()) {
                                socket.close()
                                withContext(Dispatchers.Main) {
                                    onTvDiscovered?.invoke(tvIp)
                                    connect(tvIp)
                                }
                                return@launch
                            }
                        }
                    } catch (_: Exception) { }
                }
                socket.close()
                withContext(Dispatchers.Main) {
                    val savedIp = getSavedIp()
                    if (savedIp.isNotEmpty()) {
                        connect(savedIp)
                    } else {
                        onStatusChanged?.invoke("TV not found. Enter IP manually.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val savedIp = getSavedIp()
                    if (savedIp.isNotEmpty()) connect(savedIp)
                    else onStatusChanged?.invoke("Scan failed: ${e.message}")
                }
            }
        }
    }

    fun connect(tvIp: String) {
        prefs.edit().putString("tv_ip", tvIp).apply()
        val savedToken = prefs.getString("auth_token", "") ?: ""
        val appNameBase64 = Base64.encodeToString("Samsung Remote Pro".toByteArray(), Base64.NO_WRAP)
        val url = if (savedToken.isNotEmpty()) {
            "wss://$tvIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64&token=$savedToken"
        } else {
            "wss://$tvIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64"
        }

        onStatusChanged?.invoke("Connecting to $tvIp...")
        val request = Request.Builder().url(url).build()

        webSocket?.close(1000, "Reconnecting")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onStatusChanged?.invoke("Connected to Samsung TV")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("event") == "ms.channel.connect") {
                        val token = json.optJSONObject("data")?.optString("token", "") ?: ""
                        if (token.isNotEmpty()) {
                            prefs.edit().putString("auth_token", token).apply()
                            onStatusChanged?.invoke("Connected & Paired")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Message parse error", e)
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
        if (!isConnected || webSocket == null) {
            val savedIp = getSavedIp()
            if (savedIp.isNotEmpty()) connect(savedIp)
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

    // সরাসরি ইউটিউব অ্যাপ লঞ্চ করা
    fun launchYouTube() {
        val tvIp = getSavedIp()
        if (tvIp.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Tizen REST API to open YouTube
                val request = Request.Builder()
                    .url("http://$tvIp:8001/api/v2/applications/111299001912")
                    .post(RequestBody.create(null, ByteArray(0)))
                    .build()
                client.newCall(request).execute()
            } catch (_: Exception) {
                // Fallback via remote key
                sendKey("KEY_YOUTUBE")
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Exit")
        webSocket = null
        isConnected = false
    }

    fun getSavedIp(): String = prefs.getString("tv_ip", "") ?: ""
}
