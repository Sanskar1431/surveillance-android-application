package com.mycamera

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity

import com.google.firebase.firestore.FirebaseFirestore
import androidx.core.graphics.toColorInt

import android.widget.LinearLayout
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.Query
import androidx.appcompat.app.AlertDialog
import com.google.firebase.storage.FirebaseStorage
import java.util.Locale

class ObserverActivity : AppCompatActivity() {

    data class GalleryItem(val id: String, val url: String, val path: String? = null)

    private lateinit var roomIdInput: EditText
    private lateinit var btnJoin: Button
    private lateinit var btnScreenshare: Button
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var activeDevicesList: LinearLayout
    private lateinit var activeDevicesHeader: TextView
    private lateinit var galleryRecyclerView: RecyclerView
    private lateinit var galleryAdapter: GalleryAdapter
    private val galleryList = mutableListOf<GalleryItem>()
    private val db = FirebaseFirestore.getInstance()
    private var isJoined = false
    private var currentRoomId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_observer)

        roomIdInput = findViewById(R.id.roomIdInput)
        btnJoin = findViewById(R.id.btnJoin)
        btnScreenshare = findViewById(R.id.btnScreenshare)
        statusText = findViewById(R.id.observerStatusText)
        timerText = findViewById(R.id.observerTimerText)
        activeDevicesList = findViewById(R.id.activeDevicesList)
        activeDevicesHeader = findViewById(R.id.tvActiveDevicesHeader)
        
        galleryRecyclerView = findViewById(R.id.galleryRecyclerView)
        galleryRecyclerView.layoutManager = GridLayoutManager(this, 3)
        galleryAdapter = GalleryAdapter(galleryList) { item ->
            showDeleteConfirmation(item)
        }
        galleryRecyclerView.adapter = galleryAdapter

        setupActiveDevicesListener()
        
        val autoId = intent.getStringExtra("AUTO_ROOM_ID")
        if (!autoId.isNullOrEmpty()) {
            roomIdInput.setText(autoId)
            joinRoom(autoId)
        }

        btnJoin.setOnClickListener {
            val roomId = roomIdInput.text.toString().trim()
            if (roomId.length == 6) {
                isJoined = false
                joinRoom(roomId)
            } else {
                Toast.makeText(this, "Please enter a 6-digit ID", Toast.LENGTH_SHORT).show()
            }
        }

        btnScreenshare.setOnClickListener {
            val roomId = currentRoomId ?: roomIdInput.text.toString().trim()
            if (roomId.length == 6) {
                val intent = Intent(this, ScreenshareObserverActivity::class.java)
                intent.putExtra("ROOM_ID", roomId)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please join a room first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupActiveDevicesListener() {
        db.collection("rooms")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("Observer", "Active devices listener error", e)
                    return@addSnapshotListener
                }
                activeDevicesList.removeAllViews()
                if (snapshots != null && !snapshots.isEmpty) {
                    val now = System.currentTimeMillis()
                    val activeDocs = snapshots.documents.filter { doc ->
                        val lastHeartbeat = doc.getLong("lastHeartbeat") ?: 0L
                        val status = doc.getString("status") ?: ""
                        // Show if heartbeat is recent (within 2 mins) AND it's not "finished" or "inactive"
                        // Or if it's explicitly "active" or "paused"
                        (now - lastHeartbeat) < 120000 && status != "finished"
                    }
                    if (activeDocs.isNotEmpty()) {
                        activeDevicesHeader.visibility = View.VISIBLE
                        for (doc in activeDocs) {
                            addDeviceToView(doc.id, doc.getString("deviceName") ?: "Unknown Device")
                        }
                    } else {
                        activeDevicesHeader.visibility = View.GONE
                    }
                } else {
                    activeDevicesHeader.visibility = View.GONE
                }
            }
    }

    private fun addDeviceToView(id: String, deviceName: String) {
        val deviceView = TextView(this).apply {
            text = getString(R.string.device_info_format, deviceName, id)
            setTextColor("#A855F7".toColorInt())
            textSize = 16f
            setPadding(10, 20, 10, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                roomIdInput.setText(id)
                isJoined = false
                joinRoom(id)
            }
        }
        activeDevicesList.addView(deviceView)
    }

    private fun joinRoom(roomId: String) {
        currentRoomId = roomId
        statusText.text = getString(R.string.status_connecting)
        
        // Remove previous listeners if any (though Firestore handles multiple listeners, 
        // it's cleaner to reset for a new Room ID)
        
        db.collection("rooms").document(roomId).collection("gallery")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("Observer", "Gallery error", e)
                    return@addSnapshotListener
                }
                galleryList.clear()
                snapshots?.forEach { doc ->
                    val url = doc.getString("url")
                    val path = doc.getString("path")
                    if (url != null) {
                        galleryList.add(GalleryItem(doc.id, url, path))
                    }
                }
                galleryAdapter.notifyItemRangeChanged(0, galleryList.size)
            }
        
        db.collection("rooms").document(roomId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    statusText.text = getString(R.string.status_connection_error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    isJoined = true
                    timerText.text = snapshot.getString("timer") ?: "00:00:00"
                    val status = snapshot.getString("status") ?: "waiting"
                    statusText.text = getString(R.string.status_format, status.uppercase(Locale.getDefault()))
                } else {
                    statusText.text = getString(R.string.status_room_not_found)
                }
            }
    }

    private fun showDeleteConfirmation(item: GalleryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Screenshot")
            .setMessage("Are you sure you want to delete this screenshot?")
            .setPositiveButton("Delete") { _, _ ->
                deleteScreenshot(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteScreenshot(item: GalleryItem) {
        val roomId = currentRoomId ?: return
        
        // Delete from Storage if path is available
        item.path?.let { path ->
            FirebaseStorage.getInstance().reference.child(path).delete()
                .addOnFailureListener { e ->
                    Log.e("Observer", "Failed to delete from storage: ${e.message}")
                }
        }

        // Delete from Firestore
        db.collection("rooms").document(roomId)
            .collection("gallery").document(item.id)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Screenshot deleted", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to delete record", Toast.LENGTH_SHORT).show()
            }
    }

    class GalleryAdapter(
        private val items: List<GalleryItem>,
        private val onDeleteClick: (GalleryItem) -> Unit
    ) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(android.R.id.icon)
            val deleteBtn: TextView = view.findViewById(android.R.id.button1)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val frame = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 350)
                setPadding(4, 4, 4, 4)
            }
            
            val iv = ImageView(parent.context).apply {
                id = android.R.id.icon
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            
            val btn = TextView(parent.context).apply {
                id = android.R.id.button1
                text = "✕"
                setTextColor(Color.WHITE)
                setBackgroundColor("#80000000".toColorInt())
                gravity = android.view.Gravity.CENTER
                setPadding(10, 5, 10, 5)
                val params = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    setMargins(5, 5, 5, 5)
                }
                layoutParams = params
            }
            
            frame.addView(iv)
            frame.addView(btn)
            
            return ViewHolder(frame)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            Glide.with(holder.imageView.context).load(item.url).into(holder.imageView)
            holder.deleteBtn.setOnClickListener {
                onDeleteClick(item)
            }
        }
        override fun getItemCount() = items.size
    }
}
