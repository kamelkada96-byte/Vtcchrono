package com.vtcchrono

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt(); setPadding(p, (48 * dp).toInt(), p, p)
        }
        root.addView(TextView(this).apply { text = "⏱ VTC Chrono"; textSize = 24f })
        status = TextView(this).apply { textSize = 16f; setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt()) }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "1. Touche « Autoriser les réglages restreints », puis ⋮ en haut à droite → « Autoriser les paramètres restreints ».\n\n" +
                "2. Touche « Activer le service », ouvre « Applications installées » → VTC Chrono → active-le.\n\n" +
                "3. Ouvre Uber Driver : une bulle ⏱ apparaît. Quand une course s’affiche, touche la bulle pour voir les durées et les km.\n\n" +
                "Tu peux déplacer la bulle. Appui long = copier le texte de l’écran (utile pour régler la lecture)."
        })
        val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() }
        root.addView(Button(this).apply {
            text = "Autoriser les réglages restreints"
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }, lp)
        root.addView(Button(this).apply {
            text = "Activer le service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, lp)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)
        status.text = if (on) "✅ Service actif" else "⚠️ Service désactivé"
    }
}
