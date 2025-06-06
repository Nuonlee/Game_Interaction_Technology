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

//        val serviceIntent = Intent(this, HandInputService::class.java)
//        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        val buttonStart = findViewById<Button>(R.id.button_start)
        val buttonSetting = findViewById<Button>(R.id.button_setting)
        buttonStart.setOnClickListener {
            // Move to execution of virtual touch
//            val intent1 = Intent(this, MainActivity::class.java)
//            startActivity(intent1)
            //Toast.makeText(this, "현재 비활성화된 기능", Toast.LENGTH_SHORT).show()
            if (HandInputService.instance != null) {
                Toast.makeText(this, "A", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "B", Toast.LENGTH_SHORT).show()
            }
        }
        buttonSetting.setOnClickListener {
            // Move to setting
            val intent2 = Intent(this, SettingActivity::class.java)
            startActivity(intent2)
        }
    }
}