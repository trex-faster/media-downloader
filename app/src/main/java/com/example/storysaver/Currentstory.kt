package com.example.storysaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Identifica QUÉ story se está viendo ahora mismo, sin depender de IDs internos de Instagram:
 *  1. Edad que muestra el visor ("8h", "23m") -> filtra las stories por hora de publicación.
 *  2. Si quedan varias candidatas, se compara la captura (tomada en el instante del toque)
 *     con la miniatura de cada una. La comparación es robusta a:
 *       - distinta posición/escala de la tarjeta en pantalla (se prueban varias alineaciones),
 *       - stickers, texto y marcos encima (se descartan las celdas que más difieren),
 *       - frames de video distintos a la miniatura.
 */
object CurrentStory {

    // "8h", "8 h", "23m", "23 min", "1d"... sin letras pegadas antes/después.
    private val AGE = Regex(
        "(?<![\\w.])(\\d{1,3})\\s?(s|sec|m|min|h|hr|hrs|d)(?![\\w])",
        RegexOption.IGNORE_CASE
    )
    private val NOW = Regex("^(now|ahora)$", RegexOption.IGNORE_CASE)

    private const val GRID = 8
    private const val SAMPLES = 6

    // Zona que se compara, en fracciones de la story (9:16). Margen lateral amplio para que
    // también valga cuando Instagram recorta los lados (pantallas muy altas).
    private const val X0 = 0.14f
    private const val X1 = 0.86f
    private const val Y0 = 0.20f
    private const val Y1 = 0.55f

    // Fracción de celdas (las más parecidas) que cuenta en la distancia: ignora stickers/texto.
    private const val KEEP = 0.7f

    // Umbrales de confianza (ajustables; el Log "reconocer" muestra d1/d2 para calibrar).
    private const val RATIO_SURE = 1.25f      // d2 al menos 25 % peor que d1
    private const val RATIO_GOOD = 1.10f      // ...o 10 % si el parecido absoluto es muy bueno
    private const val ABS_GOOD = 0.008f

    class Match(
        val best: StoryFetcher.Media,
        val confident: Boolean,
        val ranked: List<StoryFetcher.Media>
    )

    /** Rango de edad en segundos [lo, hi) que corresponde al texto del visor, o null si no es una edad. */
    fun parseAge(text: String): LongRange? {
        val t = text.trim()
        if (NOW.matches(t)) return 0L..60L
        val m = AGE.find(t) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = when (m.groupValues[2].lowercase()) {
            "s", "sec" -> 1L
            "m", "min" -> 60L
            "h", "hr", "hrs" -> 3600L
            else -> 86400L
        }
        // Instagram redondea a su manera ("4h" para una story de 3 h 20 min): se acepta de n-1 a n+1.
        return (maxOf(0L, n - 1) * unit)..((n + 1) * unit)
    }

    /** ¿La story tiene la edad indicada (con 120 s de tolerancia por redondeos, retraso y reloj)? */
    fun ageMatches(m: StoryFetcher.Media, range: LongRange, nowSec: Long): Boolean {
        if (m.takenAt <= 0) return true // sin dato: no se puede descartar
        val age = nowSec - m.takenAt
        return age >= range.first - 120 && age < range.last + 120
    }

    // ---- Barra de progreso del visor (segmentos de arriba) ----------------------------------
    // Hay un segmento por story: los anteriores a la actual están llenos (blanco), la actual
    // se va llenando y las siguientes están vacías. Es la señal más fiable: no depende de la
    // hora ni de que las stories se parezcan entre sí.

    /** Brillo (0..255) de las filas de pantalla donde puede estar la barra. */
    class Band(val w: Int, val rows: Array<IntArray>)

    private val BAR_MARGINS = floatArrayOf(0.012f, 0.017f, 0.025f, 0.035f)
    private val BAR_GAPS = floatArrayOf(0.005f, 0.008f, 0.012f)
    private const val BAR_MIN_SCORE = 20f

