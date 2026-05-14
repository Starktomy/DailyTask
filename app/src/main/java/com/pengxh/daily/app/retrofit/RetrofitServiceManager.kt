package com.pengxh.daily.app.retrofit

import com.pengxh.daily.app.utils.Constant
import com.pengxh.kt.lite.utils.RetrofitFactory
import com.pengxh.kt.lite.utils.SaveKeyValues
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

object RetrofitServiceManager {
    private val api by lazy {
        RetrofitFactory.createRetrofit<RetrofitService>(Constant.WX_WEB_HOOK_URL)
    }

    suspend fun sendMessage(content: String): String {
        val channelType = SaveKeyValues.getValue(Constant.CHANNEL_TYPE_KEY, 0) as Int
        return if (channelType == 2) {
            // 飞书
            val webhookUrl = (SaveKeyValues.getValue(Constant.FS_WEB_HOOK_KEY, "") as String).trim()
            if (webhookUrl.isBlank()) {
                return "{\"code\":-1,\"msg\":\"飞书 Webhook URL 未配置\"}"
            }
            val jsonBody = JSONObject().apply {
                put("msg_type", "text")
                put("content", JSONObject().apply {
                    put("text", content)
                })
            }
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            api.sendFeishuMessage(webhookUrl, requestBody)
        } else {
            // 企业微信
            val jsonBody = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply {
                    put("content", content)
                })
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val keyMap = HashMap<String, String>()
            keyMap["key"] = (SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String).trim()
            api.sendMessage(requestBody, keyMap)
        }
    }

    suspend fun sendImageMessage(imagePath: String): String {
        val imageBytes = File(imagePath).readBytes()

        // 计算 Base64
        val base64 = Base64.getEncoder().encodeToString(imageBytes)

        // 计算文件的MD5
        val md5Hash = MessageDigest.getInstance("MD5").digest(imageBytes)
        val md5 = md5Hash.joinToString("") { "%02x".format(it) }

        val jsonBody = JSONObject().apply {
            put("msgtype", "image")
            put("image", JSONObject().apply {
                put("base64", base64)
                put("md5", md5)
            })
        }

        val requestBody = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val keyMap = HashMap<String, String>()
        keyMap["key"] = (SaveKeyValues.getValue(Constant.WX_WEB_HOOK_KEY, "") as String).trim()
        return api.sendMessage(requestBody, keyMap)
    }
}