package com.example.virtualtouchpad

import android.content.*
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

// 앱 화면이 실행되면 작동
// 카메라 화면을 확인하기 위한 용도
class SampleActivity : ComponentActivity() {
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

    // 액티비티 생성 시: UI 초기화 및 서비스 바인딩
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample)

        bindAndStartServices()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)

        val serviceIntent = Intent(this, HandInputService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        val intent = Intent(this, TouchService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    // 액티비티 화면 진입 시: 서비스 측 카메라 중지 → 프리뷰 + 분석 시작
    override fun onStart() {
        super.onStart()

        previewView.postDelayed({
            startCameraWithAnalysis()
        }, 300)
    }

    override fun onResume() {
        super.onResume()
        if (!servicesStarted && Settings.canDrawOverlays(this)) {
            bindAndStartServices()
        }
    }


    // 액티비티 화면 빠져나갈 때: 프리뷰 종료, 서비스 카메라 시작
    override fun onStop() {
        super.onStop()
        stopCamera()
        handService!!.startCameraIfNeeded()
    }

    // 액티비티 종료 시: 서비스 언바인딩
    override fun onDestroy() {
        super.onDestroy()
        if (servicesStarted) {
            unbindService(connection)
            servicesStarted = false
        }
    }

    // 카메라 프리뷰 + 분석용 카메라 시작
    private fun startCameraWithAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // 프리뷰와 분석 해제
    private fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
    }

    private fun bindAndStartServices() {
        if (servicesStarted) return

        val serviceIntent = Intent(this, HandInputService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        val intent = Intent(this, TouchService::class.java)
        ContextCompat.startForegroundService(this, intent)

        servicesStarted = true
    }

    // ImageProxy → Bitmap 변환 (MediaPipe에 전달하기 위해)
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
