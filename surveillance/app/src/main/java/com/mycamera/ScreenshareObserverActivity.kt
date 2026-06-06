package com.mycamera

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore

class ScreenshareObserverActivity : AppCompatActivity() {

    private lateinit var liveFrame: ImageView
    private lateinit var tvStatus: TextView
    private val db = FirebaseFirestore.getInstance()
    private var roomId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshare_observer)

        liveFrame = findViewById(R.id.liveFrame)
        tvStatus = findViewById(R.id.tvStatus)

        roomId = intent.getStringExtra("ROOM_ID")
        if (roomId == null) {
            Toast.makeText(this, "Room ID missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        listenForScreenshare()
    }

    private fun listenForScreenshare() {
        db.collection("rooms").document(roomId!!).collection("live")
            .document("screenshare")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    tvStatus.text = "ERROR: ${e.message}"
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val url = snapshot.getString("url")
                    val timestamp = snapshot.getLong("timestamp") ?: 0L
                    
                    // Check if the frame is recent (e.g., within the last 10 seconds)
                    if (System.currentTimeMillis() - timestamp < 10000) {
                        Glide.with(this)
                            .load(url)
                            .placeholder(liveFrame.drawable)
                            .into(liveFrame)
                        tvStatus.text = "LIVE SCREENSHARE"
                    } else {
                        tvStatus.text = "STREAM OFFLINE"
                    }
                } else {
                    tvStatus.text = "WAITING FOR STREAM..."
                }
            }
    }
}
