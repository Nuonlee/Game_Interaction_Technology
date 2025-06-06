package com.example.virtualtouchpad

import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.virtualtouchpad.filters.Landmark
import com.example.virtualtouchpad.filters.LandmarkFilterManager
import com.example.virtualtouchpad.filters.OneEuroFilter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.File
import java.util.concurrent.Executors

class HandInputService : LifecycleService() {
    // 바인더 정의
    inner class LocalBinder : Binder() {
        fun getService(): HandInputService = this@HandInputService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    companion object {
        var instance: HandInputService? = null
    }

    private lateinit var handLandmarker: HandLandmarker
    private lateinit var pointerOverlay: PointerOverlay
    private lateinit var windowManager: WindowManager

    private var cameraRunning = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val landmarkFilterManager = LandmarkFilterManager(
        freq = 30.0,
        minCutoff = 1.0,
        beta = 5.0,
        dCutoff = 1.0
    )
    private val depthFilterManager = OneEuroFilter(
        freq = 30.0,
        minCutoff = 1.0,
        beta = 5.0,
        dCutoff = 1.0
    )

    private var rotationMatrix: FloatArray? = null
    private var cameraPos: FloatArray? = null
    private var isPoseValid: Boolean = false
    private var lastBitmap: Bitmap? = null
    var isCalibrating = false
    private var captureHand = false
    private var frameIdx = 0

    private var isTouching = false
    private var alreadyTriggered = false
    private var touchStartTime: Long? = null

    private var lastDragTime: Long = 0L

    private val zPressThreshold = 0.010f
    private val zReleaseThreshold = 0.015f
    private val longPressThreshold = 1500L

    private var lastTouchX: Float? = null
    private var lastTouchY: Float? = null
    private var isDragging = false

    private var captureCallback: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupOverlay()
        setupMediaPipe()

        // Calibration 캐시 초기화 (카메라)
        val cachePath = filesDir.absolutePath + "/calibration"
        val calibrationDir = File(cachePath)
        calibrationDir.mkdirs()
        calibrationDir.listFiles()?.forEach { it.delete() }
        NativeLib.initCalibrationCache(cachePath)

        // 접근성 서비스 권한 확인
        if (!isAccessibilityServiceEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // TouchService 실행
        val intent = Intent(this, TouchService::class.java)
        startService(intent)

        initHandCalibration()
    }

    override fun onDestroy() {
        if (::pointerOverlay.isInitialized) {
            windowManager.removeView(pointerOverlay)
        }
        stopCameraIfRunning()
        instance = null
        super.onDestroy()
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    fun receiveBitmap(bitmap: Bitmap) {
        // 비트맵 복사 보관
        lastBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        // ArUco 포즈 추정
        NativeLib.estimatePose(lastBitmap!!)?.takeIf { it.size == 15 }?.let { pose ->
            isPoseValid = true
            cameraPos = pose.sliceArray(0..2)
            rotationMatrix = pose.sliceArray(6..14)
            Log.d("ArucoPose", "CamPos=${cameraPos?.toList()}")
        } ?: run {
            isPoseValid = false
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        handLandmarker.detectAsync(mpImage, System.currentTimeMillis())
    }

    fun saveCurrentFrame(type: String, callback: (Boolean) -> Unit) {
        val bitmap = lastBitmap
        if (bitmap == null) {
            callback(false)
            return
        }
        when (type) {
            "camera" -> {
                val result = NativeLib.saveCalibrationImage(bitmap)
                callback(result)
            }
            "hand" -> {
                if (isCalibrating && isPoseValid) {
                    captureHand = true
                    captureCallback = callback
                } else {
                    callback(false)
                }
            }
            else -> {
                callback(false)
            }
        }
    }

    // 카메라 캘리브레이션 실행
    fun runCalibration(): Boolean {
        val success = NativeLib.calibrateFromSavedImages()
        if (success) {
            Log.i("Calib", "카메라 캘리브레이션 완료")
        } else {
            Log.e("Calib", "카메라 캘리브레이션 실패")
        }
        return success
    }

    // 손 캘리브레이션 준비
    fun initHandCalibration() {
        val path = filesDir.absolutePath + "/calibration/hand"
        val dir = File(path)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        frameIdx = 0
    }

    // 랜드마크 데이터를 파일로 저장
    private fun saveLandmarksToFile(landmarks: List<Landmark>, imageSize: Size): Boolean {
        if (cameraPos == null || rotationMatrix == null) {
            Log.e("Calib", "saveLandmarksToFile: cameraPos 혹은 rotationMatrix가 null")
            return false
        }

        val sessionFolder = File(filesDir.absolutePath + "/calibration/hand")
        if (!sessionFolder.exists()) {
            Log.e("Calib", "saveLandmarksToFile: 폴더가 존재하지 않음")
            return false
        }

        val filename = File(sessionFolder, "frame_%04d.txt".format(frameIdx++))
        return try {
            filename.bufferedWriter().use { out ->
                out.write("%.8f,%.8f,%.8f\n".format(
                    cameraPos!![0], cameraPos!![1], cameraPos!![2]
                ))
                val rot = rotationMatrix!!
                for (i in 0 until 3) {
                    val row = rot.slice(i * 3 until i * 3 + 3)
                    out.write(row.joinToString(",") { "%.8f".format(it) })
                    out.write("\n")
                }
                landmarks.forEach { lm ->
                    val px = (lm.v * imageSize.width).toFloat()
                    val py = (lm.u * imageSize.height).toFloat()
                    out.write("%.3f,%.3f\n".format(px, py))
                }
            }
            true
        } catch (e: Exception) {
            Log.e("Calib", "saveLandmarksToFile: 파일 쓰기 오류", e)
            if (filename.exists()) {
                filename.delete()
            }
            false
        }
    }

    // MediaPipe 설정 및 콜백
    private fun setupMediaPipe() {
        val mainHandler = Handler(mainLooper)
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
            )
            .setNumHands(1)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, input ->
                result.landmarks().firstOrNull()?.let { detected ->
                    val imageWidth = input.width.toFloat()
                    val imageHeight = input.height.toFloat()
                    val rawLandmarks = detected.map {
                        Landmark(
                            u = (1.0 - it.y()).toDouble(),
                            v = it.x().toDouble(),
                            z = it.z().toDouble()
                        )
                    }
                    val filtered = landmarkFilterManager.filter(rawLandmarks)

                    val landmarkArray = FloatArray(21 * 3)
                    filtered.forEachIndexed { idx, lm ->
                        val px = (lm.v * imageWidth).toFloat()
                        val py = (lm.u * imageHeight).toFloat()
                        landmarkArray[idx*3 + 0] = px
                        landmarkArray[idx*3 + 1] = py
                        landmarkArray[idx*3 + 2] = lm.z.toFloat()
                    }
                    NativeLib.updateLandmarks(landmarkArray)

                    if (captureHand && isCalibrating && lastBitmap != null) {
                        val result = saveLandmarksToFile(filtered, Size(input.width, input.height))
                        if (result) {
                            NativeLib.calibrateHandFromLandmarkFiles()
                        }
                        captureHand = false

                        captureCallback?.invoke(result)
                        captureCallback = null
                    }

                    // 랜드마크 계산
                    var xTip = 0.0f
                    var yTip = 0.0f
                    val viewWidth = pointerOverlay.width.toDouble()
                    val viewHeight = pointerOverlay.height.toDouble()
                    val scale = maxOf(viewWidth / imageWidth, viewHeight / imageHeight)
                    val dx = (viewWidth - imageWidth * scale) / 2f
                    val dy = (viewHeight - imageHeight * scale) / 2f

                    val overlayLandmarks = mutableListOf<Pair<Float, Float>>()
                    filtered.forEachIndexed { idx, lm ->
                        val screenX = (lm.u * imageWidth).toFloat() * scale + dx
                        val screenY = (lm.v * imageHeight).toFloat() * scale + dy
                        if (idx == 8) {
                            xTip = screenX.toFloat()
                            yTip = screenY.toFloat() + getStatusBarHeight()
                        }
                        overlayLandmarks.add(Pair(screenX.toFloat(), screenY.toFloat()))
                    }

                    // UI 업데이트
                    mainHandler.post {
                        pointerOverlay.landmarks = overlayLandmarks
                    }

                    // 깊이 추정 및 z 메시지
                    var zVal = Double.MAX_VALUE
                    var depthMsg = ""
                    if (isPoseValid && NativeLib.estimateDepth()) {
                        NativeLib.estimateIndexTip()
                        val tipCoord = NativeLib.getLandmarkWorld(8)
                        zVal = depthFilterManager.filter(tipCoord[2].toDouble())
                        val zInCm = zVal * 100
                        depthMsg = if (zInCm < 0)
                            "마커 뒤쪽 %.1f cm".format(-zInCm)
                        else
                            "마커 앞쪽 %.1f cm".format(zInCm)
                        Log.d("FingerTip", "Index tip = ($xTip, $yTip, $zVal)")
                    } else {
                        Log.w("Depth", "Depth 추정 불가 (poseValid=$isPoseValid)")
                        depthMsg = "깊이 추정 불가 (pose 없음)"
                    }

                    // UI 업데이트
                    mainHandler.post {
                        pointerOverlay.zMessage = depthMsg
                    }

                    // 터치 처리
                    handleDepthTouch(this, xTip, yTip, zVal.toFloat())

                } ?: run {
                    // 손이 보이지 않는 경우
                    mainHandler.post {
                        pointerOverlay.landmarks = emptyList()
                        pointerOverlay.zMessage = ""
                    }
                    isTouching = false
                    alreadyTriggered = false
                    touchStartTime = null
                }
            }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    // 시스템 오버레이로 PointerOverlay 추가
    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pointerOverlay = PointerOverlay(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(pointerOverlay, params)
        } catch (e: SecurityException) {
            Log.e("HandInputService", "Failed to add overlay view", e)
        }
    }

    private fun sendTouchIntent(
        context: Context,
        x1: Float, y1: Float,
        type: String,
        x2: Float? = null,
        y2: Float? = null
    ) {
        Intent("HAND_COORDINATES").also { intent ->
            intent.putExtra("x", x1)
            intent.putExtra("y", y1)
            intent.putExtra("type", type)
            if (type == "drag" && x2 != null && y2 != null) {
                intent.putExtra("x2", x2)
                intent.putExtra("y2", y2)
            }
            context.sendBroadcast(intent)
        }
    }

    // 손가락 깊이에 따른 터치
    private fun handleDepthTouch(context: Context, x: Float, y: Float, z: Float) {
        val now = System.currentTimeMillis()

        if (isCalibrating) {
            isTouching = false
            isDragging = false
            alreadyTriggered = false
            touchStartTime = null
            return
        }

        if (!isTouching && z < zPressThreshold) {
            touchStartTime = now
            isTouching = true
            isDragging = false
            alreadyTriggered = false

            lastTouchX = x
            lastTouchY = y

            lastDragTime = now
        }

        if (isTouching) {
            if (z < zReleaseThreshold) {
                val held = now - (touchStartTime ?: now)

                val prevX = lastTouchX ?: x
                val prevY = lastTouchY ?: y

                val dx = x - prevX
                val dy = y - prevY
                val distSq = dx * dx + dy * dy
                val dragThresholdSq = 100f

                // 드래그 시작
                if (distSq > dragThresholdSq && !isDragging) {
                    isDragging = true
                    lastDragTime = now
                }

                if (isDragging) {
                    val frameInterval = 33L
                    val elapsed = now - lastDragTime
                    // 최소 1단계 이상 보간하도록
                    val steps = maxOf(1, (elapsed / frameInterval).toInt())

                    var fromX = prevX
                    var fromY = prevY

                    for (i in 1..steps) {
                        val t = i.toFloat() / steps.toFloat()
                        val midX = prevX + (x - prevX) * t
                        val midY = prevY + (y - prevY) * t
                        sendTouchIntent(context, fromX, fromY, "drag", midX, midY)

                        fromX = midX
                        fromY = midY
                    }

                    lastDragTime = now
                }

                // 롱 프레스
                if (held >= longPressThreshold && !alreadyTriggered) {
                    alreadyTriggered = true
                    sendTouchIntent(context, x, y, "long_press")
                }

                // 터치 좌표 갱신
                lastTouchX = x
                lastTouchY = y

            } else {
                if (isDragging) {
                    val prevX = lastTouchX ?: x
                    val prevY = lastTouchY ?: y
                    sendTouchIntent(context, prevX, prevY, "drag", x, y)
                } else if (!alreadyTriggered) {
                    sendTouchIntent(context, x, y, "tap")
                }

                // 상태 초기화
                isTouching = false
                isDragging = false
                alreadyTriggered = false
                touchStartTime = null
                lastTouchX = null
                lastTouchY = null
            }
        }
    }

    // 카메라가 필요할 때 실행
    fun startCameraIfNeeded() {
        if (cameraRunning) {
            stopCameraIfRunning()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)
                receiveBitmap(bitmap)
                imageProxy.close()
            }

            imageAnalysis = analysis
            provider.unbindAll()
            provider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, analysis)

            cameraRunning = true
        }, ContextCompat.getMainExecutor(this))
    }

    // 카메라 실행 중지
    fun stopCameraIfRunning() {
        if (!cameraRunning) return
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraRunning = false
    }

    // ImageProxy → Bitmap 변환
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

    private fun sendTouchIntent(context: Context, x: Float, y: Float, type: String) {
        val intent = Intent("HAND_COORDINATES").apply {
            putExtra("x", x)
            putExtra("y", y)
            putExtra("type", type)
        }
        context.sendBroadcast(intent)
    }

    // 접근성 서비스 확인
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, TouchAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { comp ->
            ComponentName.unflattenFromString(comp) == expectedComponentName
        }
    }
}