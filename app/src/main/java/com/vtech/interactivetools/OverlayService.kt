package com.example.overlayvideoapp
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.vtech.interactivetools.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.vtech.interactivetools.utils.TTSManager
import com.vtech.interactivetools.utils.TTSUtils
import java.io.File
import kotlin.concurrent.timer

data class MediaExcute(
    val type: String,
    val url: String,
    val duration: Long, // milliseconds
    val volume: Float = 1f,
    val provider: String? = null,
    val fromLib: Boolean = false,
    val text: String? = null
)
data class ActionMeta(
    val userId: String?,
    val uniqueId: String?,
    val nickname: String?,
    val avatar: String?,
    val giftName: String?,
    val giftPictureUrl: String?,
    val diamondCount: Int?,
    val message: String?,
    val excuteType: String?,
    val comment: String?
)
data class Action(
    val screen: String,
    val duration: Long,
    val volume: Float,
    val skipNextAction: Boolean = false,
    val excutes: List<MediaExcute>,
    val meta: ActionMeta? = null // ✅ Thêm dòng này
)

class OverlayService : Service() {
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var textureView: TextureView? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var imageView: ImageView? = null
    private var webSocket: WebSocket? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioPlayer: MediaPlayer? = null
    private var pendingVideo: MediaExcute? = null

    private val TAG = "OverlayService"

    private val messageQueue: Queue<Action> = LinkedList()

    private var isShowVideo = false
    private var isShowImage = false
    private var isPlayAudio = false

    // Biến quản lý trạng thái overlay và media đang phát

    private var isOverlayVisible = false
    private val fadeDuration = 500L
    private var videoTimer: Timer? = null
    private var imageTimer: Timer? = null
    private var audioTimer: Timer? = null

