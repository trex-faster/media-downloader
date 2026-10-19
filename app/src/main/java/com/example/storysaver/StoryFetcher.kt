package com.example.storysaver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray

/** Puente JS → Kotlin. Debe ser una clase pública: WebView la invoca por reflexión. */
class StoryBridge(
    private val onMedia: (String) -> Unit,
    private val onNote: (String) -> Unit
) {
    @JavascriptInterface
    fun media(json: String) = onMedia(json)

    @JavascriptInterface
    fun note(s: String) = onNote(s)
}

/**
 * Obtiene las URLs originales (foto/video) de las stories de un usuario SIN llamar a ningún
 * endpoint por su cuenta: carga instagram.com/stories/<usuario>/ en un WebView con la sesión
 * del cliente, deja que el propio sitio haga sus requests GraphQL y lee las RESPUESTAS
 * (un hook de fetch/XHR busca objetos con video_versions / image_versions2).
 * Así no dependemos de rutas, doc_id ni tokens, que Instagram cambia sin aviso.
 *
 * Todo se usa desde el hilo principal. El que lo use debe añadir [view] a una ventana
 * (Activity o WindowManager) mientras dure fetch(), o el WebView no renderiza.
 */
class StoryFetcher(context: Context) {

    class Media(val id: String, val url: String, val video: Boolean, val user: String)

