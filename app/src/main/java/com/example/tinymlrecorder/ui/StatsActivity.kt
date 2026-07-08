package com.example.tinymlrecorder

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class StatsActivity : AppCompatActivity() {

    private lateinit var totalsText: TextView
    private lateinit var historyText: TextView
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        totalsText  = findViewById(R.id.statsTotalsText)
        historyText = findViewById(R.id.statsHistoryText)
        backButton  = findViewById(R.id.statsBackButton)

        backButton.setOnClickListener { finish() }

        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        // Per exercise totals
        val exerciseTotals   = MonitorActivity.exerciseTotals
        val exerciseSetCount = MonitorActivity.exerciseSetCount
        val setHistory       = MonitorActivity.setHistory

        if (exerciseTotals.isEmpty()) {
            totalsText.text = "No data yet."
        } else {
            val sb = StringBuilder()
            exerciseTotals.forEach { (name, count) ->
                val label = if (name == "walking") "steps" else "reps"
                val sets  = exerciseSetCount[name] ?: 0
                sb.appendLine("${name.replace("_", " ").uppercase()}")
                sb.appendLine("$sets sets  —  $count $label total")
                sb.appendLine()
            }
            totalsText.text = sb.toString().trimEnd()
        }

        // Set history
        if (setHistory.isEmpty()) {
            historyText.text = "No sets recorded yet."
        } else {
            val sb = StringBuilder()
            setHistory.forEach { (name, setNum, count) ->
                val label = if (name == "walking") "steps" else "reps"
                sb.appendLine("${name.replace("_", " ")}  set $setNum  →  $count $label")
            }
            historyText.text = sb.toString().trimEnd()
        }
    }
}