package com.example.tinymlrecorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tinymlrecorder.R

class RepComparisonActivity : AppCompatActivity() {

    companion object {
        var manualReps = mutableListOf<DebugLogActivity.ManualRep>()
    }

    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var backButton: Button
    private lateinit var copyButton: Button
    private lateinit var summaryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rep_comparison)

        scrollView  = findViewById(R.id.comparisonScrollView)
        container   = findViewById(R.id.comparisonContainer)
        backButton  = findViewById(R.id.comparisonBackButton)
        copyButton  = findViewById(R.id.comparisonCopyButton)
        summaryText = findViewById(R.id.comparisonSummaryText)

        backButton.setOnClickListener { finish() }
        copyButton.setOnClickListener { copyAllToClipboard() }

        buildComparison()
    }

    /* ── only "REP #X counted" lines are real watch counts ── */
    private fun isWatchCount(line: String): Boolean =
        line.contains("REP #") && line.contains("counted")

    private fun buildComparison() {
        container.removeAllViews()

        var watchMatched = 0
        var watchMissed  = 0
        var watchExtra   = 0
        val fullReport   = StringBuilder()

        fullReport.appendLine("=== REP COMPARISON REPORT ===\n")
        fullReport.appendLine("Manual reps: ${manualReps.size}\n")

        manualReps.forEach { rep ->
            val duration = rep.endTime - rep.startTime

            /* count ONLY "REP #X counted" lines — not the debug log lines */
            val watchCountsInWindow = rep.logsDuring.count { isWatchCount(it) }
            val repCounted          = watchCountsInWindow >= 1
            val doubleCount         = (watchCountsInWindow - 1).coerceAtLeast(0)

            val rejections = rep.logsDuring.filter {
                it.startsWith("GYRO TOO LOW")   ||
                        it.startsWith("ACCEL TOO LOW")  ||
                        it.startsWith("GYRO FLOOR")     ||
                        it.startsWith("ACCEL FLOOR")    ||
                        it.startsWith("ADAPTIVE GYRO")  ||
                        it.startsWith("ADAPTIVE ACCEL") ||
                        it.startsWith("TOO FAST")       ||
                        it.startsWith("CALIB")
            }

            val accelLines = rep.logsDuring.filter {
                it.trimStart().startsWith("ax=") ||
                        it.trimStart().startsWith("  ax=")
            }
            val gyroLines = rep.logsDuring.filter {
                it.trimStart().startsWith("gx=") ||
                        it.trimStart().startsWith("  gx=")
            }

            val accMag  = extractMag(accelLines, "|acc=")
            val gyroMag = extractMag(gyroLines,  "|gyro=")

            if (repCounted) watchMatched++ else watchMissed++
            watchExtra += doubleCount

            val cardText = buildString {
                appendLine("── MANUAL REP #${rep.repNumber} ──────────────────────")
                appendLine("Duration      : ${duration}ms")
                appendLine("Watch counts  : $watchCountsInWindow")
                appendLine("Result        : ${if (repCounted) "✅ COUNTED" else "❌ MISSED"}")
                if (doubleCount > 0)
                    appendLine("⚠️  DOUBLE COUNT: watch fired ${watchCountsInWindow}x inside this rep!")
                appendLine()

                if (accelLines.isNotEmpty()) {
                    appendLine("📐 Accel during rep:")
                    accelLines.forEach { appendLine("   $it") }
                    if (accMag != null)
                        appendLine("   → acc magnitude = ${"%.1f".format(accMag)}")
                } else {
                    appendLine("📐 Accel : no data in this window")
                }
                appendLine()

                if (gyroLines.isNotEmpty()) {
                    appendLine("🔄 Gyro during rep:")
                    gyroLines.forEach { appendLine("   $it") }
                    if (gyroMag != null)
                        appendLine("   → gyro magnitude = ${"%.1f".format(gyroMag)}")
                } else {
                    appendLine("🔄 Gyro  : no data in this window")
                }
                appendLine()

                if (!repCounted) {
                    appendLine("⚠️  WHY MISSED:")
                    if (rejections.isEmpty()) {
                        appendLine("   → Classifier never fired above 0.85 confidence")
                        appendLine("     during this window.")
                    } else {
                        rejections.forEach { appendLine("   → $it") }
                    }
                    appendLine()
                }

                appendLine("📋 All logs during rep:")
                if (rep.logsDuring.isEmpty()) {
                    appendLine("   (no BLE messages received during this window)")
                } else {
                    rep.logsDuring.forEach { appendLine("   $it") }
                }
                appendLine()
            }

            fullReport.append(cardText).appendLine()

            val badgeText = buildString {
                append(if (repCounted) "✅" else "❌")
                append("  Rep #${rep.repNumber} — ${if (repCounted) "COUNTED" else "MISSED"}")
                if (doubleCount > 0) append("  ⚠️ +$doubleCount EXTRA")
            }

            addCard(
                badgeText, cardText,
                when {
                    doubleCount > 0 -> Color.parseColor("#2B1A00")
                    repCounted      -> Color.parseColor("#0A1F10")
                    else            -> Color.parseColor("#1F0A0A")
                },
                when {
                    doubleCount > 0 -> Color.parseColor("#7A3D00")
                    repCounted      -> Color.parseColor("#1A4D2E")
                    else            -> Color.parseColor("#4D1A1A")
                }
            )
        }

        /* ── ghost reps — watch counted outside any manual window ── */
        val highestRepNum = manualReps.flatMap { it.logsDuring }
            .filter { isWatchCount(it) }
            .mapNotNull { line ->
                Regex("REP #(\\d+)").find(line)
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 0

        val totalWatchInWindows = manualReps.sumOf { rep ->
            rep.logsDuring.count { isWatchCount(it) }
        }

        val ghostCount = (highestRepNum - totalWatchInWindows).coerceAtLeast(0)
        watchExtra += ghostCount

        if (ghostCount > 0) {
            val ghostText = buildString {
                appendLine("── GHOST REPS ────────────────────────────────")
                appendLine("Watch fired $ghostCount rep(s) OUTSIDE your hold windows.")
                appendLine()
                appendLine("Possible causes:")
                appendLine("  • Classifier triggered during rest between reps")
                appendLine("  • Triggered while lowering the weight down")
                appendLine("  • Incidental movement between reps")
                appendLine()
                appendLine("Highest watch rep seen : $highestRepNum")
                appendLine("Watch counts in windows: $totalWatchInWindows")
                appendLine("Ghost counts outside   : $ghostCount")
            }

            fullReport.appendLine(ghostText)
            addCard(
                "👻  $ghostCount GHOST REP(S) — fired outside your windows",
                ghostText,
                Color.parseColor("#1A0A2B"),
                Color.parseColor("#3D1A6B")
            )
        }

        /* ── summary ── */
        val total = manualReps.size
        val summaryLine = buildString {
            append("Manual: $total  |  ✅ $watchMatched  |  ❌ $watchMissed")
            if (watchExtra > 0) append("  |  ⚠️ +$watchExtra extra")
            append("  |  Watch total: $highestRepNum")
        }
        summaryText.text = summaryLine
        summaryText.setTextColor(
            when {
                watchExtra > 0  -> Color.parseColor("#FF9500")
                watchMissed > 0 -> Color.parseColor("#FF9500")
                else            -> Color.parseColor("#34C759")
            }
        )

        fullReport.insert(
            fullReport.indexOf("\n") + 1,
            "$summaryLine\n"
        )
        reportText = fullReport.toString()
    }

    private fun addCard(
        badgeText: String,
        cardText: String,
        cardColor: Int,
        badgeColor: Int
    ) {
        val badge = TextView(this)
        badge.textSize = 13f
        badge.typeface = android.graphics.Typeface.DEFAULT_BOLD
        badge.setPadding(28, 16, 28, 16)
        badge.text = badgeText
        badge.setTextColor(Color.WHITE)
        badge.setBackgroundColor(badgeColor)

        val card = TextView(this)
        card.textSize = 11.5f
        card.typeface = android.graphics.Typeface.MONOSPACE
        card.setPadding(28, 28, 28, 28)
        card.setTextColor(Color.parseColor("#EBEBF5"))
        card.setBackgroundColor(cardColor)
        card.text = cardText

        val divider = TextView(this)
        divider.setBackgroundColor(Color.parseColor("#2C2C4A"))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2)
        lp.setMargins(0, 12, 0, 12)
        divider.layoutParams = lp

        container.addView(badge)
        container.addView(card)
        container.addView(divider)
    }

    private fun extractMag(lines: List<String>, key: String): Float? {
        if (lines.isEmpty()) return null
        return try {
            val line  = lines.last()
            val start = line.indexOf(key)
            if (start < 0) return null
            val sub   = line.substring(start + key.length)
            val end   = sub.indexOfFirst { !it.isDigit() && it != '.' }
            val numStr = if (end < 0) sub else sub.substring(0, end)
            numStr.toFloatOrNull()
        } catch (e: Exception) { null }
    }

    private var reportText = ""

    private fun copyAllToClipboard() {
        if (reportText.isEmpty()) {
            Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rep Comparison", reportText))
        Toast.makeText(this, "Report copied!", Toast.LENGTH_SHORT).show()
    }
}