package com.mycamera

import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class CalculatorActivity : AppCompatActivity() {

    private lateinit var tvDisplay: TextView
    private lateinit var tvFormula: TextView
    private lateinit var btnGoToObserver: Button
    private lateinit var btnHistory: android.widget.ImageButton
    private lateinit var historyContainer: android.view.View
    private lateinit var tvHistoryContent: TextView
    
    private var firstValue = 0.0
    private var operator = ""
    private var isNewOp = true
    private var currentRoomId: String? = null
    private val calculationHistory = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator)

        currentRoomId = intent.getStringExtra("ROOM_ID")

        tvDisplay = findViewById(R.id.tvDisplay)
        tvFormula = findViewById(R.id.tvFormula)
        btnGoToObserver = findViewById(R.id.btnGoToObserver)
        btnHistory = findViewById(R.id.btnHistory)
        historyContainer = findViewById(R.id.historyContainer)
        tvHistoryContent = findViewById(R.id.tvHistoryContent)
        
        setupButtons()

        btnHistory.setOnClickListener {
            historyContainer.visibility = if (historyContainer.visibility == android.view.View.VISIBLE) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }

        btnGoToObserver.setOnClickListener {
            val intent = android.content.Intent(this, ObserverActivity::class.java)
            if (currentRoomId != null) {
                intent.putExtra("AUTO_ROOM_ID", currentRoomId)
            }
            startActivity(intent)
        }
    }

    private fun setupButtons() {
        val gridLayout = findViewById<android.widget.GridLayout>(R.id.gridLayout)
        for (i in 0 until gridLayout.childCount) {
            val child = gridLayout.getChildAt(i)
            if (child is Button) {
                child.setOnClickListener { onButtonClick(child.text.toString()) }
            }
        }
    }

    private fun onButtonClick(text: String) {
        when (text) {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "." -> {
                if (isNewOp) {
                    tvDisplay.text = ""
                    isNewOp = false
                }
                
                if (text == "." && tvDisplay.text.contains(".")) return
                
                tvDisplay.append(text)
                
                // Secret Code Check
                if (tvDisplay.text.toString() == "932012") {
                    btnGoToObserver.visibility = android.view.View.VISIBLE
                }
            }
            "C" -> {
                tvDisplay.text = "0"
                tvFormula.text = ""
                firstValue = 0.0
                operator = ""
                isNewOp = true
            }
            "+/-" -> {
                val current = tvDisplay.text.toString().toDoubleOrNull() ?: 0.0
                if (current != 0.0) {
                    val flipped = current * -1
                    tvDisplay.text = formatResult(flipped)
                }
            }
            "%" -> {
                val current = tvDisplay.text.toString().toDoubleOrNull() ?: 0.0
                val percent = current / 100
                tvDisplay.text = formatResult(percent)
                isNewOp = true
            }
            "÷", "×", "-", "+" -> {
                firstValue = tvDisplay.text.toString().toDoubleOrNull() ?: 0.0
                operator = text
                tvFormula.text = "${formatResult(firstValue)} $operator"
                isNewOp = true
            }
            "=" -> {
                if (operator.isEmpty()) return
                
                val secondValue = tvDisplay.text.toString().toDoubleOrNull() ?: 0.0
                val result = when (operator) {
                    "÷" -> if (secondValue != 0.0) firstValue / secondValue else 0.0
                    "×" -> firstValue * secondValue
                    "-" -> firstValue - secondValue
                    "+" -> firstValue + secondValue
                    else -> secondValue
                }
                
                val formulaStr = "${formatResult(firstValue)} $operator ${formatResult(secondValue)}"
                val resultStr = formatResult(result)
                
                tvFormula.text = ""
                tvDisplay.text = resultStr
                isNewOp = true
                
                addToHistory("$formulaStr = $resultStr")
                operator = ""
            }
        }
    }

    private fun formatResult(d: Double): String {
        return if (d % 1 == 0.0) d.toLong().toString() else String.format(Locale.getDefault(), "%.2f", d)
    }

    private fun addToHistory(calculation: String) {
        calculationHistory.add(0, calculation)
        if (calculationHistory.size > 4) {
            calculationHistory.removeAt(4)
        }
        
        val historyText = calculationHistory.joinToString("\n")
        tvHistoryContent.text = historyText
    }
}
