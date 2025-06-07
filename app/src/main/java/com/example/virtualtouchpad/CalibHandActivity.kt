package com.example.virtualtouchpad

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ProgressBar
import android.view.View
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.ceil
import android.util.Log

class CalibHandActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var progressBarCalib: ProgressBar
    private lateinit var textCalibTimer: TextView
    private lateinit var buttonBackward : ImageButton
    private lateinit var buttonCapture : ImageButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var handService: HandInputService? = null
    private var servicesStarted = false

    private var remainingSeconds = 0.0
    private var currentAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var uiRunnable: Runnable? = null

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

        setContentView(R.layout.activity_calib_hand)

        val serviceIntent = Intent(this, HandInputService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        servicesStarted = true

        progressBarCalib = findViewById<ProgressBar>(R.id.progressBar_HandCalib)
        textCalibTimer = findViewById<TextView>(R.id.Text_HandCalib_Timer)

        // 버튼 기능 할당
        buttonBackward = findViewById<ImageButton>(R.id.button_back_calibHand)
        buttonCapture = findViewById<ImageButton>(R.id.Button_HandCalib_Capture)

        buttonBackward.setOnClickListener {
            finish()
        }
        buttonCapture.setOnClickListener {
            // 여기에 캘리브레이션 기능 호출
            if (handService?.isCalibrating == false)
                startCallbackRepeatingTask(5.0)
        }

        previewView = findViewById(R.id.previewView)
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

    override fun onStop() {
        super.onStop()
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        handService!!.startCameraIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (servicesStarted) {
            unbindService(connection)
            servicesStarted = false
        }
        currentAnimator?.cancel()
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

    private fun noticeActivityWillClose(){
        AlertDialog.Builder(this)
            .setMessage("캘리브레이션이 완료되었습니다. 이전 화면으로 돌아갑니다.")
            .setPositiveButton("확인") { _, _ ->
                finish()  // 현재 Activity 종료
            }
            .setCancelable(false)
            .show()
    }

    private fun startCallbackRepeatingTask(seconds: Double) {
        handService?.isCalibrating = true
        handService?.initHandCalibration()
        remainingSeconds = seconds
        setWidgetVisibility(true)

        runNextStep()
        startProgressTimerUI()
    }

    private fun runNextStep() {
        if (remainingSeconds <= 0.01) {
            finishProcess()
            return
        }

        handService?.saveCurrentFrame("hand") { success ->
            runOnUiThread {
                if (!success) {
                    abortProcess()
                    return@runOnUiThread
                }

                remainingSeconds -= 0.1
                runNextStep()
            }
        }
    }


    private fun startProgressTimerUI() {
        uiRunnable = object : Runnable {
            override fun run() {
                if (handService?.isCalibrating == false || remainingSeconds <= 0.01) return

                textCalibTimer.text = ceil(remainingSeconds).toInt().toString()
                animateProgressBarOnce()

                handler.postDelayed(this, 1000L)
            }
        }

        handler.post(uiRunnable!!)
    }

    private fun animateProgressBarOnce() {
        currentAnimator?.cancel()
        progressBarCalib.progress = 0
        currentAnimator = ObjectAnimator.ofInt(progressBarCalib, "progress", 0, 100).apply {
            duration = 1000L
            interpolator = LinearInterpolator()
            start()
        }
    }


    private fun finishProcess() {
        handService?.isCalibrating = false
        currentAnimator?.cancel()
        setWidgetVisibility(false)

        val len05 = NativeLib.getLandmarkLength("0-5")
        val len09 = NativeLib.getLandmarkLength("0-9")
        val len59 = NativeLib.getLandmarkLength("5-9")

        Log.d("Hand Calib", "Length 0-5 : $len05)")
        Log.d("Hand Calib", "Length 0-9 : $len09)")
        Log.d("Hand Calib", "Length 5-9 : $len59)")

        noticeActivityWillClose()
    }

    private fun abortProcess() {
        handService?.isCalibrating = false
        currentAnimator?.cancel()
        Toast.makeText(this, "핸드 캘리브레이션 실패", Toast.LENGTH_SHORT).show()
        setWidgetVisibility(false)
    }

    private fun setWidgetVisibility(isCalib: Boolean){
        buttonBackward.isEnabled = !isCalib
        buttonCapture.isEnabled = !isCalib
        if (isCalib){
            progressBarCalib.visibility = View.VISIBLE
            textCalibTimer.visibility = View.VISIBLE
        }
        else{
            progressBarCalib.visibility = View.INVISIBLE
            textCalibTimer.visibility = View.INVISIBLE
        }
    }
}