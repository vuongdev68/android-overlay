package com.vtech.interactivetools

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.overlayvideoapp.OverlayService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

class MainActivity : ComponentActivity() {

    private val REQUEST_CODE_OVERLAY = 1000
    private val RC_SIGN_IN = 1001
    private val REQUEST_CODE_SCREEN_CAPTURE = 1002

    private var TAG = "MainActivity"
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var btnGoogleSignIn: SignInButton
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d("ProcessCheck", "Process: ${Application.getProcessName()}")
        }

        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)

        val userJson = loadUserDataFromLocal(this)
        if (userJson != null) {
            btnGoogleSignIn.visibility = View.GONE
            if (isMyServiceRunning(this, OverlayService::class.java)) {
                btnStartService.visibility = View.GONE
                btnStopService.visibility = View.VISIBLE
            } else {
                btnStartService.visibility = View.VISIBLE
                btnStopService.visibility = View.GONE
            }
        } else {
            btnGoogleSignIn.visibility = View.VISIBLE
            btnStartService.visibility = View.GONE
            btnStopService.visibility = View.GONE
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        btnGoogleSignIn.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }

        btnStopService.setOnClickListener {
            val intent = Intent(this, OverlayService::class.java)
            stopService(intent)

            btnStopService.visibility = View.GONE
            btnStartService.visibility = View.VISIBLE
        }

        btnStartService.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            } else {
                startOverlayVideoService()
            }
        }
    }
    private fun startOverlayVideoService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra("videoUrl", "https://example.com/video.mp4")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnStartService.visibility = View.GONE
        btnStopService.visibility = View.VISIBLE
    }
    private fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    fun isMyServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (service.service.className == serviceClass.name) {
                return true
            }
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        requestScreenCapturePermission()
                    } else {
                        Toast.makeText(this, "Bạn cần cấp quyền overlay để tiếp tục", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, OverlayService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)

                    } else {
                        startService(intent)
                    }
                    // Cập nhật UI ngay sau khi khởi động service thành công
                    btnStartService.visibility = View.GONE
                    btnStopService.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Bạn cần cấp quyền ghi màn hình để tiếp tục", Toast.LENGTH_SHORT).show()
                    // Khôi phục lại trạng thái UI nếu quyền bị từ chối
                    btnStartService.visibility = View.VISIBLE
                    btnStopService.visibility = View.GONE
                }
            }
            RC_SIGN_IN -> {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account.idToken

                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnCompleteListener { authTask ->
                            if (authTask.isSuccessful) {
                                val firebaseUser = FirebaseAuth.getInstance().currentUser
                                val firebaseUid = firebaseUser?.uid
                                val email = firebaseUser?.email
                                val displayName = firebaseUser?.displayName
                                val photoURL = firebaseUser?.photoUrl?.toString()

                                sendLoginToServer(firebaseUid!!, email!!, displayName!!, photoURL!!)
                            } else {
                                Log.e(TAG, "Firebase login failed: ${authTask.exception?.message}")
                            }
                        }
                } catch (e: ApiException) {
                    Log.e(TAG, e.message.toString())
                    Toast.makeText(this, "Login thất bại: ${e.statusCode}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadUserDataFromLocal(context: Context): JSONObject? {
        val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val userJsonString = sharedPref.getString("user_data", null)
        return if (userJsonString != null) JSONObject(userJsonString) else null
    }

    fun saveUserDataToLocal(context: Context, userJsonString: String) {
        val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("user_data", userJsonString)
            apply()
        }
    }

    private fun sendLoginToServer(uid: String, email: String, displayName: String, photoURL: String) {
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return Dns.SYSTEM.lookup(hostname).filter { it is Inet4Address }
                }
            })
            .build()

        val jsonBody = JSONObject().apply {
            put("uid", uid)
            put("email", email)
            put("displayName", displayName)
            put("photoURL", photoURL)
        }

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody.toString())

        val request = Request.Builder()
            .url("https://vliveapp.com/api/login")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Login to server failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Lỗi kết nối server", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val respBody = response.body?.string()
                    Log.d(TAG, "Login to server success: $respBody")

                    try {
                        val jsonResponse = JSONObject(respBody ?: "")
                        val success = jsonResponse.optBoolean("success", false)
                        if (success) {
                            val dataJson = jsonResponse.getJSONObject("data")
                            saveUserDataToLocal(this@MainActivity, dataJson.toString())
                            runOnUiThread {
                                btnGoogleSignIn.visibility = View.GONE
                                btnStartService.visibility = View.VISIBLE
                                btnStopService.visibility = View.VISIBLE
                                Toast.makeText(this@MainActivity, "Đăng nhập server thành công", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Login thất bại, server trả lỗi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: JSONException) {
                        e.printStackTrace()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Lỗi xử lý dữ liệu server", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.e(TAG, "Login to server thất bại, code: ${response.code}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Đăng nhập server thất bại", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
}