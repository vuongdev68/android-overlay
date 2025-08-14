package com.vtech.interactivetools

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
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
    private lateinit var googleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 1001
    private  var TAG ="MainActivity"
    private lateinit var btnGoogleSignIn: SignInButton
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d("ProcessCheck", "Process: ${Application.getProcessName()}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_CODE_OVERLAY)
            } else {
               // startOverlayService()
            }
        } else {
           // startOverlayService()
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
            btnStopService.visibility =View.GONE
        }
        // Cấu hình Google Sign-In, request email user
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
            // Dừng service
            val intent = Intent(this, OverlayService::class.java)
            stopService(intent)

            // Cập nhật UI
            btnStopService.visibility = View.GONE
            btnStartService.visibility = View.VISIBLE
        }
        btnStartService.setOnClickListener {
            btnStopService.visibility = View.VISIBLE
            btnStartService.visibility = View.GONE
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

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
        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startOverlayService()
                } else {
                    // Người dùng từ chối quyền vẽ overlay
                }
            }
        }else if (requestCode == RC_SIGN_IN) {

            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
// Lấy token Google ID token
                val idToken = account.idToken

                // Đăng nhập Firebase với Google credential
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        if (authTask.isSuccessful) {
                            // Đăng nhập Firebase thành công

                            val firebaseUser = FirebaseAuth.getInstance().currentUser

                            val firebaseUid = firebaseUser?.uid              // Firebase UID (vd: K4CevPcN3nWnBYEkNDjNp6lSAmv1)
                            val email = firebaseUser?.email
                            val displayName = firebaseUser?.displayName
                            val photoURL = firebaseUser?.photoUrl?.toString()

                            // Gửi lên server backend
                            sendLoginToServer(firebaseUid!!, email!!, displayName!!, photoURL!!)
                        } else {
                            Log.e(TAG, "Firebase login failed: ${authTask.exception?.message}")
                        }
                    }

                // Gửi lên server
              

            } catch (e: ApiException) {
                Log.e(TAG,e.message.toString())
                Toast.makeText(this, "Login thất bại: ${e.statusCode}", Toast.LENGTH_SHORT).show()
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
            .url("https://vliveapp.com/api/login") // URL backend bạn
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

                            // Lưu dữ liệu dataJson vào SharedPreferences
                            saveUserDataToLocal(this@MainActivity, dataJson.toString())

                            runOnUiThread {
                                btnGoogleSignIn.visibility = View.GONE
                                btnStartService.visibility = View.VISIBLE
                                btnStopService.visibility =View.VISIBLE
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
    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
      //  finish() // Kết thúc MainActivity nếu muốn (tuỳ bạn)
    }
}

