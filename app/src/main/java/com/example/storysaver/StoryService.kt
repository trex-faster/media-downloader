package com.example.storysaver

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class StoryService : AccessibilityService() {

    companion object {
        private const val TAG = "StorySaver"
        private const val IG = "com.instagram.android"

        // true = muestra el WebView que lee las stories (semitransparente, a pantalla completa)
        // para ver qué está cargando. false = prácticamente invisible. Para depurar.
        private const val SHOW_WEBVIEW = false

        // Modos (prefs "mode", los elige MainActivity).
        private const val MODE_ALL = "all"         // todas las stories del usuario
        private const val MODE_PICK = "pick"       // elegir una de la lista
        private const val MODE_CURRENT = "current" // la que se está viendo, casi al instante

        // Intervalos de evaluación (ms).
        private const val DELAY_FAST = 150L   // cambio de ventana
        private const val DELAY_STORY = 300L  // botón visible
        private const val DELAY_IDLE = 700L   // feed, DMs, perfil…
        private const val WATCHDOG_MS = 1000L // comprobación de "¿sigo en Instagram?" con el botón visible

        // Modo "current": precarga de las stories del usuario que estás viendo.
        // Las stories duran 3-15 s: la lectura debe estar lista antes de que toques ⬇.
        private const val PREFETCH_DELAY = 350L         // precarga en cuanto el usuario se queda un instante
        private const val PREFETCH_MIN_GAP = 1200L      // como mucho una lectura cada 1,2 s al pasar rápido
        private const val CACHE_TTL = 5 * 60 * 1000L    // se descarta a los 5 min
        private const val CACHE_MAX = 8                 // se guardan los últimos 8 usuarios
        private const val FAIL_BACKOFF = 10_000L        // tras un fallo, no reintentar solo durante 10 s

        // ID de vista donde Instagram muestra el username en el visor de stories
        private val TITLE_IDS = listOf("$IG:id/reel_viewer_title")

        private val USER = Regex("[A-Za-z0-9._]{1,30}")
    }

    /** Lo que se sabe en el instante de tocar ⬇ (el visor puede cambiar justo después). */
    private class Tap(val user: String, val age: LongRange?, val titleTop: Int) {
        /** Firmas de la pantalla CONGELADA en el instante del toque (null si no se pudo capturar). */
        var sigs: List<FloatArray>? = null

        /** Franja superior de la pantalla congelada (barra de progreso de segmentos). */
        var band: CurrentStory.Band? = null
        var retried = false
    }

    /** Stories de un usuario ya leídas + firmas de sus miniaturas (para reconocer cuál se ve). */
    private class Cached(val items: List<StoryFetcher.Media>, val at: Long) {
        val sigs = ConcurrentHashMap<String, FloatArray>()
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()   // guardado de archivos
    private val pre = Executors.newSingleThreadExecutor()  // miniaturas / captura / comparación

    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }

    private lateinit var wm: WindowManager
    private lateinit var button: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var fetcher: StoryFetcher? = null

    private var shown = false
    private var scheduled = false
    private var username: String? = null

    private val isDebuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private var dumpPending = false
    private val tmpRect = Rect()
    private var lastTitleTop = -1

    // Precarga / caché (solo hilo principal).
    private val cache = LinkedHashMap<String, Cached>()
    private val failUntil = HashMap<String, Long>()
    private var prefetching = false
    private var prefetchingUser: String? = null
    private var prefetchScheduled: String? = null
    private var lastPrefetchAt = 0L
    private var waiting: Tap? = null
    private val prefetchRun = Runnable { startScheduledPrefetch() }

    private var pickerView: View? = null
    private var pickCallback: ((List<StoryFetcher.Media>?) -> Unit)? = null
    private val pickerTimeout = Runnable { resolvePicker(null) }

    @Volatile
    private var busy = false

    private var warnedShot = false

    private fun mode(): String =
        prefs.getString("mode", null) ?: if (prefs.getBoolean("pick", false)) MODE_PICK else MODE_ALL

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
        val type = e.eventType

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return // eventos de nuestro propio overlay

        if (pkg != IG) {
            // El usuario salió de Instagram: ocultar el botón y soltar la precarga en curso.
            // Se reacciona a CUALQUIER evento de otra app (algunos launchers no mandan
            // TYPE_WINDOW_STATE_CHANGED al volver al inicio).
            leaveInstagram()
            return
        }

        if (busy) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) dumpPending = true

        if (!scheduled) {
            scheduled = true
            val delay = when {
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> DELAY_FAST
                shown -> DELAY_STORY
                else -> DELAY_IDLE
            }
            main.postDelayed({ scheduled = false; check() }, delay)
        }
    }

    /**
     * Usuario de la story que se ve AHORA. El visor de Instagram mantiene también la vista del
     * usuario vecino (precargada, fuera de pantalla) con el mismo ID, así que se elige el visible
     * y, si hay varios, el de mayor área dentro de la pantalla. Deja en [lastTitleTop] la
     * coordenada Y del título elegido (sirve de ancla para localizar la tarjeta de la story).
     */
    private fun currentUser(root: AccessibilityNodeInfo, log: Boolean): String? {
        val dm = resources.displayMetrics
        var bestVisible: String? = null
        var visibleArea = 0L
        var visibleTop = -1
        var bestAny: String? = null
        var anyArea = 0L
        var anyTop = -1

        for (id in TITLE_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (node in nodes) {
                try {
                    val text = node.text?.toString()?.trim() ?: continue
                    val token = text.takeWhile { !it.isWhitespace() }
                    if (token.isEmpty() || !USER.matches(token)) continue

                    node.getBoundsInScreen(tmpRect)
                    val r = tmpRect
                    val w = maxOf(0, minOf(r.right, dm.widthPixels) - maxOf(r.left, 0))
                    val h = maxOf(0, minOf(r.bottom, dm.heightPixels) - maxOf(r.top, 0))
                    val area = w.toLong() * h
                    if (log) Log.d(TAG, "título @$token visible=${node.isVisibleToUser} bounds=$r área=$area")

                    if (node.isVisibleToUser && area > visibleArea) {
                        bestVisible = token
                        visibleArea = area
                        visibleTop = r.top
                    }
                    if (area > anyArea) {
                        bestAny = token
                        anyArea = area
                        anyTop = r.top
                    }
                } finally {
                    recycle(node)
                }
            }
        }
        lastTitleTop = if (bestVisible != null) visibleTop else anyTop
        return bestVisible ?: bestAny
    }

    /**
     * Busca en la parte alta de la pantalla el texto de edad del visor ("8h", "23m"…).
     * Solo se usa al tocar ⬇ en modo "current" (recorre el árbol una vez).
     */
    private fun findAge(n: AccessibilityNodeInfo?, depth: Int, maxTop: Int, screenW: Int): LongRange? {
        if (n == null || depth > 25) return null
        val t = n.text?.toString()
        if (t != null && t.length <= 60) {
            n.getBoundsInScreen(tmpRect)
            if (tmpRect.top < maxTop && tmpRect.bottom > 0 && tmpRect.left < screenW && tmpRect.right > 0) {
                CurrentStory.parseAge(t)?.let { return it }
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i)
            val r = findAge(c, depth + 1, maxTop, screenW)
            recycle(c)
            if (r != null) return r
        }
        return null
    }

    private fun check() {
        if (busy) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != IG) {
                leaveInstagram()
                return
            }

            val user = currentUser(root, false)

            if (user == null && dumpPending && isDebuggable) {
                dumpPending = false
                dump(root, 0)
            }

            if (!busy) {
                username = user
                setShown(user != null)
                maybePrefetch(user)
            }
        } finally {
            recycle(root)
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
        main.removeCallbacks(watchdog)
        if (shown) main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Salió de Instagram (inicio, otra app, bloqueo...): botón fuera y precarga cancelada. */
    private fun leaveInstagram() {
        username = null
        setShown(false)
        prefetchScheduled = null
        main.removeCallbacks(prefetchRun)
        if (prefetching && waiting == null) fetcher?.cancel()
    }

    // Red de seguridad: mientras el botón está visible, comprueba cada segundo que la ventana
    // activa siga siendo Instagram. Si no (o si no hay ventana activa, p. ej. pantalla bloqueada),
    // lo oculta aunque no haya llegado ningún evento.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!shown) return
            val root = rootInActiveWindow
            val inIg = try {
                root?.packageName?.toString() == IG
            } finally {
                recycle(root)
            }
            if (!inIg && !busy) leaveInstagram()
            if (shown) main.postDelayed(this, WATCHDOG_MS)
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

    private fun ensureFetcher(): StoryFetcher =
        fetcher ?: StoryFetcher(applicationContext).also { fetcher = it }

    // ---- Descarga al tocar ⬇ ---------------------------------------------------------------

    private fun download() {
        if (busy) return
        val mode = mode()

        // Se vuelve a detectar al tocar: no se confía en el último valor guardado.
        val root = rootInActiveWindow
        var detected: String? = null
        var age: LongRange? = null
        var top = -1
        if (root != null) {
            try {
                detected = currentUser(root, true)
                top = lastTitleTop
                if (mode == MODE_CURRENT) {
                    val dm = resources.displayMetrics
                    age = findAge(root, 0, (dm.heightPixels * 0.3f).toInt(), dm.widthPixels)
                }
            } finally {
                recycle(root)
            }
        }
        val user = detected ?: username ?: return

        if (prefs.getString("cookie", null) == null) {
            toast("Abre StorySaver e inicia sesión")
            return
        }
        // Una precarga en curso solo se puede aprovechar en modo "current".
        if (prefetching && mode != MODE_CURRENT) {
            toast("Espera un momento…")
            return
        }

        busy = true
        button.text = "…"

        if (mode == MODE_CURRENT) {
            downloadCurrent(Tap(user, age, top))
            return
        }

        toast("Leyendo stories de @$user…")
        val pick = mode == MODE_PICK
        val f = ensureFetcher()
        attachWeb(f.view)
        f.fetch(user) { items, err ->
            // Garantiza hilo principal: se tocan WindowManager y vistas.
            main.post { onFetched(f, user, items, err, pick) }
        }
    }

    private fun onFetched(
        f: StoryFetcher,
        user: String,
        items: List<StoryFetcher.Media>,
        err: String?,
        pick: Boolean
    ) {
        detachWeb(f.view)
        if (err != null || items.isEmpty()) {
            Log.w(TAG, "sin stories: $err")
            toast(err ?: "Sin stories")
            done("✖")
            busy = false
            return
        }
        if (pick && items.size > 1) {
            showPicker(user, items) { chosen ->
                if (chosen == null) {
                    done("–")
                    busy = false
                } else {
                    saveAll(user, chosen)
                }
            }
        } else {
            saveAll(user, items)
        }
    }

    private fun saveAll(user: String, items: List<StoryFetcher.Media>) {
        button.text = "…"
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

    // ---- Modo "la que estoy viendo" ----------------------------------------------------------

    private fun cacheGet(key: String): Cached? {
        val c = cache[key] ?: return null
        if (System.currentTimeMillis() - c.at > CACHE_TTL) {
            cache.remove(key)
            return null
        }
        return c
    }

    private fun cachePut(key: String, items: List<StoryFetcher.Media>): Cached {
        val now = System.currentTimeMillis()
        val c = Cached(items, now)
        cache.entries.removeAll { now - it.value.at > CACHE_TTL }
        cache.remove(key)
        cache[key] = c
        while (cache.size > CACHE_MAX) cache.remove(cache.keys.first())
        return c
    }

    /** Mientras miras las stories de alguien, las lee en segundo plano para que ⬇ sea instantáneo. */
    private fun maybePrefetch(user: String?) {
        if (user == null || busy || mode() != MODE_CURRENT) return
        ensureFetcher().warmUp() // deja cargada la página liviana: las lecturas serán casi inmediatas
        val key = user.lowercase()
        if (prefetching) {
            // Pasaste a otro usuario: se abandona la lectura anterior (su callback limpia el estado).
            if (prefetchingUser != key && waiting == null) fetcher?.cancel()
            return
        }
        if (cacheGet(key) != null) return
        if ((failUntil[key] ?: 0L) > System.currentTimeMillis()) return
        if (prefetchScheduled == key) return
        prefetchScheduled = key
        main.removeCallbacks(prefetchRun)
        main.postDelayed(prefetchRun, PREFETCH_DELAY)
    }

    private fun startScheduledPrefetch() {
        val key = prefetchScheduled ?: return
        prefetchScheduled = null
        if (busy || prefetching) return
        val user = username ?: return
        if (user.lowercase() != key) return

        // Pasando rápido de usuario en usuario: se espera para no encadenar lecturas.
        val wait = PREFETCH_MIN_GAP - (System.currentTimeMillis() - lastPrefetchAt)
        if (wait > 0) {
            prefetchScheduled = key
            main.postDelayed(prefetchRun, wait)
            return
        }
        startPrefetch(user)
    }

    private fun startPrefetch(user: String) {
        lastPrefetchAt = System.currentTimeMillis()
        prefetching = true
        prefetchingUser = user.lowercase()
        val f = ensureFetcher()
        attachWeb(f.view)
        f.fetch(user) { items, err -> main.post { onPrefetched(f, user, items, err) } }
    }

    private fun onPrefetched(f: StoryFetcher, user: String, items: List<StoryFetcher.Media>, err: String?) {
        detachWeb(f.view)
        prefetching = false
        prefetchingUser = null
        val key = user.lowercase()

        val tap = waiting
        if (tap != null && tap.user.lowercase() != key) {
            // La lectura terminada era de otro usuario; el tocado espera por este.
            startPrefetch(tap.user)
            return
        }

        if (err != null || items.isEmpty()) {
            if (err != "cancelado") failUntil[key] = System.currentTimeMillis() + FAIL_BACKOFF
            Log.w(TAG, "precarga @$user: $err")
            if (tap != null) {
                // El toque no se pierde por un fallo puntual de red/Instagram: un reintento.
                if (err != "cancelado" && !tap.retried) {
                    tap.retried = true
                    startPrefetch(user) // waiting sigue apuntando al toque
                    return
                }
                waiting = null
                toast(err ?: "Sin stories")
                done("✖")
                busy = false
            }
            return
        }

        waiting = null
        // Las miniaturas ya no se descargan aquí: la barra de progreso resuelve casi siempre y
        // resolveCurrent las baja solo si hace falta (así no compiten por la red con la lectura).
        val c = cachePut(key, items)
        if (tap != null) resolveCurrent(c, tap)
    }

    /** Miniaturas -> firmas, en segundo plano. Solo hace falta si hay varias stories. */
    private fun computeSigs(c: Cached) {
        if (c.items.size < 2) return
        pre.execute {
            for (m in c.items) {
                CurrentStory.thumbSignature(m.thumb)?.let { c.sigs[m.id] = it }
            }
        }
    }

    /** Stories que encajan con la edad que mostraba el visor (si no encaja ninguna, todas). */
    private fun candidates(items: List<StoryFetcher.Media>, tap: Tap): List<StoryFetcher.Media> {
        val range = tap.age ?: return items
        val nowSec = System.currentTimeMillis() / 1000
        val f = items.filter { CurrentStory.ageMatches(it, range, nowSec) }
        return if (f.isEmpty()) items else f
    }

    private fun downloadCurrent(tap: Tap) {
        val key = tap.user.lowercase()

        // Camino instantáneo: precarga lista y la edad deja una sola story -> sin captura.
        cacheGet(key)?.let { c ->
            val cands = candidates(c.items, tap)
            if (cands.size == 1) {
                saveAll(tap.user, cands)
                return
            }
        }

        // Puede haber varias candidatas (o aún no hay datos): se CONGELA la pantalla ya, antes de
        // esperar nada, porque la story sigue avanzando mientras llegan los datos.
        freezeScreen(tap) {
            val c = cacheGet(key)
            if (c != null) {
                resolveCurrent(c, tap)
            } else {
                // Sin precarga lista: se espera a la que está en curso, o se lanza ahora.
                toast("Leyendo stories de @${tap.user}…")
                waiting = tap
                if (!prefetching) {
                    startPrefetch(tap.user)
                } else if (prefetchingUser != key) {
                    fetcher?.cancel() // su callback arranca la lectura de este usuario
                }
            }
        }
    }

    /**
     * Captura la pantalla justo ahora (con nuestro botón oculto para que no ensucie la imagen),
     * guarda sus firmas en [tap] y continúa con [next] en el hilo principal.
     * Nunca se queda colgado: si la captura falla o tarda, continúa sin firmas.
     */
    private fun freezeScreen(tap: Tap, next: () -> Unit) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(sigs: List<FloatArray>?, band: CurrentStory.Band?) {
            if (!once.compareAndSet(false, true)) return
            main.post {
                button.alpha = 1f
                tap.sigs = sigs
                tap.band = band
                next()
            }
        }
        button.alpha = 0f
        main.postDelayed({ finish(null, null) }, 2500) // tope de seguridad
        main.postDelayed({
            grabScreen { shot ->
                var sigs: List<FloatArray>? = null
                var band: CurrentStory.Band? = null
                if (shot != null) {
                    try {
                        band = CurrentStory.readBand(shot, tap.titleTop)
                        sigs = CurrentStory.screenSignatures(shot, tap.titleTop)
                    } catch (e: Exception) {
                        Log.e(TAG, "firma de pantalla", e)
                    } finally {
                        shot.recycle()
                    }
                } else if (!warnedShot) {
                    warnedShot = true
                    toast("No se pudo capturar la pantalla: activa canTakeScreenshot en la config de accesibilidad y reactiva el servicio")
                }
                finish(sigs, band)
            }
        }, 40) // deja que el sistema repinte sin el botón
    }

    private fun resolveCurrent(c: Cached, tap: Tap) {
        val items = c.items
        val cands = candidates(items, tap)

        if (cands.size == 1) {
            saveAll(tap.user, cands)
            return
        }

        // Varias candidatas (p. ej. todas "8h"). Se decide con la pantalla congelada, nunca "todas":
        //  1) barra de progreso (qué segmento va llenándose) -> índice exacto, sin comparar imágenes
        //  2) si no se lee, parecido con las miniaturas
        val frames = tap.sigs
        val band = tap.band
        pre.execute {
            val idx = try {
                band?.let { CurrentStory.barIndex(it, items.size) } ?: -1
            } catch (e: Exception) {
                Log.e(TAG, "barra", e)
                -1
            }
            Log.d(TAG, "barra: idx=$idx de ${items.size}, candidatas por edad=${cands.size}")
            if (idx in items.indices) {
                main.post { saveAll(tap.user, listOf(items[idx])) }
                return@execute
            }

            var match: CurrentStory.Match? = null
            if (!frames.isNullOrEmpty()) {
                try {
                    // Miniaturas que aún no se habían calculado (o fallaron): se reintentan ahora.
                    for (m in cands) {
                        if (c.sigs[m.id] == null) {
                            CurrentStory.thumbSignature(m.thumb)?.let { c.sigs[m.id] = it }
                        }
                    }
                    match = CurrentStory.match(cands, c.sigs, frames)
                    Log.d(TAG, "reconocer: candidatas=${cands.size} mejor=${match?.best?.id} seguro=${match?.confident}")
                } catch (e: Exception) {
                    Log.e(TAG, "reconocer", e)
                }
            }
            val m = match
            main.post {
                if (m != null) {
                    // Siempre UNA sola story: la más parecida. Si hay duda, se avisa cuál fue.
                    if (!m.confident) toast("Guardada la ${items.indexOf(m.best) + 1} de ${items.size} (la más probable)")
                    saveAll(tap.user, listOf(m.best))
                } else {
                    // Sin ninguna pista (ni barra ni captura): único caso en que se pregunta.
                    askWhich(tap.user, items, cands)
                }
            }
        }
    }

    /** Captura de pantalla (API 30+, requiere canTakeScreenshot="true" en la config de accesibilidad). */
    private fun grabScreen(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb(null)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, pre, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "captura", e)
                    }
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "captura falló: $errorCode")
                    cb(null)
                }
            })
        } catch (e: SecurityException) {
            // Falta android:canTakeScreenshot="true" en res/xml/<config>.xml (o el servicio no se
            // volvió a activar tras añadirlo): sin captura no hay barra de progreso ni comparación.
            Log.e(
                TAG,
                "captura BLOQUEADA (SecurityException): añade android:canTakeScreenshot=\"true\" " +
                        "a la config de accesibilidad y reactiva el servicio en Ajustes",
                e
            )
            cb(null)
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot", e)
            cb(null)
        }
    }

    private fun askWhich(user: String, all: List<StoryFetcher.Media>, subset: List<StoryFetcher.Media>) {
        showPicker(user, subset, all, showAll = false) { chosen ->
            if (chosen == null) {
                done("–")
                busy = false
            } else {
                saveAll(user, chosen)
            }
        }
    }

    // ---- Selector de una sola story ------------------------------------------------------

    private fun age(t: Long): String {
        if (t <= 0) return ""
        val s = System.currentTimeMillis() / 1000 - t
        return if (s < 3600) "${maxOf(1L, s / 60)}m" else "${s / 3600}h"
    }

    private fun showPicker(
        user: String,
        items: List<StoryFetcher.Media>,
        all: List<StoryFetcher.Media> = items,
        showAll: Boolean = true,
        cb: (List<StoryFetcher.Media>?) -> Unit
    ) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(px(14), px(8), px(14), px(8))
            background = GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(Color.WHITE)
            }
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(8), px(4), px(8), px(10))
        }
        fun add(c: View) {
            row.addView(
                c,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(px(4), 0, px(4), 0) }
            )
        }

        for (m in items) {
            val n = all.indexOf(m) + 1 // numeración siempre respecto a TODAS las stories
            val a = age(m.takenAt)
            val label = "$n · " + (if (m.video) "video" else "foto") + (if (a.isNotEmpty()) "\n$a" else "")
            add(chip(label) { resolvePicker(listOf(m)) })
        }
        if (showAll) add(chip("Todas") { resolvePicker(items) })
        add(chip("✕") { resolvePicker(null) })

        val title = TextView(this).apply {
            text = if (showAll) {
                "@$user — ¿cuál story? (en el orden en que las ves)"
            } else {
                "@$user — no estoy seguro, ¿cuál es la que ves?"
            }
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(px(16), px(10), px(16), px(2))
        }
        val scroll = HorizontalScrollView(this).apply { addView(row) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(Color.argb(235, 20, 20, 20))
            }
            addView(title)
            addView(scroll)
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = px(96)
            horizontalMargin = 0.03f
        }

        closePicker()
        pickCallback = cb
        try {
            wm.addView(panel, p)
            pickerView = panel
            main.postDelayed(pickerTimeout, 30000)
            button.text = "?"
        } catch (e: Exception) {
            Log.e(TAG, "showPicker", e)
            pickCallback = null
            cb(null)
        }
    }

    private fun closePicker() {
        main.removeCallbacks(pickerTimeout)
        pickerView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "closePicker", e)
            }
        }
        pickerView = null
    }

    private fun resolvePicker(chosen: List<StoryFetcher.Media>?) {
        closePicker()
        val cb = pickCallback
        pickCallback = null
        cb?.invoke(chosen)
    }

    // ---------------------------------------------------------------------------------------

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

    // recycle() está deprecado desde API 33 (ya no hace nada), pero en versiones
    // anteriores evita acumular nodos en el pool y presión sobre el GC.
    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo?) {
        try {
            n?.recycle()
        } catch (_: Exception) {
        }
    }

    private fun dump(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 25) return
        val id = n.viewIdResourceName
        if (id != null) Log.d(TAG, "$id | text='${n.text}' | desc='${n.contentDescription}'")
        for (i in 0 until n.childCount) {
            val child = n.getChild(i)
            dump(child, depth + 1)
            recycle(child)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (pickCallback != null) resolvePicker(null) else closePicker()
        setShown(false)
        waiting = null
        prefetching = false
        prefetchingUser = null
        prefetchScheduled = null
        cache.clear()
        fetcher?.let {
            detachWeb(it.view)
            it.destroy()
        }
        fetcher = null
        scheduled = false
        busy = false
        main.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdown() // deja terminar el guardado en curso
        pre.shutdownNow()
        super.onDestroy()
    }
}