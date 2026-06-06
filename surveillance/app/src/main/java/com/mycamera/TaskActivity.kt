package com.mycamera

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class TaskActivity : AppCompatActivity() {

    private lateinit var etHabitInput: EditText
    private lateinit var btnAddHabit: Button
    private lateinit var rvHabits: RecyclerView
    private lateinit var dateLabelsContainer: LinearLayout
    private lateinit var progressGraph: ProgressGraphView
    private lateinit var headerDateScrollView: SyncHorizontalScrollView
    
    private var currentScrollX = 0
    private val activeScrollViews = mutableSetOf<SyncHorizontalScrollView>()

    private val habits = mutableListOf<String>()
    private lateinit var adapter: HabitAdapter
    private val prefs by lazy { getSharedPreferences("HabitPrefs", Context.MODE_PRIVATE) }
    
    // We show the whole current month
    private val dateFormats = mutableListOf<String>()
    private val dateKeys = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task)

        // Initialize dates for the current month
        val calendar = Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH)
        val year = calendar.get(Calendar.YEAR)
        
        for (i in 1..daysInMonth) {
            val cal = Calendar.getInstance()
            cal.set(year, month, i)
            dateFormats.add(SimpleDateFormat("dd/MM", Locale.getDefault()).format(cal.time))
            dateKeys.add(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time))
        }

        etHabitInput = findViewById(R.id.etHabitInput)
        btnAddHabit = findViewById(R.id.btnAddHabit)
        rvHabits = findViewById(R.id.rvHabits)
        dateLabelsContainer = findViewById(R.id.dateLabelsContainer)
        progressGraph = findViewById(R.id.progressGraph)
        headerDateScrollView = findViewById(R.id.headerDateScrollView)

        headerDateScrollView.setOnScrollChangeListenerCustom { x, _ ->
            syncScrolls(x, headerDateScrollView)
        }

        loadHabits()
        setupDateHeaders()
        updateGraph()

        adapter = HabitAdapter(habits)
        rvHabits.layoutManager = LinearLayoutManager(this)
        rvHabits.adapter = adapter

        // Scroll to today's date
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val itemWidth = 120 + 20 // 120px width + 20px margins
        val scrollX = (today - 1) * itemWidth
        
        headerDateScrollView.post {
            headerDateScrollView.scrollTo(scrollX, 0)
            syncScrolls(scrollX, headerDateScrollView)
        }

        btnAddHabit.setOnClickListener {
            val habit = etHabitInput.text.toString().trim()
            if (habit.isNotEmpty()) {
                if (!habits.contains(habit)) {
                    habits.add(habit)
                    saveHabits()
                    adapter.notifyItemInserted(habits.size - 1)
                    etHabitInput.text.clear()
                    updateGraph()
                } else {
                    Toast.makeText(this, "Habit already exists!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncScrolls(x: Int, source: SyncHorizontalScrollView) {
        currentScrollX = x
        activeScrollViews.forEach { sv ->
            if (sv != source) {
                sv.scrollTo(x, 0)
            }
        }
        if (headerDateScrollView != source) {
            headerDateScrollView.scrollTo(x, 0)
        }
    }

    private fun updateGraph() {
        if (habits.isEmpty()) {
            progressGraph.setData(List(dateKeys.size) { 0f }, dateFormats)
            return
        }

        val completionData = dateKeys.map { dateKey ->
            val completedCount = habits.count { habit ->
                prefs.getBoolean("${habit}_${dateKey}", false)
            }
            completedCount.toFloat() / habits.size
        }
        progressGraph.setData(completionData, dateFormats)
    }

    private fun setupDateHeaders() {
        dateLabelsContainer.removeAllViews()
        dateFormats.forEach { dateStr ->
            val tv = TextView(this).apply {
                val params = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(10, 0, 10, 0)
                layoutParams = params
                text = dateStr
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextColor(Color.parseColor("#AAFFFFFF"))
                textSize = 12f
            }
            dateLabelsContainer.addView(tv)
        }
    }

    private fun loadHabits() {
        val savedHabits = prefs.getStringSet("HABIT_LIST", emptySet()) ?: emptySet()
        habits.clear()
        habits.addAll(savedHabits.toList().sorted())
    }

    private fun saveHabits() {
        prefs.edit().putStringSet("HABIT_LIST", habits.toSet()).apply()
    }

    inner class HabitAdapter(private val list: MutableList<String>) : RecyclerView.Adapter<HabitVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HabitVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_habit_row, parent, false)
            return HabitVH(view)
        }

        override fun onBindViewHolder(holder: HabitVH, position: Int) {
            val habit = list[position]
            holder.tvName.text = habit
            
            val scrollView = holder.itemView.findViewById<SyncHorizontalScrollView>(R.id.checkboxScrollView)
            activeScrollViews.add(scrollView)
            
            // Apply current scroll position immediately to new items
            scrollView.post { scrollView.scrollTo(currentScrollX, 0) }

            scrollView.setOnScrollChangeListenerCustom { x, _ ->
                syncScrolls(x, scrollView)
            }

            holder.checkboxContainer.removeAllViews()
            dateKeys.forEach { dateKey ->
                val cb = CheckBox(this@TaskActivity).apply {
                    val params = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.setMargins(10, 0, 10, 0)
                    layoutParams = params
                    gravity = Gravity.CENTER
                    buttonTintList = ColorStateList.valueOf(Color.WHITE)
                    
                    val storageKey = "${habit}_${dateKey}"
                    setOnCheckedChangeListener(null)
                    isChecked = prefs.getBoolean(storageKey, false)
                    
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(storageKey, checked).apply()
                        updateGraph()
                    }
                }
                holder.checkboxContainer.addView(cb)
            }
            
            holder.itemView.setOnLongClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val habitToRemove = list[currentPos]
                    
                    androidx.appcompat.app.AlertDialog.Builder(this@TaskActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Delete Habit")
                        .setMessage("Are you sure you want to delete '$habitToRemove'?")
                        .setPositiveButton("Delete") { _, _ ->
                            list.removeAt(currentPos)
                            saveHabits()
                            notifyItemRemoved(currentPos)
                            updateGraph()
                            Toast.makeText(this@TaskActivity, "Deleted: $habitToRemove", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                true
            }
        }

        override fun onViewRecycled(holder: HabitVH) {
            super.onViewRecycled(holder)
            val scrollView = holder.itemView.findViewById<SyncHorizontalScrollView>(R.id.checkboxScrollView)
            activeScrollViews.remove(scrollView)
        }

        override fun getItemCount() = list.size
    }

    class HabitVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvHabitName)
        val checkboxContainer: LinearLayout = v.findViewById(R.id.checkboxContainer)
    }
}
