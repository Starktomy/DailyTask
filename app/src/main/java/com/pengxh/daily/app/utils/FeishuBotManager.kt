package com.pengxh.daily.app.utils

import android.content.Context
import android.util.Log
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FeishuBotManager(private val context: Context) {
    private val kTag = "FeishuBot"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 需要无限超时
        .build()
    private val remoteCommandHandler by lazy { RemoteCommandHandler(context) }
    private var webSocket: WebSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        connect()
    }

    fun stop() {
        isRunning = false
        webSocket?.close(1000, "Stop by user")
        webSocket = null
    }

    private fun connect() {
        if (!isRunning) return

        scope.launch {
            try {
                val appId = SaveKeyValues.getValue(Constant.FEISHU_APP_ID_KEY, "") as String
                val appSecret = SaveKeyValues.getValue(Constant.FEISHU_APP_SECRET_KEY, "") as String
                
                if (appId.isBlank() || appSecret.isBlank()) {
                    Log.e(kTag, "AppID or Secret is empty")
                    return@launch
                }

                // 1. 获取 tenant_access_token
                val token = getTenantAccessToken(appId, appSecret) ?: return@launch
                
                // 2. 获取 WebSocket 终点 URL (飞书长连接 v1/v2 协议)
                // 注意：这里使用的是飞书“长连接”模式的官方端点获取方式
                val wsUrl = getWebSocketUrl(token) ?: return@launch

                // 3. 开启 WebSocket
                val request = Request.Builder().url(wsUrl).build()
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d(kTag, "WebSocket Connected")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleFeishuMessage(text)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(kTag, "WebSocket Closed: $reason")
                        reconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(kTag, "WebSocket Failure: ${t.message}")
                        reconnect()
                    }
                })
            } catch (e: Exception) {
                Log.e(kTag, "Connect failed: ${e.message}")
                reconnect()
            }
        }
    }

    private fun reconnect() {
        if (!isRunning) return
        scope.launch {
            delay(5000)
            Log.d(kTag, "Attempting to reconnect...")
            connect()
        }
    }

    private fun getTenantAccessToken(appId: String, appSecret: String): String? {
        val json = JSONObject().apply {
            put("app_id", appId)
            put("app_secret", appSecret)
        }
        val body = RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"),
            json.toString()
        )
        val request = Request.Builder()
            .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val resBody = response.body()?.string() ?: ""
            JSONObject(resBody).getString("tenant_access_token")
        } catch (e: Exception) {
            null
        }
    }

    private fun getWebSocketUrl(token: String): String? {
        // 飞书长连接获取 URL 的接口
        val request = Request.Builder()
            .url("https://open.feishu.cn/open-apis/event/v1/endpoint_url")
            .header("Authorization", "Bearer $token")
            .post(RequestBody.create(null, ""))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val resBody = response.body()?.string() ?: ""
            val obj = JSONObject(resBody)
            if (obj.getInt("code") == 0) {
                obj.getJSONObject("data").getString("url")
            } else {
                Log.e(kTag, "Get WS URL failed: $resBody")
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun handleFeishuMessage(text: String) {
        try {
            val json = JSONObject(text)
            // 飞书长连接推送的是事件包，需要根据 type 判断
            if (json.has("header")) {
                val header = json.getJSONObject("header")
                val eventType = header.optString("event_type")
                
                // 处理机器人收到的文本消息 (im.message.receive_v1)
                if (eventType == "im.message.receive_v1") {
                    val event = json.getJSONObject("event")
                    val message = event.getJSONObject("message")
                    val msgType = message.getString("message_type")
                    
                    if (msgType == "text") {
                        val contentJson = JSONObject(message.getString("content"))
                        val command = contentJson.getString("text").trim()
                        Log.d(kTag, "Received command: $command")
                        
                        // 交给通用的指令处理器执行逻辑
                        CoroutineScope(Dispatchers.Main).launch {
                            remoteCommandHandler.handleCommand(command)
                        }
                    }
                }
            } else if (json.optString("type") == "url_verification") {
                // 如果飞书发送的是挑战验证（长连接通常不需要，但兼容一下）
                // 长连接模式下通常会自动处理，或者在此处回复 ping/pong
            }
        } catch (e: Exception) {
            Log.e(kTag, "Parse message error: ${e.message}")
        }
    }
}
