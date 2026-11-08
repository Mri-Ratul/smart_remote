package com.ratul.samsungremote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TvAppItem(val id: String, val name: String)

class SamsungTvManager(private val context: Context) {

    private val tag = "SamsungTvManager"
    private val prefs = context.getSharedPreferences("samsung_remote_prefs", Context.MODE_PRIVATE)
    private var webSocket: WebSocket? = null
    var isConnected = false
        private set

    var onStatusChanged: ((String) -> Unit)? = null
    var onTvDiscovered: ((String) -> Unit)? = null
    var onScanFailed: (() -> Unit)? = null

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
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun autoDiscoverTv() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) { onStatusChanged?.invoke("Scanning network...") }

            var foundIp: String? = null

            try {
                val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n"

                val socket = DatagramSocket()
                socket.soTimeout = 1500
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
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 1800) {
                    try {
                        socket.receive(receivePacket)
                        val resp = String(receivePacket.data, 0, receivePacket.length)
                        if (resp.contains("Samsung") || resp.contains("SEC")) {
                            foundIp = receivePacket.address.hostAddress
                            break
                        }
                    } catch (_: Exception) {}
                }
                socket.close()
            } catch (_: Exception) {}

            if (foundIp == null) {
                val baseSubnet = getLocalSubnetPrefix()
                if (baseSubnet.isNotEmpty()) {
                    val jobs = mutableListOf<Deferred<String?>>()
                    for (i in 2..254) {
                        val targetIp = "$baseSubnet.$i"
                        val job = async {
                            try {
                                val s = Socket()
                                s.connect(InetSocketAddress(targetIp, 8002), 250)
                                s.close()
                                targetIp
                            } catch (_: Exception) {
                                null
                            }
                        }
                        jobs.add(job)
                    }
                    val results = jobs.awaitAll()
                    foundIp = results.firstOrNull { it != null }
                }
            }

            withContext(Dispatchers.Main) {
                if (!foundIp.isNullOrEmpty()) {
                    onTvDiscovered?.invoke(foundIp)
                    connect(foundIp)
                } else {
                    val saved = getSavedIp()
                    if (saved.isNotEmpty()) {
                        onStatusChanged?.invoke("Trying saved IP: $saved")
                        connect(saved)
                    } else {
                        onStatusChanged?.invoke("TV Not Found. Tap '⋮' to set IP")
                        onScanFailed?.invoke()
                    }
                }
            }
        }
    }

    private fun getLocalSubnetPrefix(): String {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifi.connectionInfo.ipAddress
            if (ipInt == 0) return ""
            return "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}"
        } catch (_: Exception) {
            return ""
        }
    }

    fun connect(rawIp: String) {
        val cleanIp = rawIp.replace("http://", "")
            .replace("https://", "")
            .replace("wss://", "")
            .replace("ws://", "")
            .split(":")[0].trim()

        prefs.edit().putString("tv_ip", cleanIp).apply()
        val savedToken = prefs.getString("auth_token", "") ?: ""
        val appNameBase64 = Base64.encodeToString("Samsung Remote Pro".toByteArray(), Base64.NO_WRAP)
        val url = if (savedToken.isNotEmpty()) {
            "wss://$cleanIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64&token=$savedToken"
        } else {
            "wss://$cleanIp:8002/api/v2/channels/samsung.remote.control?name=$appNameBase64"
        }

        onStatusChanged?.invoke("Connecting to $cleanIp...")
        val request = Request.Builder().url(url).build()

        webSocket?.close(1000, "Reconnecting")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onStatusChanged?.invoke("Connected: $cleanIp")
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
                onStatusChanged?.invoke("Failed: Check TV/IP")
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

    fun cyclePictureMode() {
        sendKey("KEY_PICTURE_MODE")
        sendKey("KEY_PMODE")
    }

    fun togglePicSize() {
        sendKey("KEY_ASPECT")
        sendKey("KEY_ZOOM")
    }

    fun toggleSubtitle() {
        sendKey("KEY_SUB_TITLE")
        sendKey("KEY_CAPTION")
    }

    fun sendText(text: String) {
        if (!isConnected || webSocket == null) {
            val savedIp = getSavedIp()
            if (savedIp.isNotEmpty()) connect(savedIp)
            return
        }

        val textBase64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", textBase64)
                put("DataOfCmd", "base64")
                put("Option", "false")
                put("TypeOfRemote", "SendInputString")
            })
        }
        webSocket?.send(payload.toString())
    }

    // টিভির ইনস্টলড অ্যাপস ফেচ করা
    fun fetchInstalledApps(callback: (List<TvAppItem>) -> Unit) {
        val tvIp = getSavedIp()
        if (tvIp.isEmpty()) {
            callback(emptyList())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val appsList = mutableListOf<TvAppItem>()
            try {
                // স্যামসাং টাইজেন অ্যাপ্লিকেশন ইনফো এন্ডপয়েন্ট
                val request = Request.Builder()
                    .url("http://$tvIp:8001/api/v2/applications")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val dataArr = json.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.getJSONObject(i)
                        appsList.add(TvAppItem(item.optString("appId"), item.optString("name")))
                    }
                }
            } catch (_: Exception) {}

            // যদি এন্ডপয়েন্ট ব্লক থাকে তবে স্ট্যান্ডার্ড অ্যাপস ডিফল্ট যুক্ত হবে
            if (appsList.isEmpty()) {
                appsList.add(TvAppItem("111299001912", "YouTube"))
                appsList.add(TvAppItem("3201512006785", "Netflix"))
                appsList.add(TvAppItem("3201512006963", "Amazon Prime Video"))
                appsList.add(TvAppItem("org.tizen.browser", "Internet Web Browser"))
                appsList.add(TvAppItem("11101200001", "Facebook Watch"))
            }

            withContext(Dispatchers.Main) {
                callback(appsList)
            }
        }
    }

    // নির্দিষ্ট অ্যাপ টিভিতে ওপেন করা
    fun launchApp(appId: String) {
        val tvIp = getSavedIp()
        if (tvIp.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("http://$tvIp:8001/api/v2/applications/$appId")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                client.newCall(request).execute()
            } catch (_: Exception) {
                if (appId == "111299001912") sendKey("KEY_YOUTUBE")
            }
        }
    }

    fun launchYouTubeWithSearch(query: String) {
        val tvIp = getSavedIp()
        if (tvIp.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val postData = "v=&search_query=$encoded"
                val mediaType = "application/json".toMediaTypeOrNull()
                val body = postData.toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("http://$tvIp:8001/api/v2/applications/111299001912")
                    .post(body)
                    .build()
                client.newCall(request).execute()
            } catch (_: Exception) {
                launchApp("111299001912")
                delay(1200)
                sendText(query)
                sendKey("KEY_ENTER")
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
