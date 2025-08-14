package com.vtech.interactivetools.utils

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import java.io.File
import java.util.LinkedList
import java.util.Queue

class TTSManager(private val context: Context) {
    private val ttsQueue: Queue<String> = LinkedList()
    private var isReading = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var originalVolumes: Map<Int, Int> = emptyMap()

    fun enqueue(text: String) {
        ttsQueue.offer(text)
        if (!isReading) processQueue()
    }

    private fun processQueue() {
        val next = ttsQueue.poll() ?: run {
            isReading = false
            abandonAudioFocus()
            return
        }
        isReading = true

        // 1) Lưu lại volume gốc
        originalVolumes = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION
        ).associateWith { audioManager.getStreamVolume(it) }

        // 2) Yêu cầu audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val builder = AudioFocusRequest.Builder(
                // Hoặc GAIN_TRANSIENT_EXCLUSIVE nếu bạn muốn tắt hoàn toàn
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            // Duck xuống 0 (tắt hẳn)
                          //  audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Phục hồi volume gốc
                          //
                        }
                    }
                }
            focusRequest = builder.build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { /* no-op */ },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            // trên API <26, hệ thống vẫn duck tự, nhưng bạn có thể tự set xuống 0 nếu cần
        }

        // 3) Synthesize & play
        TTSUtils.synthesizeDirect(
            text   = next,
            lang   = "vi-VN",
            gender = "female",
            speed  = 0.5
        ) { result ->
            result.onSuccess { audioBytes ->
                // ghi file tạm
                val file = File(context.cacheDir, "tts.tmp.mp3")
                file.outputStream().use { it.write(audioBytes) }

                // phát MediaPlayer
                val mp = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setDataSource(file.absolutePath)
                    prepare()
                    setVolume(1f, 1f)
                    setOnCompletionListener {
                        it.release()
                        processQueue()  // đọc tiếp đoạn sau
                    }
                    start()
                }
            }.onFailure {
                processQueue()  // lỗi vẫn chuyển tiếp
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                focusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