    companion object {
        private const val TAG = "StorySaver"

        // Tras llegar la primera media del usuario pedido, esperamos esto por si llegan más lotes.
        private const val MATCH_WINDOW_MS = 4000L
        private const val TIMEOUT_MS = 30000L

        // Sin ningún carácter "dólar" a propósito: esto es un raw string de Kotlin.
        private val HOOK_JS = """
            (function () {
              if (window.__sbHook) return;
              window.__sbHook = true;
              var BAD = /AdsPool|Ads[A-Z]|Seen|Badge|Promotion|Notification/;

              function want(u) {
                return typeof u === 'string' && (u.indexOf('/graphql') >= 0 || u.indexOf('/api/') >= 0);
              }
              function fname(b) {
                try {
                  if (!b) return '';
                  var s = (typeof b === 'string') ? b : ((b instanceof URLSearchParams) ? b.toString() : '');
                  var m = /fb_api_req_friendly_name=([^&]+)/.exec(s);
                  return m ? decodeURIComponent(m[1]) : '';
                } catch (e) { return ''; }
              }
              function pick(arr) {
                var best = arr[0], bs = -1;
                for (var i = 0; i < arr.length; i++) {
                  var s = (arr[i].width || 0) * (arr[i].height || 0);
                  if (s > bs) { bs = s; best = arr[i]; }
                }
                return best;
              }
              function collect(root) {
                var out = [], seen = {};
                function walk(o, owner, depth) {
                  if (!o || typeof o !== 'object' || depth > 40) return;
                  if (Array.isArray(o)) {
                    for (var i = 0; i < o.length; i++) walk(o[i], owner, depth + 1);
                    return;
                  }
                  var user = (o.user && o.user.username) || (o.owner && o.owner.username) || owner;
                  var vv = o.video_versions;
                  var iv = o.image_versions2 && o.image_versions2.candidates;
                  var hasV = vv && vv.length, hasI = iv && iv.length;
                  if (hasV || hasI) {
                    var id = String(o.pk || o.id || '');
                    if (id && !seen[id]) {
                      seen[id] = 1;
                      out.push({
                        id: id,
                        u: hasV ? pick(vv).url : pick(iv).url,
                        v: hasV ? 1 : 0,
                        user: user || ''
                      });
                    }
                    return;
                  }
                  for (var k in o) {
                    if (Object.prototype.hasOwnProperty.call(o, k)) walk(o[k], user, depth + 1);
                  }
                }
                walk(root, '', 0);
                return out;
              }
              function handle(text, fn) {
                try {
                  if (!text || BAD.test(fn)) return;
                  if (text.indexOf('video_versions') < 0 && text.indexOf('image_versions2') < 0) return;
                  var items = collect(JSON.parse(text.replace(/^for \(;;\);/, '')));
                  StoryBridge.note('respuesta ' + fn + ': ' + items.length + ' items con media');
                  if (items.length) StoryBridge.media(JSON.stringify(items));
                } catch (e) {
                  try { StoryBridge.note('hook error: ' + e); } catch (x) {}
                }
              }

              // En una carga directa de /stories/usuario/ Instagram puede traer los datos ya
              // incrustados en el HTML (sin request de red): los leemos de los <script> JSON.
              window.__sbScan = function () {
                try {
                  var ss = document.querySelectorAll('script[type="application/json"]');
                  for (var i = 0; i < ss.length; i++) {
                    var t = ss[i].textContent;
                    if (t && (t.indexOf('video_versions') >= 0 || t.indexOf('image_versions2') >= 0)) {
                      handle(t, 'inline');
                    }
                  }
                } catch (e) {}
              };

              var of = window.fetch;
              if (of) {
                window.fetch = function (input, init) {
                  var url = null, fn = '';
                  try {
                    url = (typeof input === 'string') ? input : (input && input.url);
                    fn = fname(init && init.body);
                  } catch (e) {}
                  var p = of.apply(this, arguments);
                  try {
                    if (want(url)) {
                      p.then(function (r) {
                        try { r.clone().text().then(function (t) { handle(t, fn); }, function () {}); } catch (e) {}
                      }, function () {});
                    }
                  } catch (e) {}
                  return p;
                };
              }

              var xo = XMLHttpRequest.prototype.open;
              var xs = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (m, u) {
                this.__sbu = String(u);
                return xo.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function (b) {
                try {
                  if (want(this.__sbu)) {
                    var fn = fname(b), self = this;
                    this.addEventListener('load', function () {
                      try {
                        if (self.responseType === '' || self.responseType === 'text') handle(self.responseText, fn);
                      } catch (e) {}
                    });
                  }
                } catch (e) {}
                return xs.apply(this, arguments);
              };
            })();
        """.trimIndent()

        // Pulsa botones tipo "Ver historia" si Instagram muestra una pantalla intermedia.
        private val CLICK_JS = """
            (function () {
              var bs = document.querySelectorAll('button, div[role=button]');
              for (var i = 0; i < bs.length; i++) {
                var t = (bs[i].innerText || '').trim().toLowerCase();
                if (t.length > 0 && t.length < 40 &&
                    /^(view story|view stories|ver historia|ver historias)/.test(t)) {
                  bs[i].click();
                  return 'click: ' + t;
                }
              }
              return '';
            })();
        """.trimIndent()

        // Último recurso: lee la foto/video visible en el DOM (los videos suelen ser blob: y no sirven).
        private val SCRAPE_JS = """
            (function () {
              var out = [], seen = {};
              var vs = document.querySelectorAll('video');
              for (var i = 0; i < vs.length; i++) {
                var s = vs[i].currentSrc || vs[i].src || '';
                if (/^https?:/.test(s) && !seen[s]) {
                  seen[s] = 1;
                  out.push({ id: 'dom' + s.split('?')[0].split('/').pop(), u: s, v: 1, user: '' });
                }
              }
              var is = document.querySelectorAll('img');
              for (var j = 0; j < is.length; j++) {
                var im = is[j], u = im.currentSrc || im.src || '';
                if (/^https?:/.test(u) && im.naturalWidth >= 500 && im.naturalHeight >= 500 && !seen[u]) {
                  seen[u] = 1;
                  out.push({ id: 'dom' + u.split('?')[0].split('/').pop(), u: u, v: 0, user: '' });
                }
              }
              if (out.length) StoryBridge.media(JSON.stringify(out));
              else StoryBridge.note('scrape: no hay media en el DOM');
            })();
        """.trimIndent()
    }

    private val main = Handler(Looper.getMainLooper())

