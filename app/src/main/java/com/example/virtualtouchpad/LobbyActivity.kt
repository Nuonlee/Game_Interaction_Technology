package com.example.virtualtouchpad

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.Toast
import android.Manifest
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class LobbyActivity : AppCompatActivity() {
    private var handService: HandInputService? = null
    private var servicesStarted = false

    // 서비스 바인딩 콜백
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HandInputService.LocalBinder
            handService = binder.getService()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            handService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_lobby)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            100
        )

        if (TouchAccessibilityService.instance == null) {
            openAccessibilitySettings(this)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val buttonStart = findViewById<Button>(R.id.button_start)
        val buttonSetting = findViewById<Button>(R.id.button_setting)
        buttonStart.setOnClickListener {
            val intent1 = Intent(this, SampleActivity::class.java)
            startActivity(intent1)
        }
        buttonSetting.setOnClickListener {
            // Move to setting
            val intent2 = Intent(this, SettingActivity::class.java)
            startActivity(intent2)
        }
    }
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}