package com.example.storysaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.widget.TextView

/**
 * Botón flotante redondo con anillo de progreso:
 *  - [spinner]: anillo que gira (leyendo las stories, aún sin porcentaje).
 *  - [progress]: anillo que se llena y el porcentaje en el centro (guardando).
 *  - [reset]: sin anillo, con el texto indicado (⬇, ✔, ✖, ?).
 * Solo se usa desde el hilo principal.
 */
class RingButton(ctx: Context) : TextView(ctx) {

    private val dp = resources.displayMetrics.density
    private val stroke = 3.5f * dp

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = Color.argb(70, 255, 255, 255)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val oval = RectF()

    private var mode = MODE_OFF
    private var frac = 0f
    private var startMs = 0L

    /** Anillo girando, sin porcentaje. */
    fun spinner() {
        if (mode == MODE_SPIN) return
        mode = MODE_SPIN
        startMs = SystemClock.uptimeMillis()
        text = ""
        invalidate()
    }

    /** Anillo lleno en [f] (0..1) y porcentaje en el centro. */
    fun progress(f: Float) {
        mode = MODE_PROGRESS
        frac = f.coerceIn(0f, 1f)
        textSize = 11f
        text = "${(frac * 100).toInt()}%"
        invalidate()
    }

    /** Quita el anillo y muestra [label] (⬇, ✔, ✖, ?). */
    fun reset(label: String) {
        mode = MODE_OFF
        textSize = 22f
        text = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == MODE_OFF) return

        val pad = stroke / 2f + 2f * dp
        oval.set(pad, pad, width - pad, height - pad)
        canvas.drawOval(oval, track)

        if (mode == MODE_SPIN) {
            val t = SystemClock.uptimeMillis() - startMs
            canvas.drawArc(oval, (t * 0.36f) % 360f, 100f, false, arc)
            postInvalidateOnAnimation()
        } else {
            canvas.drawArc(oval, -90f, 360f * frac, false, arc)
        }
    }

    private companion object {
        const val MODE_OFF = 0
        const val MODE_SPIN = 1
        const val MODE_PROGRESS = 2
    }
}
