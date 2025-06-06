package com.example.virtualtouchpad

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import android.content.*
import android.graphics.Bitmap
import android.util.Size
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.*
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors


class CalibCamActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

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

        setContentView(R.layout.activity_calib_cam)

        val serviceIntent = Intent(this, HandInputService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        servicesStarted = true

        // 버튼 기능 할당
        val buttonBackward = findViewById<ImageButton>(R.id.button_back_calibCam)
        val buttonCapture = findViewById<ImageButton>(R.id.Button_CamCalib_Capture)

        buttonBackward.setOnClickListener {
            finish()
        }
        buttonCapture.setOnClickListener {
            val success = handService?.runCalibration() ?: false
            if (success) {
                Toast.makeText(this, "카메라 캘리브레이션 완료", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "카메라 캘리브레이션 실패", Toast.LENGTH_SHORT).show()
            }
        }

        previewView = findViewById(R.id.previewView)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)
                handService?.receiveBitmap(bitmap)
                imageProxy.close()
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))

    }

    override fun onStop() {
        super.onStop()
        cameraProvider?.unbindAll()
        handService!!.startCameraIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (servicesStarted) {
            unbindService(connection)
            servicesStarted = false
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
    }
}