    /** Copia el brillo de la franja superior de la captura. Llamar en hilo de fondo. */
    fun readBand(screen: Bitmap, titleTop: Int): Band {
        val w = screen.width
        val h = screen.height
        val a: Int
        val b: Int
        if (titleTop >= 0) {
            a = (titleTop - 0.05f * h).toInt()
            b = (titleTop + 0.006f * h).toInt()
        } else {
            a = 0
            b = (0.12f * h).toInt()
        }
        val y0 = a.coerceIn(0, h - 1)
        val y1 = b.coerceIn(y0 + 1, h)
        val px = IntArray(w)
        val rows = Array(y1 - y0) { i ->
            screen.getPixels(px, 0, w, 0, y0 + i, w, 1)
            IntArray(w) { x ->
                val c = px[x]
                (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }
        }
        return Band(w, rows)
    }

    /**
     * Índice (0 = la más antigua) de la story que se ve, leído de la barra de progreso,
     * o -1 si no se reconoce con seguridad (entonces se usan las otras pistas).
     * [n] = nº de stories del usuario: la barra debe tener exactamente ese nº de segmentos.
     */
    fun barIndex(band: Band, n: Int): Int {
        if (n < 2 || n > 12) return -1
        var bestScore = BAR_MIN_SCORE
        var bestIdx = -1
        for (row in band.rows) {
            for (m in BAR_MARGINS) {
                for (g in BAR_GAPS) {
                    val r = evalBarRow(row, band.w, n, m, g) ?: continue
                    if (r.first > bestScore) {
                        bestScore = r.first
                        bestIdx = r.second
                    }
                }
            }
        }
        return bestIdx
    }

    /** (puntuación, índice) si esta fila parece la barra con [n] segmentos; null si no. */
    private fun evalBarRow(row: IntArray, w: Int, n: Int, margin: Float, gap: Float): Pair<Float, Int>? {
        val left = margin * w
        val gapPx = gap * w
        val segW = (w - 2 * left - (n - 1) * gapPx) / n
        if (segW < 12f) return null

        val from = IntArray(n)
        val to = IntArray(n)
        for (i in 0 until n) {
            val s0 = left + i * (segW + gapPx)
            from[i] = (s0 + 0.06f * segW).toInt().coerceIn(0, w - 2)
            to[i] = (s0 + 0.94f * segW).toInt().coerceIn(from[i] + 1, w)
        }

        // Brillo medio dentro de los segmentos, "rugosidad" (la barra es lisa) y histograma.
        val hist = IntArray(256)
        var sum = 0L
        var cnt = 0
        var jump = 0L
        var jumpCnt = 0
        for (i in 0 until n) {
            for (x in from[i] until to[i]) {
                val v = row[x]
                hist[v.coerceIn(0, 255)]++
                sum += v
                cnt++
                if (x > from[i]) {
                    jump += kotlin.math.abs(v - row[x - 1])
                    jumpCnt++
                }
            }
        }
        if (cnt == 0 || jumpCnt == 0) return null
        val rough = jump.toFloat() / jumpCnt
        if (rough > 14f) return null

        // Los huecos entre segmentos deben ser más oscuros que los segmentos.
        var gapSum = 0f
        for (i in 0 until n - 1) {
            val c = (left + i * (segW + gapPx) + segW + gapPx / 2f).toInt()
            val r = maxOf(3, gapPx.toInt())
            var mn = 255
            for (x in (c - r).coerceAtLeast(0)..(c + r).coerceAtMost(w - 1)) if (row[x] < mn) mn = row[x]
            gapSum += mn
        }
        val dip = sum.toFloat() / cnt - gapSum / (n - 1)
        if (dip < 25f) return null

        // Dos niveles: relleno (blanco) y pista (gris).
        fun q(p: Float): Int {
            val target = (cnt * p).toInt()
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }
        val lo = q(0.05f)
        val hi = q(0.95f)
        val hasFill = hi - lo >= 20
        val t = (lo + hi) / 2

        var idx = 0
        val full = BooleanArray(n)
        for (i in 0 until n) {
            var fill = 0
            if (hasFill) for (x in from[i] until to[i]) if (row[x] >= t) fill++
            val len = to[i] - from[i]
            if (fill >= 3) idx = i       // la última con algo de relleno = la que se está viendo
            full[i] = fill >= 0.85f * len
        }
        // Coherencia: todas las anteriores llenas; ninguna posterior con relleno.
        for (i in 0 until idx) if (!full[i]) return null
        for (i in idx + 1 until n) {
            var fill = 0
            if (hasFill) for (x in from[i] until to[i]) if (row[x] >= t) fill++
            if (fill >= 3) return null
        }
        return Pair(dip - 2f * rough, idx)
    }

    /** Descarga la miniatura y calcula su firma. Llamar en hilo de fondo. */
    fun thumbSignature(url: String): FloatArray? {
        if (url.isEmpty()) return null
        return try {
            val bmp = Ig.open(url).use { BitmapFactory.decodeStream(it) } ?: return null
            try {
                val w = bmp.width.toFloat()
                val h = bmp.height.toFloat()
                signature(bmp, w * X0, h * Y0, w * X1, h * Y1)
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Firmas de la captura, una por cada posible colocación de la story en pantalla.
     * [screen] es la pantalla completa (ARGB_8888 por software).
     * [titleTop] = coordenada Y del nombre de usuario (ancla de la tarjeta), o -1 si no se conoce.
     */
    fun screenSignatures(screen: Bitmap, titleTop: Int): List<FloatArray> {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        val cardH = w * 16f / 9f

        // Cada frame = (left, top, ancho, alto) de la story 9:16 tal como se dibuja en pantalla.
        val frames = ArrayList<FloatArray>()
        val seenTops = HashSet<Int>()

        fun addFit(top: Float) {
            if (seenTops.add(top.toInt() / 8)) frames.add(floatArrayOf(0f, top, w, cardH))
        }
        if (cardH <= h) {
            if (titleTop >= 0) addFit((titleTop - 0.04f * cardH).coerceIn(0f, h - cardH))
            addFit((h - cardH) * 0.28f)
            addFit(0f)
            addFit((h - cardH) / 2f)
        }
        // Cubriendo toda la pantalla (recorte centrado).
        val scale = maxOf(w / 9f, h / 16f)
        val cw = 9f * scale
        val ch = 16f * scale
        frames.add(floatArrayOf((w - cw) / 2f, (h - ch) / 2f, cw, ch))
        // Ajustada al alto (bandas a los lados).
        val mw = h * 9f / 16f
        if (mw < w) frames.add(floatArrayOf((w - mw) / 2f, 0f, mw, h))

        return frames.map { f ->
            signature(
                screen,
                f[0] + X0 * f[2], f[1] + Y0 * f[3],
                f[0] + X1 * f[2], f[1] + Y1 * f[3]
            )
        }
    }

    /**
     * Ordena las candidatas por parecido con la captura (mejor alineación de cada una).
     * [confident] solo si TODAS tienen firma y la primera gana con margen claro.
     * Llamar en hilo de fondo.
     */
    fun match(
        candidates: List<StoryFetcher.Media>,
        sigs: Map<String, FloatArray>,
        screen: List<FloatArray>
    ): Match? {
        if (screen.isEmpty()) return null
        val scored = candidates.mapNotNull { m ->
            val s = sigs[m.id] ?: return@mapNotNull null
            var best = Float.MAX_VALUE
            for (f in screen) {
                val d = distance(s, f)
                if (d < best) best = d
            }
            m to best
        }.sortedBy { it.second }
        if (scored.isEmpty()) return null
        val complete = scored.size == candidates.size

        val d1 = scored[0].second
        val d2 = scored.getOrNull(1)?.second ?: Float.MAX_VALUE
        val sure = d2 == Float.MAX_VALUE ||
                d1 * RATIO_SURE <= d2 ||
                (d1 < ABS_GOOD && d1 * RATIO_GOOD <= d2)
        android.util.Log.d("StorySaver", "reconocer: d1=$d1 d2=$d2 completas=$complete")
        return Match(scored[0].first, complete && sure, scored.map { it.first })
    }

    /** Error medio de las celdas más parecidas (descarta las que tapan stickers/texto). */
    private fun distance(a: FloatArray, b: FloatArray): Float {
        val n = a.size / 3
        val e = FloatArray(n)
        for (c in 0 until n) {
            var s = 0f
            for (k in 0 until 3) {
                val d = a[c * 3 + k] - b[c * 3 + k]
                s += d * d
            }
            e[c] = s
        }
        e.sort()
        val keep = (n * KEEP).toInt().coerceAtLeast(1)
        var sum = 0f
        for (i in 0 until keep) sum += e[i]
        return sum / keep
    }

    /** Colores promedio (0..1) de una cuadrícula GRID x GRID dentro del rectángulo en píxeles. */
    private fun signature(bmp: Bitmap, rx0: Float, ry0: Float, rx1: Float, ry1: Float): FloatArray {
        val out = FloatArray(GRID * GRID * 3)
        val bw = bmp.width
        val bh = bmp.height
        val cw = (rx1 - rx0) / GRID
        val ch = (ry1 - ry0) / GRID
        val n = SAMPLES * SAMPLES * 255f
        var k = 0
        for (cy in 0 until GRID) {
            for (cx in 0 until GRID) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until SAMPLES) {
                    val py = (ry0 + cy * ch + (j + 0.5f) / SAMPLES * ch).toInt().coerceIn(0, bh - 1)
                    for (i in 0 until SAMPLES) {
                        val px = (rx0 + cx * cw + (i + 0.5f) / SAMPLES * cw).toInt().coerceIn(0, bw - 1)
                        val c = bmp.getPixel(px, py)
                        r += Color.red(c)
                        g += Color.green(c)
                        b += Color.blue(c)
                    }
                }
                out[k++] = r / n
                out[k++] = g / n
                out[k++] = b / n
            }
        }
        return out
    }
}