    private var currentAction: Action? = null
    private lateinit var tts: TTSManager
    private val baseUrl = "https://s3.vliveapp.com/"
        //https://vlive-actions.s3.ap-southeast-1.amazonaws.com/
    private fun abandonAudioFocus() {
        if (::audioManager.isInitialized) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } else {
                audioManager.abandonAudioFocus(null)
            }
        }
    }
    private fun requestAudioFocus(): Boolean {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // Mất focus hẳn → dừng media
                            mediaPlayer?.pause()
                            audioPlayer?.pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Mất focus tạm thời → dừng hoặc pause
                            mediaPlayer?.pause()
                            audioPlayer?.pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            // Duck: giảm âm lượng của app bạn (không phải game)
                            mediaPlayer?.setVolume(0.1f, 0.1f)
                            audioPlayer?.setVolume(0.1f, 0.1f)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Lấy lại focus, phục hồi âm lượng
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                            audioPlayer?.setVolume(1.0f, 1.0f)
                        }
                    }
                }
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            val result = audioManager.requestAudioFocus(
                { focusChange -> /* tương tự xử lý ở trên */ },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    @SuppressLint("InflateParams", "SourceLockedOrientationActivity")
    override fun onCreate() {
        super.onCreate()
        tts = TTSManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        textureView = overlayView?.findViewById(R.id.textureView)
        imageView = overlayView?.findViewById(R.id.imageView)
        imageView?.visibility = View.GONE
        textureView?.visibility = View.VISIBLE
        imageView?.visibility = View.GONE





        overlayView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        mediaPlayer = MediaPlayer()
        mediaPlayer?.setOnCompletionListener {
            Log.d(TAG, "Video completed")
            isShowVideo = false
            videoTimer?.cancel()
            videoTimer = null
            checkAndHideOverlayIfNoMedia()
            processQueue()
        }
        mediaPlayer?.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Video MediaPlayer error: what=$what extra=$extra")
            isShowVideo = false
            checkAndHideOverlayIfNoMedia()
            processQueue()
            true
        }

        audioPlayer = MediaPlayer()
        audioPlayer?.setOnCompletionListener {
            isPlayAudio = false
            audioTimer?.cancel()
            audioTimer = null
            checkAndHideOverlayIfNoMedia()
            processQueue()
        }
        audioPlayer?.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Audio MediaPlayer error: what=$what extra=$extra")
            isPlayAudio = false
            checkAndHideOverlayIfNoMedia()
            processQueue()
            true
        }

        createNotificationChannelAndStartForeground()

        val userJson = loadUserDataFromLocal(this)
        val uid = userJson?.optString("googleId", null)
        fetchWsUrl(uid = uid.toString()) { wsUrl ->
            if (wsUrl != null) {
                Log.d(TAG, wsUrl)
                runOnUiThread {
                    initWebSocket(wsUrl)
                }
            }
        }
        overlayView?.visibility = View.INVISIBLE // ← quan trọng
      //  overlayView?.alpha = 0f

        initView()
    }

    fun loadUserDataFromLocal(context: Context): JSONObject? {
        val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val userJsonString = sharedPref.getString("user_data", null)
        return if (userJsonString != null) JSONObject(userJsonString) else null
    }
    private fun initView() {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenSize = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenSize.x = bounds.width()
            screenSize.y = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(screenSize)
        }

        val params = WindowManager.LayoutParams(
            screenSize.x,
            screenSize.y,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            windowManager.addView(overlayView, params)
            isOverlayVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "initView addView error: ${e.message}")
        }
        textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "Texture available")
                val surface = Surface(surface)
                mediaPlayer?.setSurface(surface)

                if (pendingVideo != null) {
                    playVideo(pendingVideo!!.url, pendingVideo!!.duration, pendingVideo!!.volume, pendingVideo!!.provider)
                    pendingVideo = null
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }
    private fun isTextureViewReady(): Boolean {
        return textureView?.isAvailable == true
    }
    private fun fetchWsUrl(uid: String, type: String = "media", callback: (String?) -> Unit) {
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return Dns.SYSTEM.lookup(hostname).filter { it is Inet4Address }
                }
            })
            .build()
        val url = "https://vliveapp.com/api/fetch-media-ws"

        val jsonBody = JSONObject().apply {
            put("uid", uid)
            put("type", type)
        }

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        val start = System.currentTimeMillis()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val end = System.currentTimeMillis()
                Log.e(TAG, "❌ Request failed sau ${end - start}ms: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {


                if (!response.isSuccessful) {
                    Log.e(TAG, "Unsuccessful: ${response.code}")
                    callback(null)
                    return
                }

                val responseBody = response.body?.string()
                try {
                    val json = JSONObject(responseBody ?: "")
                    if (json.optBoolean("success")) {
                        val wsUrl = json.getJSONObject("data").optString("url")
                        callback(wsUrl)
                    } else {
                        callback(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse error: ${e.message}")
                    callback(null)
                }
            }
        })
    }
    private fun showCustomGiftNotification(
        userName: String,
        avatarBitmap: Bitmap,
        giftBitmap: Bitmap,
        message: String
    ) {
        val channelId = "custom_gift_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Custom Gift Notification",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val remoteView = RemoteViews(packageName, R.layout.custom_notification)
        remoteView.setTextViewText(R.id.username, userName)
        remoteView.setTextViewText(R.id.message, message)
        remoteView.setImageViewBitmap(R.id.avatar, avatarBitmap)
        remoteView.setImageViewBitmap(R.id.giftImage, giftBitmap)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteView)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, builder.build())
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun showNotification(title: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "message_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Message Notification",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.enableVibration(true)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
    }
    private fun createNotificationChannelAndStartForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("overlay_channel", "Overlay Service Channel", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)

            val notification = Notification.Builder(this, "overlay_channel")
                .setContentTitle("Overlay Video")
                .setContentText("Đang chờ phát video overlay")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()

            startForeground(1, notification)
        }
    }

    private fun initWebSocket(wsUrl: String) {
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return Dns.SYSTEM.lookup(hostname).filter { it is Inet4Address }
                }
            })
            .build()
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val byteArray = bytes.toByteArray()
                    val unpacker = MessagePack.newDefaultUnpacker(byteArray)
                    val msg = unpacker.unpackValue()


                    val jsonString = msg.toJson()
                    val json = JSONObject(jsonString)
                    val type = json.optString("type", "")

                    if (type == "like" || type == "view_count" || type == "coin_ranking_chat" || type == "coin_ranking" || type =="like_ranking" || type == "FIREWORKS" || type == "WS_START_TIMER" || type =="CONTROL_TIMER") {
                        // Bỏ qua message có type = "coin"
                       // Log.d(TAG, "Message type=coin, bỏ qua không xử lý")
                        return;
                    }
                    Log.e(TAG, "TYP: ${type}")
                    parseAndEnqueueAction(jsonString)
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi giải mã msgpack: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
            }
        })
    }

    private fun parseAndEnqueueAction(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            Log.d(TAG,jsonStr)
            val screen = json.optString("screen", "")
            val duration = json.optLong("duration", 5000) * 1000 // s → ms
            val volume = json.optDouble("volume", 100.0).toFloat() / 100f
            val skipNextAction = json.optBoolean("skipNe xtAction", false)

            val excutesJson = json.optJSONArray("excutes") ?: JSONArray()
            val excutes = mutableListOf<MediaExcute>()

            for (i in 0 until excutesJson.length()) {
                val ex = excutesJson.getJSONObject(i)

                val type = ex.optString("type")
                val url = ex.optString("url")
                val dur = ex.optLong("duration", duration / 1000) * 1000
                val vol = ex.optDouble("volume", (volume * 100).toDouble()).toFloat() / 100f
                val provider = ex.optString("provider", null)
                val fromLib = ex.optBoolean("fromLib", false)
                val text = ex.optString("text", null)

                excutes.add(MediaExcute(type, url, dur, vol, provider, fromLib, text))
            }
            val meta = ActionMeta(
                userId = json.optString("userId", null),
                uniqueId = json.optString("uniqueId", null),
                nickname = json.optString("nickname", null),
                avatar = json.optString("avatar", null),
                giftName = json.optString("giftName", null),
                giftPictureUrl = json.optString("giftPictureUrl", null),
                diamondCount = json.optInt("diamondCount", 0),
                message = json.optString("message", null),
                excuteType = json.optString("excuteType", null),
                comment = json.optString("comment", null),
            )

            val action = Action(screen, duration, volume, skipNextAction, excutes, meta)
            if(action.meta?.excuteType == "chat"){

            }

            action.meta?.let { meta ->

                if (meta.avatar != null && meta.giftPictureUrl != null && meta.nickname != null && meta.giftName != null && (meta.excuteType == "min_coin" || meta.excuteType == "gift")) {
                    Thread {
                        try {
                            //  val avatar = Glide.with(applicationContext).asBitmap().load(meta.avatar).submit().get()
                            val cornerRadius = resources.getDimensionPixelSize(R.dimen.avatar_corner_radius)

                            val avatarBitmap = Glide.with(applicationContext)
                                .asBitmap()
                                .load(meta.avatar)
                                .transform(RoundedCorners(cornerRadius))
                                .submit()
                                .get()
                            val gift = Glide.with(applicationContext).asBitmap().load(meta.giftPictureUrl).submit().get()
                            val message = "${meta.nickname} tặng ${meta.giftName} x${meta.diamondCount}"
                            showCustomGiftNotification(meta.nickname, avatarBitmap, gift, message)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Lỗi khi show notification: ${e.message}")
                        }
                    }.start()
                }else if(meta.excuteType =="chat" ){
                    tts.enqueue(meta.comment.toString())

                }
            }
            messageQueue.offer(action)

            Log.d(TAG, "Parsed action: $action")

            if (!isShowVideo && !isShowImage && !isPlayAudio) {
                processQueue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseAndEnqueueAction error: ${e.message}")
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlayLayout()
    }
    private fun updateOverlayLayout() {
        if (overlayView != null && isOverlayVisible) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val screenSize = Point()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                screenSize.x = bounds.width()
                screenSize.y = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(screenSize)
            }

            val newParams = WindowManager.LayoutParams(
                screenSize.x,
                screenSize.y,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            newParams.gravity = Gravity.TOP or Gravity.START

            try {
                wm.updateViewLayout(overlayView, newParams)
            } catch (e: Exception) {
                Log.e(TAG, "updateOverlayLayout error: ${e.message}")
            }
        }
    }
    private fun processQueue() {
        if (messageQueue.isEmpty()) return
        if (isShowVideo || isShowImage || isPlayAudio) return

        val action = messageQueue.poll() ?: return
        currentAction = action

        if (action.skipNextAction) {

        }

        executeAction(action)
    }

    private fun executeAction(action: Action) {

        if (action.excutes.isEmpty()) {
            processQueue()
            return
        }

        action.excutes.forEach { exc ->
            when (exc.type.lowercase()) {
                "video" -> playVideo(exc.url, exc.duration, exc.volume, exc.provider)
                "image" -> playImage(exc.url, exc.duration, exc.provider)
                "audio" -> playAudio(exc.url, exc.duration, exc.volume, exc.fromLib, exc.provider)
                "show_user_alert" -> {
                    // TODO: xử lý alert nếu cần
                }
                else -> Log.w(TAG, "Unknown media type: ${exc.type}")
            }
        }

    }

    private fun playVideo(url: String, duration: Long, volume: Float, provider: String?) {

        runOnUiThread {
            showOverlay(isVideo = true)
            if (!isTextureViewReady()) {
                Log.w(TAG, "Surface chưa sẵn sàng, lưu video chờ phát sau")
                pendingVideo = MediaExcute("video", url, duration, volume, provider)
                isShowVideo = true
                return@runOnUiThread
            }
            if (!requestAudioFocus()) {
                Log.w(TAG, "Không lấy được audio focus, bỏ qua video")
                return@runOnUiThread
            }
            isShowVideo = true
            isShowImage = false
            isPlayAudio = false


            try {
                mediaPlayer?.reset()
                val surface = Surface(textureView!!.surfaceTexture)
                mediaPlayer?.setSurface(surface)
                val fullUrl = (if (provider == "aws") baseUrl else "") + url
                Log.d(TAG,fullUrl)
                mediaPlayer?.setDataSource(fullUrl)
                mediaPlayer?.isLooping = false
                mediaPlayer?.setVolume(volume, volume)
                mediaPlayer?.prepareAsync()
                mediaPlayer?.setOnPreparedListener { mp ->
                    mp.seekTo(0)
                    mp.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "playVideo error: ${e.message}")
                abandonAudioFocus()
                isShowVideo = false
                checkAndHideOverlayIfNoMedia()
                processQueue()
            }
        }

        videoTimer?.cancel()
        videoTimer = timer(initialDelay = duration, period = duration) {
            this.cancel()
            isShowVideo = false
            runOnUiThread {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
                abandonAudioFocus()
                checkAndHideOverlayIfNoMedia()
                processQueue()
            }

        }
    }



    private fun playImage(url: String, duration: Long, provider: String?) {
        isShowVideo = false
        isShowImage = true
        isPlayAudio = false


        runOnUiThread {
            showOverlay(isImage = true)
            val fullUrl = (if (provider == "aws") baseUrl else "") + url
            Glide.with(this@OverlayService)
                .load(fullUrl)
                .into(imageView!!)
        }

        imageTimer?.cancel()
        imageTimer = timer(initialDelay = duration, period = duration) {
            this.cancel()
            isShowImage = false
            checkAndHideOverlayIfNoMedia()
            processQueue()
        }
    }

    private fun playAudio(url: String, duration: Long, volume: Float, fromLib: Boolean, provider: String?) {
        isShowVideo = false
        isShowImage = false
        isPlayAudio = true
        runOnUiThread {
            try {
                if (!requestAudioFocus()) {
                    Log.w(TAG, "Không lấy được audio focus, bỏ qua audio")
                    return@runOnUiThread
                }
                audioPlayer?.reset()
                val fullUrl = when {
                    fromLib -> url.replace("http://", "https://")
                    provider == "aws" -> baseUrl + url
                    else -> url
                }
                audioPlayer?.setAudioStreamType(AudioManager.STREAM_MUSIC)
                audioPlayer?.setDataSource(fullUrl)
                audioPlayer?.setOnPreparedListener {
                    it.setVolume(volume, volume)
                    it.start()
                }
                audioPlayer?.prepareAsync()

            } catch (e: Exception) {
                Log.e(TAG, "playAudio error: ${e.message}")
                isPlayAudio = false
                abandonAudioFocus()
                checkAndHideOverlayIfNoMedia()
                processQueue()
            }
        }

        audioTimer?.cancel()
        audioTimer = timer(initialDelay = duration, period = duration) {
            this.cancel()
            isPlayAudio = false
            runOnUiThread {
                audioPlayer?.stop()
            }
            abandonAudioFocus()
            checkAndHideOverlayIfNoMedia()
            processQueue()
        }
    }

    private fun showOverlay(isVideo: Boolean = false, isImage: Boolean = false) {
        runOnUiThread {
            if (overlayView == null) return@runOnUiThread
            imageView?.animate()?.cancel()
            textureView?.animate()?.cancel()
            Log.d(TAG, "🔔 showOverlay được gọi, isVideo=$isVideo, isImage=$isImage")

            val lp = overlayView?.layoutParams as? WindowManager.LayoutParams
            if (lp != null && lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
                try {
                    windowManager.updateViewLayout(overlayView, lp)
                    Log.d(TAG, "➡️ Đã gỡ FLAG_NOT_TOUCHABLE")
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "❌ View chưa được gắn vào WindowManager: ${e.message}")
                }
            }
            if (overlayView?.visibility != View.VISIBLE) {
                Log.d(TAG, "overlayView how")
                overlayView?.visibility = View.VISIBLE
            }

            when {
                isVideo -> {
                    imageView?.visibility = View.GONE
                    textureView?.visibility = View.VISIBLE
                    textureView?.alpha = 0f
                    textureView?.animate()?.alpha(1f)?.setDuration(fadeDuration)?.start()
                }
                isImage -> {
                    textureView?.visibility = View.GONE
                    Handler(Looper.getMainLooper()).postDelayed({
                        imageView?.visibility = View.VISIBLE
                        imageView?.alpha = 0f
                        imageView?.animate()?.alpha(1f)?.setDuration(fadeDuration)?.start()
                    }, 50)

                }
                else -> {
                    textureView?.visibility = View.GONE
                    imageView?.visibility = View.GONE
                }
            }

            isOverlayVisible = true
        }
    }


    private fun hideOverlay() {
        runOnUiThread {
            Log.d(TAG, "🔕 Thực hiện ẩn overlay (soft)")

            if (textureView?.visibility == View.VISIBLE) {
                textureView?.animate()
                    ?.alpha(0f)
                    ?.setDuration(fadeDuration)
                    ?.withEndAction {
                        Log.d(TAG,"runnn")
                        overlayView?.visibility = View.INVISIBLE
                        setOverlayNotTouchable()
                    }
                    ?.start()
            } else if (imageView?.visibility == View.VISIBLE) {
                imageView?.animate()
                    ?.alpha(0f)
                    ?.setDuration(fadeDuration)
                    ?.withEndAction {
                        overlayView?.visibility = View.INVISIBLE
                        setOverlayNotTouchable()
                    }
                    ?.start()
            } else {
                overlayView?.visibility = View.INVISIBLE
                setOverlayNotTouchable()
            }
        }
    }


    private fun setOverlayNotTouchable() {
        runOnUiThread {
            val lp = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@runOnUiThread

            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(overlayView, lp)
                Log.d(TAG, "🛡️ Đã set FLAG_NOT_TOUCHABLE cho overlay")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Lỗi khi set FLAG_NOT_TOUCHABLE: ${e.message}")
            }
        }
    }
    private fun checkAndHideOverlayIfNoMedia() {
        if (!isPlayAudio && !isShowImage && !isShowVideo ) {

            if(messageQueue.isEmpty()){
                Log.d(TAG,"messageQueue empty")
                hideOverlay()
               // removeOverlay()
            }else{
                Log.d(TAG,"messageQueue not empty")
            }

        }
    }

    private fun runOnUiThread(action: Runnable) {
        Handler(Looper.getMainLooper()).post(action)
    }

    private fun removeOverlayAndStop() {
        try {
            overlayView?.let {
                windowManager.removeView(it)
                isOverlayVisible = false
            }
        } catch (e: Exception) {}

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        audioPlayer?.stop()
        audioPlayer?.release()
        audioPlayer = null

        videoTimer?.cancel()
        imageTimer?.cancel()
        audioTimer?.cancel()

        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlayAndStop()
    }

    override fun onBind(intent: Intent?) = null
}
