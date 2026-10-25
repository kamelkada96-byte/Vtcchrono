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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class VtcService : AccessibilityService() {
    private val UBER = "com.ubercab.driver"
    private val TRAFIC = 1.25
    private val VITESSE = 28.0
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var card: TextView? = null
    private var gen = 0
    private val h = Handler(Looper.getMainLooper())
    private val hide = Runnable { hideCard() }

    private val pickRx = Regex("""(\d+)\s*min\s*\(?\s*(\d+(?:[.,]\d+)?)\s*km""", RegexOption.IGNORE_CASE)
    private val rideRx = Regex("""(?:course|trajet|trip)\s*(?:de|of)?\s*(\d+(?:[.,]\d+)?)\s*km""", RegexOption.IGNORE_CASE)
    private val fareRx = Regex("""(\d+(?:[.,]\d{1,2})?)\s*€""")

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

    private fun collect(n: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (n == null) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun screenTexts(): List<String>? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        for (w in windows) w.root?.let { roots.add(it) }
        val uber = roots.filter { it.packageName == UBER }
        if (uber.isEmpty()) return null
        val out = LinkedHashSet<String>()
        uber.forEach { collect(it, out) }
        return out.toList()
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()
    private fun km(k: Double) = String.format(Locale.FRANCE, "%.1f", k)

    private fun render(pm: Int, pk: Double, rk: Double, rideMin: Int, exact: Boolean, fare: Double?): String {
        val sb = StringBuilder()
        val pre = if (exact) "" else "≈ "
        if (pm > 0) sb.append("🚗 Client : $pm min · ${km(pk)} km\n")
        if (rk > 0) sb.append("➡️ Course : $pre$rideMin min · ${km(rk)} km\n")
        if (pm > 0 && rk > 0) sb.append("Σ Total : $pre${pm + rideMin} min · ${km(pk + rk)} km\n")
        val mins = pm + rideMin
        if (fare != null && rk > 0 && mins > 0) {
            sb.append("💶 ").append(String.format(Locale.FRANCE, "%.0f €/h · %.2f €/km", fare / (mins / 60.0), fare / (pk + rk)))
        }
        if (!exact && rk > 0) sb.append("\n⏳ calcul précis…")
        return sb.toString().trimEnd()
    }

    private fun analyze() {
        val t = screenTexts()
        if (t == null) { showCard("Ouvre Uber Driver, puis touche la bulle."); return }
        var pm = 0; var pk = 0.0; var rk = 0.0; var a1 = ""; var a2 = ""
        var gotP = false; var gotR = false
        t.forEachIndexed { i, s ->
            if (!gotP) pickRx.find(s)?.let { pm = it.groupValues[1].toInt(); pk = num(it.groupValues[2]); gotP = true; a1 = t.getOrElse(i + 1) { "" } }
            if (!gotR) rideRx.find(s)?.let { rk = num(it.groupValues[1]); gotR = true; a2 = t.getOrElse(i + 1) { "" } }
        }
        if (!gotP && !gotR) {
            showCard("Rien trouvé (${t.size} lignes lues).\nAppui long sur la bulle = copier le texte de l’écran.")
            return
        }
        val fare = fareRx.find(t.joinToString(" "))?.let { num(it.groupValues[1]) }
        val est = Math.round(rk / VITESSE * 60).toInt()
        val g = ++gen
        showCard(render(pm, pk, rk, est, false, fare))
        if (rk > 0 && a1.isNotBlank() && a2.isNotBlank()) {
            thread {
                try {
                    val p1 = geo(a1)
                    Thread.sleep(1100)
                    val p2 = geo(a2)
                    val m = Math.round(route(p1, p2) * TRAFIC / 60).toInt()
                    h.post { if (g == gen) showCard(render(pm, pk, rk, m, true, fare)) }
                } catch (e: Exception) { }
            }
        }
    }

    private fun http(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "VTCChrono/1.0 (appli personnelle)")
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().readText()
    }

    private fun geo(q: String): DoubleArray {
        val j = JSONArray(http("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8")))
        if (j.length() == 0) throw Exception("Adresse introuvable")
        val o = j.getJSONObject(0)
        return doubleArrayOf(o.getString("lat").toDouble(), o.getString("lon").toDouble())
    }

    private fun route(a: DoubleArray, b: DoubleArray): Double {
        val u = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=false", a[1], a[0], b[1], b[0])
        val r = JSONObject(http(u)).getJSONArray("routes")
        if (r.length() == 0) throw Exception("Aucun itinéraire")
        return r.getJSONObject(0).getDouble("duration")
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
