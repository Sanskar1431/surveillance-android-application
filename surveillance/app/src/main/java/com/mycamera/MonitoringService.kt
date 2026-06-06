package com.mycamera

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MonitoringService : LifecycleService() {

    companion object {
        const val ACTION_START_MONITORING = "com.mycamera.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.mycamera.action.STOP_MONITORING"
        const val ACTION_START_SCREENSHARE = "com.mycamera.action.START_SCREENSHARE"
        const val ACTION_STOP_SCREENSHARE = "com.mycamera.action.STOP_SCREENSHARE"
        
        var isRunning = false
        var hasMediaProjection = false
    }

    private val CHANNEL_ID = "MonitoringServiceChannel"
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private var roomId: String? = null
    private var serviceEndTime: Long = 0
    private var studyEndTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var imageCapture: ImageCapture? = null
    private var lastScreenshotTime: Long = 0
    private val SCREENSHOT_INTERVAL = 2 * 60 * 1000L // 2 minutes

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isScreenshareActive = false
    private val screenshareHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        
        // Load roomId from preferences in case of service restart
        val prefs = getSharedPreferences("MyCameraPrefs", Context.MODE_PRIVATE)
        roomId = prefs.getString("ROOM_ID", null)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val action = intent?.action
        
        val incomingRoomId = intent?.getStringExtra("ROOM_ID")
        if (incomingRoomId != null) roomId = incomingRoomId
        
        when (action) {
            ACTION_STOP_MONITORING -> {
                stopUpdateLoop()
                stopBackgroundCamera()
                stopScreenshare()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SCREENSHARE -> {
                stopScreenshare()
            }
        }

        val duration = intent?.getLongExtra("DURATION", -1L) ?: -1L
        if (duration > 0) {
            serviceEndTime = System.currentTimeMillis() + duration
        }

        val studyDuration = intent?.getLongExtra("STUDY_DURATION", -1L) ?: -1L
        if (studyDuration >= 0) {
            studyEndTime = System.currentTimeMillis() + studyDuration
        }

        val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("PROJECTION_INTENT", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("PROJECTION_INTENT")
        }

        val hasProjection = projectionIntent != null || mediaProjection != null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Mode Active")
            .setContentText("Monitoring session is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (hasProjection) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            if (type != 0) {
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }

        if (action != ACTION_STOP_SCREENSHARE) {
            startBackgroundCamera()
            startUpdateLoop()
        }

        if (projectionIntent != null && mediaProjection == null) {
            mainHandler.postDelayed({
                try {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, projectionIntent)
                    
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            isScreenshareActive = false
                            hasMediaProjection = false
                            virtualDisplay?.release()
                            virtualDisplay = null
                            mediaProjection = null
                        }
                    }, screenshareHandler)
                    
                    hasMediaProjection = true
                    startScreenshare()
                } catch (e: Exception) {
                    Log.e("MonitoringService", "Error starting MediaProjection", e)
                    hasMediaProjection = false
                }
            }, 500)
        } else if (action == ACTION_START_SCREENSHARE || (action == null && mediaProjection != null)) {
            startScreenshare()
        }

        return START_STICKY
    }

    private fun startScreenshare() {
        val mp = mediaProjection ?: return
        try {
            virtualDisplay?.release()
            imageReader?.close()

            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
            
            val width = if (metrics.widthPixels > 0) metrics.widthPixels / 2 else 720
            val height = if (metrics.heightPixels > 0) metrics.heightPixels / 2 else 1280
            val density = if (metrics.densityDpi > 0) metrics.densityDpi else 320

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "Screenshare",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            isScreenshareActive = true
            screenshareHandler.removeCallbacksAndMessages(null)
            captureAndUploadScreen()
        } catch (e: Exception) {
            Log.e("MonitoringService", "Failed to start screenshare", e)
            isScreenshareActive = false
        }
    }

    private fun captureAndUploadScreen() {
        if (!isScreenshareActive || roomId == null) return

        try {
            val reader = imageReader ?: return
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.e("MonitoringService", "Failed to acquire image", e)
                null
            }

            if (image != null) {
                try {
                    val width = image.width
                    val height = image.height
                    val planes = image.planes
                    
                    if (planes.isNotEmpty() && width > 0 && height > 0) {
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height, Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        uploadScreenshareFrame(croppedBitmap)
                        bitmap.recycle() // Clean up intermediate bitmap
                    }
                } finally {
                    try {
                        image.close()
                    } catch (e: Exception) {
                        // Image might already be closed
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MonitoringService", "Error during frame capture", e)
        }

        if (isScreenshareActive) {
            screenshareHandler.removeCallbacksAndMessages(null)
            screenshareHandler.postDelayed({ captureAndUploadScreen() }, 3000)
        }
    }

    private fun uploadScreenshareFrame(bitmap: Bitmap) {
        val roomId = this.roomId ?: return
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
        val data = baos.toByteArray()

        val ref = storage.reference.child("screenshare/$roomId/current.jpg")
        ref.putBytes(data).addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUri ->
                val screenshareData = hashMapOf(
                    "url" to downloadUri.toString(),
                    "timestamp" to System.currentTimeMillis()
                )
                db.collection("rooms").document(roomId)
                    .collection("live").document("screenshare")
                    .set(screenshareData)
            }
        }
    }

    private fun startUpdateLoop() {
        if (updateRunnable != null) return // Already running
        
        updateRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                
                // If service expired (24h), stop everything
                if (serviceEndTime != 0L && now > serviceEndTime) {
                    db.collection("rooms").document(roomId ?: "").update("status", "finished")
                    stopSelf()
                    return
                }

                if (roomId != null) {
                    val remainingStudy = Math.max(0L, studyEndTime - now)
                    val updates = hashMapOf<String, Any>(
                        "timer" to formatTime(remainingStudy),
                        "lastHeartbeat" to now,
                        "deviceName" to android.os.Build.MODEL
                    )
                    db.collection("rooms").document(roomId!!)
                        .set(updates, com.google.firebase.firestore.SetOptions.merge())

                    // Auto-screenshot every 2 minutes
                    if (now - lastScreenshotTime >= SCREENSHOT_INTERVAL) {
                        takeScreenshotAndUpload()
                        lastScreenshotTime = now
                    }
                }
                
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(updateRunnable!!)
    }

    private fun startBackgroundCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val preview = Preview.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeScreenshotAndUpload() {
        val capture = imageCapture ?: return
        val context = this
        val roomId = this.roomId ?: return

        val photoFile = File(externalCacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val uri = Uri.fromFile(photoFile)
                saveImageToGallery(photoFile)
                uploadImageToFirebase(uri, roomId)
            }

            override fun onError(exception: ImageCaptureException) {
                exception.printStackTrace()
            }
        })
    }

    private fun saveImageToGallery(file: File) {
        val filename = "MyCamera_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { targetUri: Uri ->
            try {
                contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(targetUri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, targetUri))
                }
                Log.d("MonitoringService", "Screenshot saved to gallery: $targetUri")
            } catch (e: Exception) {
                Log.e("MonitoringService", "Failed to save screenshot to gallery", e)
            }
        }
    }

    private fun uploadImageToFirebase(uri: Uri, roomId: String) {
        val timestamp = System.currentTimeMillis()
        val path = "screenshots/$roomId/$timestamp.jpg"
        val ref = storage.reference.child(path)
        
        ref.putFile(uri).addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUri ->
                val galleryData = hashMapOf(
                    "url" to downloadUri.toString(),
                    "path" to path,
                    "timestamp" to timestamp
                )
                db.collection("rooms").document(roomId)
                    .collection("gallery").add(galleryData)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Monitoring Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun stopScreenshare() {
        isScreenshareActive = false
        screenshareHandler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        virtualDisplay = null
        // We keep mediaProjection object if we want to reuse it, 
        // but often it's better to recreate if intent is available.
        // For now, let's just stop the display.
    }

    private fun stopBackgroundCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopUpdateLoop() {
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
        updateRunnable = null
    }

    override fun onDestroy() {
        isRunning = false
        hasMediaProjection = false
        stopUpdateLoop()
        screenshareHandler.removeCallbacksAndMessages(null)
        isScreenshareActive = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