    // Solo hilo principal.
    private val found = LinkedHashMap<String, Media>()   // media del usuario pedido (o sin usuario)
    private val others = LinkedHashMap<String, Media>()  // media de otros usuarios (respaldo)
    private var target = ""
    private var active = false
    private var finishScheduled = false
    private var ticks = 0
    private var clicks = 0
    private var callback: ((List<Media>, String?) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            ticks++
            if (found.isEmpty()) {
                if (ticks == 3 || ticks == 6 || ticks == 10) {
                    view.evaluateJavascript("window.__sbScan && window.__sbScan();", null)
                }
                if (ticks >= 2 && clicks < 12) {
                    clicks++
                    view.evaluateJavascript(CLICK_JS) { r ->
                        if (r != null && r != "\"\"" && r != "null") Log.d(TAG, "auto-click: $r")
                    }
                }
                if ((ticks == 12 || ticks == 20) && others.isEmpty()) {
                    view.evaluateJavascript(SCRAPE_JS, null)
                }
            }
            main.postDelayed(this, 1000)
        }
    }
    private val timeout = Runnable { finish(null) }
    private val finishNow = Runnable { finish(null) }

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = Ig.UA
        settings.mediaPlaybackRequiresUserGesture = true // que la story no se reproduzca con sonido

        addJavascriptInterface(
            StoryBridge(
                { json -> main.post { onMedia(json) } },
                { s -> Log.d(TAG, "js: $s") }
            ),
            "StoryBridge"
        )

        // Lo ideal: inyectar el hook antes de cualquier script de la página.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, HOOK_JS, setOf("https://www.instagram.com"))
        }

        // Respaldo (idempotente): por si el WebView no soporta document-start-script.
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                view?.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(HOOK_JS, null)
            }
        }
    }

    /** [done] se llama en el hilo principal: (media, null) si fue bien, o (lista vacía, mensaje). */
    fun fetch(username: String, done: (List<Media>, String?) -> Unit) {
        if (active) {
            done(emptyList(), "Ya hay una descarga en curso")
            return
        }
        active = true
        finishScheduled = false
        target = username
        found.clear()
        others.clear()
        ticks = 0
        clicks = 0
        callback = done

        view.loadUrl("https://www.instagram.com/stories/$username/")
        main.postDelayed(tick, 1000)
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun onMedia(json: String) {
        if (!active) return
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("u")
            if (id.isEmpty() || url.isEmpty()) continue
            val m = Media(id, url, o.optInt("v") == 1, o.optString("user"))
            if (m.user.isEmpty() || m.user.equals(target, ignoreCase = true)) found[id] = m else others[id] = m
        }
        if (found.isNotEmpty() && !finishScheduled) {
            finishScheduled = true
            main.postDelayed(finishNow, MATCH_WINDOW_MS)
        }
    }

    private fun finish(error: String?) {
        if (!active) return
        active = false
        main.removeCallbacks(tick)
        main.removeCallbacks(timeout)
        main.removeCallbacks(finishNow)
        view.loadUrl("about:blank") // corta cualquier reproducción y más requests

        var list = found.values.toList()
        var err = error
        if (list.isEmpty() && others.isNotEmpty()) {
            // Respaldo: llegó media pero con otro nombre de usuario (p. ej. distinto formato).
            // Tomamos el primer usuario que llegó, que es el de la página abierta.
            val first = others.values.first().user
            list = others.values.filter { it.user == first }
            Log.w(TAG, "Sin coincidencia para @$target; usando @$first")
        }
        if (list.isEmpty() && err == null) {
            err = "No llegaron stories de @$target (¿sin stories activas, cuenta privada que no sigues, o sesión vencida?)"
        }
        val cb = callback
        callback = null
        cb?.invoke(list, err)
    }

    /** Llamar en el hilo principal, con [view] ya fuera de cualquier ventana. */
    fun destroy() {
        active = false
        main.removeCallbacks(tick)
        main.removeCallbacks(timeout)
        main.removeCallbacks(finishNow)
        view.destroy()
    }
}
