package com.mycamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import com.google.firebase.firestore.FirebaseFirestore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.mycamera.R

class StudentActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var timerText: TextView
    private lateinit var roomIdText: TextView
    private var roomId: String = ""
    private var timer: CountDownTimer? = null
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student)

        previewView = findViewById(R.id.previewView)
        timerText = findViewById(R.id.timerText)
        roomIdText = findViewById(R.id.roomIdText)

        // Use ID and time passed from MainActivity
        roomId = intent.getStringExtra("ROOM_ID") ?: (100000..999999).random().toString()
        val timeInMillis = intent.getLongExtra("TIME_LEFT", 60 * 60 * 1000L)
        
        roomIdText.text = "Room ID: $roomId"
        
        checkCameraPermission()
        startStudyTimer(timeInMillis)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            }
        }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            // Only request if not already granted
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startStudyTimer(duration: Long) {
        timer?.cancel()
        timer = object : CountDownTimer(duration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                
                val time = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                
                timerText.text = time
                db.collection("rooms").document(roomId).update("timer", time)
            }

            override fun onFinish() {
                timerText.text = "00:00"
                db.collection("rooms").document(roomId).update("status", "finished")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        if (roomId.isNotEmpty()) {
            db.collection("rooms").document(roomId).update("status", "inactive")
        }
    }
}
