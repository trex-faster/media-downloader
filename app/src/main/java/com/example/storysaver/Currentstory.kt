package com.example.storysaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Ayudas para saber QUÉ story se está viendo ahora mismo, sin depender de IDs internos de Instagram:
 *  1. Edad que muestra el visor ("8h", "23m") -> filtra las stories por hora de publicación.
 *  2. Si quedan varias candidatas (todas "8h"), se compara la captura de pantalla con la
 *     miniatura de cada una (colores promedio en una cuadrícula) y se elige la más parecida.
 */
object CurrentStory {

    // "8h", "8 h", "23m", "23 min", "1d"... sin letras pegadas antes/después.
    private val AGE = Regex(
        "(?<![\\w.])(\\d{1,3})\\s?(s|sec|m|min|h|hr|hrs|d)(?![\\w])",
        RegexOption.IGNORE_CASE
    )

    private const val GRID = 6
    private const val SAMPLES = 8

    // Zona que se compara, en fracciones de la tarjeta de la story (9:16).
    private const val X0 = 0.08f
    private const val X1 = 0.92f
    private const val Y0 = 0.20f
    private const val Y1 = 0.55f

    class Match(val best: StoryFetcher.Media, val confident: Boolean, val ranked: List<StoryFetcher.Media>)

    /** Rango de edad en segundos [lo, hi) que corresponde al texto del visor, o null si no es una edad. */
    fun parseAge(text: String): LongRange? {
        val m = AGE.find(text) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unit = when (m.groupValues[2].lowercase()) {
            "s", "sec" -> 1L
            "m", "min" -> 60L
            "h", "hr", "hrs" -> 3600L
            else -> 86400L
        }
        return (n * unit)..((n + 1) * unit)
    }

    /** ¿La story tiene la edad indicada (con 90 s de tolerancia por redondeos y retraso)? */
    fun ageMatches(m: StoryFetcher.Media, range: LongRange, nowSec: Long): Boolean {
        if (m.takenAt <= 0) return true // sin dato: no se puede descartar
        val age = nowSec - m.takenAt
        return age >= range.first - 90 && age < range.last + 90
    }

    /** Descarga la miniatura y calcula su firma. Llamar en hilo de fondo. */
    fun thumbSignature(url: String): FloatArray? {
        if (url.isEmpty()) return null
        return try {
            val bmp = Ig.open(url).use { BitmapFactory.decodeStream(it) } ?: return null
            try {
                signature(bmp, X0, Y0, X1, Y1)
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Firma de la captura: [screen] es la pantalla completa (ARGB_8888 por software).
     * [titleTop] = coordenada Y del nombre de usuario (ancla de la tarjeta), o -1 si no se conoce.
     */
    fun screenSignature(screen: Bitmap, titleTop: Int): FloatArray {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        val cardH = w * 16f / 9f
        val cardTop = when {
            cardH >= h -> 0f
            titleTop >= 0 -> (titleTop - 0.04f * cardH).coerceIn(0f, h - cardH)
            else -> (h - cardH) * 0.28f
        }
        val effH = minOf(cardH, h)
        return signature(
            screen,
            X0, (cardTop + Y0 * effH) / h,
            X1, (cardTop + Y1 * effH) / h
        )
    }

    /** Elige la story cuya miniatura más se parece a [screen]. Llamar en hilo de fondo. */
    fun match(
        candidates: List<StoryFetcher.Media>,
        sigs: Map<String, FloatArray>,
        screen: FloatArray
    ): Match? {
        val scored = candidates.mapNotNull { m ->
            val s = sigs[m.id] ?: return@mapNotNull null
            m to distance(s, screen)
        }.sortedBy { it.second }
        // Si a alguna candidata le falta la miniatura, no se puede comparar con garantías.
        if (scored.size < candidates.size || scored.isEmpty()) return null

        val d1 = scored[0].second
        val d2 = scored.getOrNull(1)?.second ?: Float.MAX_VALUE
        val confident = d1 < 0.08f && d1 * 1.6f <= d2
        return Match(scored[0].first, confident, scored.map { it.first })
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum / a.size
    }

    /** Colores promedio (0..1) de una cuadrícula GRID x GRID dentro de la región indicada. */
    private fun signature(bmp: Bitmap, fx0: Float, fy0: Float, fx1: Float, fy1: Float): FloatArray {
        val out = FloatArray(GRID * GRID * 3)
        val bw = bmp.width
        val bh = bmp.height
        val x0 = bw * fx0
        val y0 = bh * fy0
        val cw = bw * (fx1 - fx0) / GRID
        val ch = bh * (fy1 - fy0) / GRID
        var k = 0
        for (cy in 0 until GRID) {
            for (cx in 0 until GRID) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until SAMPLES) {
                    for (i in 0 until SAMPLES) {
                        val px = (x0 + cx * cw + (i + 0.5f) / SAMPLES * cw).toInt().coerceIn(0, bw - 1)
                        val py = (y0 + cy * ch + (j + 0.5f) / SAMPLES * ch).toInt().coerceIn(0, bh - 1)
                        val c = bmp.getPixel(px, py)
                        r += Color.red(c)
                        g += Color.green(c)
                        b += Color.blue(c)
                    }
                }
                val n = SAMPLES * SAMPLES * 255f
                out[k++] = r / n
                out[k++] = g / n
                out[k++] = b / n
            }
        }
        return out
    }
}