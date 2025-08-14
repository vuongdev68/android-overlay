package com.vtech.interactivetools.utils

import android.util.Log
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

object TTSUtils {
    private const val TAG = "TTSUtils"
    private const val GOOGLE_TTS_URL = "https://www.google.com/speech-api/v2/synthesize"
    private const val API_KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw"

    private val client = OkHttpClient()

    /**
     * Gọi trực tiếp Google TTS V2, trả về mp3 bytes qua callback.
     *
     * @param text   Nội dung cần tổng hợp.
     * @param lang   Mã ngôn ngữ, ví dụ "en-US" hoặc "vi-VN".
     * @param gender "male" hoặc "female".
     * @param speed  Tốc độ, ví dụ 1.0
     * @param callback Trả về Result.success(ByteArray) hoặc Result.failure(Throwable)
     */
    fun synthesizeDirect(
        text: String,
        lang: String,
        gender: String,
        speed: Double,
        callback: (Result<ByteArray>) -> Unit
    ) {
        // 1) Build URL với các params
        val urlBuilder = GOOGLE_TTS_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("key", API_KEY)
            addQueryParameter("enc", "mpeg")
            addQueryParameter("text", text)
            addQueryParameter("lang", lang)
            addQueryParameter("speed", speed.toString())
            addQueryParameter("gender", gender)
            addQueryParameter("pitch", "0.5")
            addQueryParameter("rate", "48000")
        }
        val url = urlBuilder.build()

        // 2) Tạo GET request
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // 3) Enqueue bất đồng bộ
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Google TTS request failed", e)
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val ex = IOException("Unexpected code ${it.code}")
                        Log.e(TAG, "TTS error code: ${it.code}")
                        callback(Result.failure(ex))
                        return
                    }
                    val bytes = it.body?.bytes()
                    if (bytes != null) {
                        callback(Result.success(bytes))
                    } else {
                        val ex = IOException("Empty TTS response")
                        Log.e(TAG, "TTS response body null")
                        callback(Result.failure(ex))
                    }
                }
            }
        })
    }
}