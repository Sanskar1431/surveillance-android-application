package com.mycamera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var timerText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tapToStartText: TextView
    
    private val db = FirebaseFirestore.getInstance()
    private var timer: CountDownTimer? = null
    private var roomId: String = ""
    private var isTimerRunning = false
    private var timeLeftInMillis: Long = 0
    private var totalTimeSet: Long = 0

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startMonitoringService(timeLeftInMillis, result.data)
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            } else true
            
            if (cameraGranted && notifyGranted) {
                // Permissions granted
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timerText = findViewById(R.id.mainTimerText)
        progressBar = findViewById(R.id.timerProgressBar)
        tapToStartText = findViewById(R.id.tapToStartText)

        val prefs = getSharedPreferences("MyCameraPrefs", MODE_PRIVATE)
        roomId = prefs.getString("ROOM_ID", "") ?: ""

        // Reset timer to 0 on home page startup (User decides)
        timeLeftInMillis = 0 
        totalTimeSet = 0
        updateCountDownText()

        // Automatically generate Room ID if not exists
        if (roomId.isEmpty()) {
            roomId = (100000..999999).random().toString()
            prefs.edit { putString("ROOM_ID", roomId) }
            
            val roomData = hashMapOf(
                "status" to "active",
                "timer" to "00:00",
                "lastHeartbeat" to System.currentTimeMillis(),
                "deviceName" to android.os.Build.MODEL
            )
            
            db.collection("rooms").document(roomId)
                .set(roomData)
        }

        // Always start/ensure service is running for heartbeat/camera
        // But only if it's not already running
        if (!MonitoringService.isRunning) {
            startMonitoringService()
        }

        findViewById<View>(R.id.clockContainer).setOnClickListener {
            val scaleDown = android.view.animation.ScaleAnimation(1f, 0.9f, 1f, 0.9f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply { duration = 100 }
            it.startAnimation(scaleDown)
            if (isTimerRunning) {
                stopStudySession()
            } else {
                startStudySession()
            }
        }

        findViewById<Button>(R.id.btnAdd30).setOnClickListener { changeTime(30) }
        findViewById<Button>(R.id.btnAdd60).setOnClickListener { changeTime(60) }
        findViewById<Button>(R.id.btnAdd120).setOnClickListener { changeTime(120) }
        findViewById<Button>(R.id.btnSub30).setOnClickListener { changeTime(-30) }
        findViewById<Button>(R.id.btnSub60).setOnClickListener { changeTime(-60) }

        findViewById<Button>(R.id.btnGoToCalculator).setOnClickListener {
            val intent = Intent(this, CalculatorActivity::class.java)
            if (roomId.isNotEmpty()) {
                intent.putExtra("ROOM_ID", roomId)
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnGoToTask).setOnClickListener {
            startActivity(Intent(this, TaskActivity::class.java))
        }

        startGlowAnimation()
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun changeTime(minutes: Int) {
        val timeToAdd = minutes * 60 * 1000L
        val newTime = timeLeftInMillis + timeToAdd
        
        if (newTime >= 0) {
            timeLeftInMillis = newTime
            
            if (isTimerRunning) {
                timer?.cancel()
                if (timeToAdd > 0) totalTimeSet += timeToAdd
                startStudySession()
            } else {
                totalTimeSet = timeLeftInMillis
                updateCountDownText()
                tapToStartText.text = "TAP TO START"
                progressBar.progress = 100
            }
        }
    }

    private fun startStudySession() {
        isTimerRunning = true
        tapToStartText.text = "FOCUSING..."
        
        db.collection("rooms").document(roomId).update("status", "active")

        if (MonitoringService.isRunning && MonitoringService.hasMediaProjection) {
            // Already have projection, just tell service to start capture
            val serviceIntent = Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_SCREENSHARE
                putExtra("ROOM_ID", roomId)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            // Need to request projection permission
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        timer = object : CountDownTimer(timeLeftInMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeftInMillis = millisUntilFinished
                updateCountDownText()
                if (totalTimeSet > 0) {
                    val progress = (millisUntilFinished.toFloat() / totalTimeSet.toFloat() * 100).toInt()
                    progressBar.progress = progress
                }
                db.collection("rooms").document(roomId).update("timer", formatTime(timeLeftInMillis))
            }

            override fun onFinish() {
                isTimerRunning = false
                timeLeftInMillis = 0
                progressBar.progress = 0
                updateCountDownText()
                tapToStartText.text = "FINISHED"
                db.collection("rooms").document(roomId).update("status", "finished")
            }
        }.start()
    }

    private fun startMonitoringService(studyDurationMillis: Long = 0L, projectionIntent: Intent? = null) {
        val serviceIntent = Intent(this, MonitoringService::class.java).apply {
            putExtra("ROOM_ID", roomId)
            putExtra("DURATION", 24 * 60 * 60 * 1000L) // 24 hours
            if (studyDurationMillis > 0) {
                putExtra("STUDY_DURATION", studyDurationMillis)
            }
            if (projectionIntent != null) {
                putExtra("PROJECTION_INTENT", projectionIntent)
            }
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopStudySession() {
        timer?.cancel()
        isTimerRunning = false
        tapToStartText.text = "PAUSED"
        db.collection("rooms").document(roomId).update("status", "paused")
        
        // Stop only screenshare, keep service running for camera/heartbeat
        val serviceIntent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP_SCREENSHARE
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun updateCountDownText() {
        timerText.text = formatTime(timeLeftInMillis)
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

    private fun startGlowAnimation() {
        val anim = AlphaAnimation(0.4f, 0.8f)
        anim.duration = 1500
        anim.repeatMode = Animation.REVERSE
        anim.repeatCount = Animation.INFINITE
        findViewById<View>(R.id.backgroundGlow).startAnimation(anim)
        
        // Add a subtle scale animation to the timer text
        val scaleAnim = android.view.animation.ScaleAnimation(
            1f, 1.05f, 1f, 1.05f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 3000
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        timerText.startAnimation(scaleAnim)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
