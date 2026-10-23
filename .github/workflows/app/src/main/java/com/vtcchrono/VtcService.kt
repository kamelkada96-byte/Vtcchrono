package com.vtcchrono

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class VtcService : AccessibilityService() {
    private val UBER = "com.ubercab.driver"
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var card: TextView? = null
    private val h = Handler(Looper.getMainLooper())
    private val hide = Runnable { hideCard() }

    private val rx = Regex("""(?:(\d+)\s*h(?:eures?|r)?\s*)?(\d+)\s*min[^\d]{0,8}(\d+(?:[.,]\d+)?)\s*km""", RegexOption.IGNORE_CASE)
    private val fareRx = Regex("""(\d+(?:[.,]\d{1,2})?)\s*€|€\s*(\d+(?:[.,]\d{1,2})?)""")
    private val rideKw = Regex("""trajet|trip|course|ride|voyage""")
    private val pickKw = Regex("""away|pickup|prise|client|de vous""")

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        hideCard()
        super.onDestroy()
    }

    private fun params(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (58 * d).toInt()
        val b = TextView(this).apply {
            text = "⏱"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE61A73E8.toInt()) }
        }
        val p = params().apply { width = size; height = size; x = 0; y = (220 * d).toInt() }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var long = false
        val lr = Runnable { long = true; dump() }
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false; long = false; h.postDelayed(lr, 800) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && (abs(dx) > 15 || abs(dy) > 15)) { moved = true; h.removeCallbacks(lr) }
                    if (moved) { p.x = (ox + dx).toInt(); p.y = (oy + dy).toInt(); wm.updateViewLayout(b, p) }
                }
                MotionEvent.ACTION_UP -> { h.removeCallbacks(lr); if (!moved && !long) analyze() }
                MotionEvent.ACTION_CANCEL -> h.removeCallbacks(lr)
            }
            true
        }
        wm.addView(b, p)
        bubble = b
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (n == null) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun screenTexts(): List<String>? {
        val root = rootInActiveWindow?.takeIf { it.packageName == UBER }
            ?: windows.mapNotNull { it.root }.firstOrNull { it.packageName == UBER }
            ?: return null
        val out = mutableListOf<String>()
        collect(root, out)
        return out
    }

    private fun fmt(m: Int, k: Double) = "$m min · " + String.format(Locale.FRANCE, "%.1f", k) + " km"

    private fun analyze() {
        val t = screenTexts()
        if (t == null) { showCard("Ouvre Uber Driver, puis touche la bulle."); return }
        val joined = t.joinToString(" | ")
        var tc: Pair<Int, Double>? = null
        var rd: Pair<Int, Double>? = null
        val rest = mutableListOf<Pair<Int, Double>>()
        for (m in rx.findAll(joined)) {
            val min = (m.groupValues[1].toIntOrNull() ?: 0) * 60 + m.groupValues[2].toInt()
            val km = m.groupValues[3].replace(',', '.').toDouble()
            val v = Pair(min, km)
            val ctx = joined.substring(maxOf(0, m.range.first - 25), m.range.first).lowercase()
            when {
                rideKw.containsMatchIn(ctx) && rd == null -> rd = v
                pickKw.containsMatchIn(ctx) && tc == null -> tc = v
                else -> rest.add(v)
            }
        }
        for (v in rest) { if (tc == null) tc = v else if (rd == null) rd = v }
        val a = tc; val b = rd
        val sb = StringBuilder()
        if (a != null) sb.append("🚗 Client : ").append(fmt(a.first, a.second)).append('\n')
        if (b != null) sb.append("➡️ Course : ").append(fmt(b.first, b.second)).append('\n')
        if (a != null && b != null) sb.append("Σ Total : ").append(fmt(a.first + b.first, a.second + b.second)).append('\n')
        val fm = fareRx.find(joined)
        val fare = fm?.let { r -> r.groupValues[1].ifEmpty { r.groupValues[2] }.replace(',', '.').toDoubleOrNull() }
        if (fare != null && b != null) {
            val mins = (a?.first ?: 0) + b.first
            val kms = (a?.second ?: 0.0) + b.second
            if (mins > 0 && kms > 0) sb.append("💶 ").append(String.format(Locale.FRANCE, "≈ %.0f €/h · %.2f €/km", fare / (mins / 60.0), fare / kms))
        }
        if (sb.isEmpty()) sb.append("Rien trouvé sur cet écran.\nAppui long sur la bulle = copier le texte de l’écran.")
        showCard(sb.toString().trimEnd())
    }

    private fun dump() {
        val t = screenTexts()
        if (t == null) { showCard("Ouvre Uber Driver, puis fais un appui long sur la bulle."); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ecran", t.joinToString("\n")))
        showCard("Texte de l’écran copié (${t.size} lignes). Colle-le dans ta conversation avec Claude (masque les noms et adresses si tu veux).")
    }

    private fun showCard(s: String) {
        hideCard()
        val d = resources.displayMetrics.density
        val c = TextView(this).apply {
            text = s; textSize = 18f; setTextColor(Color.WHITE)
            val p = (16 * d).toInt(); setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0xF2202833.toInt()) }
            setOnClickListener { hideCard() }
        }
        val p = params().apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (110 * d).toInt() }
        wm.addView(c, p)
        card = c
        h.postDelayed(hide, 12000)
    }

    private fun hideCard() {
        h.removeCallbacks(hide)
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }
}
