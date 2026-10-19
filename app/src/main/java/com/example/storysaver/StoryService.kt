package com.example.storysaver

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class StoryService : AccessibilityService() {

    companion object {
        private const val TAG = "StorySaver"
        private const val IG = "com.instagram.android"

        // true = muestra el WebView que lee las stories (semitransparente, a pantalla completa)
        // para ver qué está cargando. false = prácticamente invisible. Para depurar.
        private const val SHOW_WEBVIEW = false

        // ID de vista donde Instagram muestra el username en el visor de stories
        // (verificado en tu emulador: con este ID aparece el botón).
        private val TITLE_IDS = listOf("$IG:id/reel_viewer_title")

        private val USER = Regex("[A-Za-z0-9._]{1,30}")
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var wm: WindowManager
    private lateinit var button: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var fetcher: StoryFetcher? = null

    private var shown = false
    private var scheduled = false
    private var username: String? = null

    @Volatile
    private var busy = false

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        button = TextView(this).apply {
            text = "⬇"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 0, 0, 0))
            }
            setOnClickListener { download() }
        }

        val size = (52 * dp).toInt()
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (12 * dp).toInt()
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return // eventos de nuestro propio overlay

        if (pkg != IG) {
            // El usuario salió de Instagram: ocultar el botón.
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                username = null
                setShown(false)
            }
            return
        }

        // Los eventos llegan a ráfagas: evaluar como máximo cada 300 ms.
        if (!scheduled) {
            scheduled = true
            main.postDelayed({ scheduled = false; check() }, 300)
        }
    }

    private fun check() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return

        var user: String? = null
        for (id in TITLE_IDS) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull() ?: continue
            val token = node.text?.toString()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            if (token != null && USER.matches(token)) {
                user = token
                break
            }
        }

        if (user == null && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            dump(root, 0)
        }

        // Mientras descarga no tocamos el estado del botón.
        if (!busy) {
            username = user
            setShown(user != null)
        }
    }

    private fun setShown(v: Boolean) {
        if (v == shown) return
        try {
            if (v) wm.addView(button, params) else wm.removeView(button)
            shown = v
        } catch (e: Exception) {
            Log.e(TAG, "overlay", e)
        }
    }

    // El WebView que lee las stories necesita estar en una ventana real para renderizar:
    // lo añadimos a pantalla completa, no tocable y casi transparente mientras dura la lectura.
    private fun attachWeb(v: WebView) {
        if (v.parent != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        v.alpha = if (SHOW_WEBVIEW) 0.7f else 0.01f
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            Log.e(TAG, "attachWeb", e)
        }
    }

    private fun detachWeb(v: WebView) {
        try {
            if (v.parent != null) wm.removeView(v)
        } catch (e: Exception) {
            Log.e(TAG, "detachWeb", e)
        }
    }

    private fun download() {
        val user = username ?: return
        if (busy) return
        val cookie = getSharedPreferences("s", MODE_PRIVATE).getString("cookie", null)
        if (cookie == null) {
            toast("Abre StorySaver e inicia sesión")
            return
        }
        busy = true
        button.text = "…"
        toast("Leyendo stories de @$user…")

        val f = fetcher ?: StoryFetcher(applicationContext).also { fetcher = it }
        attachWeb(f.view)
        f.fetch(user) { items, err ->
            detachWeb(f.view)
            if (err != null || items.isEmpty()) {
                Log.w(TAG, "sin stories: $err")
                toast(err ?: "Sin stories")
                done("✖")
                busy = false
                return@fetch
            }
            io.execute {
                try {
                    var saved = 0
                    var skipped = 0
                    for (m in items) {
                        if (Saver.save(this@StoryService, m, user)) saved++ else skipped++
                    }
                    toast("@${items.first().user.ifEmpty { user }}: $saved guardadas, $skipped ya estaban")
                    done("✔")
                } catch (e: Exception) {
                    Log.e(TAG, "falló el guardado", e)
                    toast("Error: ${e.message}")
                    done("✖")
                } finally {
                    busy = false
                }
            }
        }
    }

    // Resultado visible en el propio botón: los toasts pueden estar bloqueados si las
    // notificaciones de la app están desactivadas (en logcat sale "Suppressing toast").
    private fun done(label: String) {
        main.post {
            button.text = label
            main.postDelayed({ button.text = "⬇" }, 2500)
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dump(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 25) return
        val id = n.viewIdResourceName
        if (id != null) Log.d(TAG, "$id | text='${n.text}' | desc='${n.contentDescription}'")
        for (i in 0 until n.childCount) dump(n.getChild(i), depth + 1)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        setShown(false)
        fetcher?.let {
            detachWeb(it.view)
            it.destroy()
        }
        fetcher = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
