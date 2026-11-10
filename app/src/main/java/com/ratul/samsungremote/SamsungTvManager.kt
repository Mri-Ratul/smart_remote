package com.ratul.samsungremote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class SamsungTvManager(private val context: Context, private val listener: TvEventListener) {

    interface TvEventListener {
        fun onTvConnected(name: String, ip: String, model: String)
        fun onTvDisconnected()
        fun onAppsLoaded(appsJson: String)
        fun onMediaStreamStarted(title: String, thumbUrl: String, mediaType: String)
        fun onVoiceRecognized(text: String)
    }

    private var tvIp: String? = null
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private var localMediaServer: LocalMediaServer? = null
    private var currentServingFile: File? = null
    private var currentServingMime: String = "image/jpeg"

    fun connectTv(ip: String) {
        this.tvIp = ip
        
        // ১. সরাসরি ও অবিলম্বে WebSocket ওপেন করা (কোনো ব্লকিং ছাড়া)
        scope.launch {
            connectWebSocket(ip)
        }

        // ২. সমান্তরালে ডিভাইস তথ্য ও অ্যাপ লিস্ট আনা
        scope.launch {
            fetchTvDeviceInfo(ip)
            fetchInstalledApps(ip)
        }
    }

    private suspend fun fetchTvDeviceInfo(ip: String) {
        val request = Request.Builder().url("http://$ip:8001/api/v2/").build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val device = json.optJSONObject("device")
                val name = device?.optString("name", "Samsung Smart TV") ?: "Samsung Smart TV"
                val model = device?.optString("modelName", "Tizen TV") ?: "Tizen TV"

                withContext(Dispatchers.Main) {
                    listener.onTvConnected(name, ip, model)
                }
            }
        } catch (e: Exception) {
            Log.e("SamsungTvManager", "Device Info error: ${e.message}")
        }
    }

    private suspend fun fetchInstalledApps(ip: String) {
        val request = Request.Builder().url("http://$ip:8001/api/v2/applications/").build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: "[]"
                val rawArray = JSONArray(bodyStr)
                val formattedArray = JSONArray()

                for (i in 0 until rawArray.length()) {
                    val app = rawArray.optJSONObject(i) ?: continue
                    val appObj = JSONObject().apply {
                        put("appId", app.optString("id"))
                        put("name", app.optString("name"))
                        put("iconUrl", app.optString("icon"))
                        put("iconText", app.optString("name").take(3).uppercase())
                    }
                    formattedArray.put(appObj)
                }

                withContext(Dispatchers.Main) {
                    listener.onAppsLoaded(formattedArray.toString())
                }
            }
        } catch (e: Exception) {
            Log.e("SamsungTvManager", "Apps fetch error: ${e.message}")
        }
    }

    private fun connectWebSocket(ip: String) {
        webSocket?.close(1000, "Reconnecting")
        val appNameBase64 = Base64.encodeToString("SamsungRemotePro".toByteArray(), Base64.NO_WRAP)
        val wsUrl = "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$appNameBase64"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("SamsungTvManager", "WebSocket Open Successfully on 8001")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SamsungTvManager", "WS Failure: ${t.message}")
                withContextUI { listener.onTvDisconnected() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                withContextUI { listener.onTvDisconnected() }
            }
        })
    }

    fun sendKey(key: String) {
        scope.launch {
            val resolvedKey = if (key == "KEY_PICTURE_MODE") "KEY_PMODE" else key
            val payload = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", "Click")
                    put("DataOfCmd", resolvedKey)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                })
            }
            webSocket?.send(payload.toString())
        }
    }

    fun sendText(text: String) {
        scope.launch {
            val textBase64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", textBase64)
                    put("DataOfCmd", "base64")
                    put("TypeOfRemote", "SendInputString")
                })
            }
            webSocket?.send(payload.toString())
        }
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        scope.launch {
            val payload = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", "Move")
                    put("Position", JSONObject().apply {
                        put("x", dx)
                        put("y", dy)
                    })
                    put("TypeOfRemote", "ProcessMouseDevice")
                })
            }
            webSocket?.send(payload.toString())
        }
    }

    fun sendMouseClick() {
        scope.launch {
            val payload = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", "LeftClick")
                    put("TypeOfRemote", "ProcessMouseDevice")
                })
            }
            webSocket?.send(payload.toString())
        }
    }

    fun launchApp(appId: String) {
        scope.launch {
            val ip = tvIp ?: return@launch
            val request = Request.Builder()
                .url("http://$ip:8001/api/v2/applications/$appId")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()
            try { httpClient.newCall(request).execute() } catch (e: Exception) {}
        }
    }

    fun triggerSoundShift(targetLevel: Int) {
        scope.launch {
            val isUp = targetLevel == 80
            val cmd = if (isUp) "KEY_VOLUP" else "KEY_VOLDOWN"
            for (i in 0 until 30) {
                sendKey(cmd)
                delay(35)
            }
        }
    }

    // নেটিভ DLNA প্রোটোকল দিয়ে কাস্ট (কোনো ব্রাউজার খুলবে না)
    fun castMediaViaDlna(file: File, mimeType: String) {
        currentServingFile = file
        currentServingMime = mimeType

        if (localMediaServer == null) {
            localMediaServer = LocalMediaServer(8899)
            localMediaServer?.start()
        }

        val localIp = getLocalIpAddress()
        val streamUrl = "http://$localIp:8899/media"

        scope.launch {
            val ip = tvIp ?: return@launch
            val isVideo = mimeType.contains("video")

            // DLNA AVTransport SOAP অ্যাকশন
            val soapBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <CurrentURI>$streamUrl</CurrentURI>
                            <CurrentURIMetaData></CurrentURIMetaData>
                        </u:SetAVTransportURI>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            val soapRequest = Request.Builder()
                .url("http://$ip:9197/upnp/control/AVTransport1")
                .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
                .post(RequestBody.create("text/xml; charset=utf-8".toMediaTypeOrNull(), soapBody))
                .build()

            try {
                httpClient.newCall(soapRequest).execute()
                // প্লে কমান্ড দেওয়া
                val playSoap = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                        <s:Body>
                            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                                <InstanceID>0</InstanceID>
                                <Speed>1</Speed>
                            </u:Play>
                        </s:Body>
                    </s:Envelope>
                """.trimIndent()

                val playRequest = Request.Builder()
                    .url("http://$ip:9197/upnp/control/AVTransport1")
                    .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
                    .post(RequestBody.create("text/xml; charset=utf-8".toMediaTypeOrNull(), playSoap))
                    .build()

                httpClient.newCall(playRequest).execute()

                withContext(Dispatchers.Main) {
                    listener.onMediaStreamStarted(file.name, streamUrl, if (isVideo) "Video" else "Image")
                }
            } catch (e: Exception) {
                Log.e("SamsungTvManager", "DLNA Cast Error: ${e.message}")
            }
        }
    }

    private inner class LocalMediaServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession?): Response {
            val file = currentServingFile ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            return try {
                val inputStream: InputStream = FileInputStream(file)
                newChunkedResponse(Response.Status.OK, currentServingMime, inputStream)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
            }
        }
    }

    private fun getLocalIpAddress(): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    private fun withContextUI(block: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { block() }
    }

    fun disconnect() {
        webSocket?.close(1000, "Closed")
        localMediaServer?.stop()
    }
}
