package com.example.storysaver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var box: FrameLayout
    private lateinit var loginWeb: WebView

    private var fetcher: StoryFetcher? = null
    private var loginShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        result = TextView(this).apply { textSize = 13f }

        val login = Button(this).apply {
            text = "1. Iniciar sesión en Instagram"
            setOnClickListener {
                loginShown = true
                showInBox(loginWeb)
                loginWeb.loadUrl("https://www.instagram.com/accounts/login/")
            }
        }

        val accessibility = Button(this).apply {
            text = "2. Activar accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        // Prueba sin pasar por Instagram ni por el servicio: abre las stories de un usuario
        // en un WebView VISIBLE para ver qué carga y guarda lo que encuentre.
        val userInput = EditText(this).apply {
            hint = "usuario con stories activas (sin @)"
            setSingleLine()
        }
        val test = Button(this).apply {
            text = "Probar descarga (visible)"
            setOnClickListener {
                val u = userInput.text.toString().trim().removePrefix("@")
                if (u.isEmpty()) return@setOnClickListener
                loginShown = false
                val f = fetcher ?: StoryFetcher(this@MainActivity).also { fetcher = it }
                f.view.alpha = 1f
                showInBox(f.view)
                result.text = "Leyendo stories de @$u…"
                f.fetch(u) { items, err ->
                    box.visibility = View.GONE
                    if (err != null || items.isEmpty()) {
                        result.text = "✖ " + (err ?: "sin stories")
                        return@fetch
                    }
                    result.text = "Guardando ${items.size}…"
                    Thread {
                        var saved = 0
                        var skipped = 0
                        var failed: String? = null
                        try {
                            for (m in items) {
                                if (Saver.save(applicationContext, m, u)) saved++ else skipped++
                            }
                        } catch (e: Exception) {
                            failed = e.message
                        }
                        runOnUiThread {
                            result.text = if (failed != null) {
                                "✖ $failed"
                            } else {
                                "✔ $saved guardadas, $skipped ya estaban (@${items.first().user.ifEmpty { u }})"
                            }
                        }
                    }.start()
                }
            }
        }

        // Modo de descarga (se guarda en prefs "mode"):
        //  all     = al tocar ⬇ se guardan todas las stories del usuario
        //  pick    = al tocar ⬇ aparece una barra para elegir UNA story (numeradas en orden)
        //  current = se guarda al instante la story que estás viendo (precarga en segundo plano)
        val prefs = getSharedPreferences("s", MODE_PRIVATE)
        val saved = prefs.getString("mode", null)
            ?: if (prefs.getBoolean("pick", false)) "pick" else "all"

        val modes = listOf(
            "all" to "Todas las stories del usuario",
            "pick" to "Elegir una sola (aparece la lista al tocar ⬇)",
            "current" to "La que estoy viendo (casi instantáneo)"
        )
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ids = HashMap<Int, String>()
        for ((key, label) in modes) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                isChecked = key == saved
            }
            ids[rb.id] = key
            modeGroup.addView(rb)
        }
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            ids[checkedId]?.let { prefs.edit().putString("mode", it).apply() }
        }

        val help = TextView(this).apply {
            text = "Android 13+: si el servicio aparece bloqueado, ve a Ajustes → Apps → StorySaver → " +
                    "menú ⋮ → \"Permitir configuración restringida\", y luego actívalo en Accesibilidad."
            textSize = 12f
        }

        box = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        loginWeb = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = Ig.UA
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    saveCookie()
                }
            }
        }

        root.addView(status)
        root.addView(login)
        root.addView(accessibility)
        root.addView(modeGroup)
        root.addView(userInput)
        root.addView(test)
        root.addView(result)
        root.addView(help)
        root.addView(box)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        box.removeAllViews()
        fetcher?.destroy()
        loginWeb.destroy()
        super.onDestroy()
    }

    private fun showInBox(v: View) {
        box.removeAllViews()
        (v.parent as? ViewGroup)?.removeView(v)
        box.addView(
            v,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        box.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            box.visibility == View.VISIBLE && loginShown && loginWeb.canGoBack() -> loginWeb.goBack()
            box.visibility == View.VISIBLE -> box.visibility = View.GONE
            else -> super.onBackPressed()
        }
    }

    private fun saveCookie() {
        val cookie = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return
        if (cookie.contains("sessionid=")) {
            getSharedPreferences("s", MODE_PRIVATE).edit().putString("cookie", cookie).apply()
            if (loginShown) {
                box.visibility = View.GONE
                loginShown = false
            }
            refresh()
        }
    }

    private fun refresh() {
        val hasSession = getSharedPreferences("s", MODE_PRIVATE).getString("cookie", null) != null
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = "Sesión de Instagram: " + (if (hasSession) "guardada ✔" else "falta") +
                "\nAccesibilidad: " + (if (enabled) "activa ✔" else "falta")
    }
}