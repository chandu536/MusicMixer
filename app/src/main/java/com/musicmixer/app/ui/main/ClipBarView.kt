package com.musicmixer.app.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Read-only trim bar for the landing page track card.
 *
 * Draws:
 *  - A dark background spanning full width
 *  - A coloured filled rectangle from [startRatio] to [endRatio]
 *  - Dimmed regions outside the selection
 *
 * No touch / drag handling — editing only happens in EditorActivity.
 */
class ClipBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var trackColor: Int = Color.parseColor("#6C63FF")
    private var startRatio: Float = 0f
    private var endRatio: Float = 1f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E2E")
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    private val dp get() = resources.displayMetrics.density
    private val CORNER get() = 4f * dp

    fun bind(color: Int, startRatio: Float, endRatio: Float) {
        this.trackColor = color
        this.startRatio = startRatio.coerceIn(0f, 1f)
        this.endRatio   = endRatio.coerceIn(0f, 1f)
        fillPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Background
        canvas.drawRoundRect(RectF(0f, 0f, w, h), CORNER, CORNER, bgPaint)

        val startPx = startRatio * w
        val endPx   = endRatio   * w

        // Dim left of selection
        if (startPx > 0f)
            canvas.drawRoundRect(RectF(0f, 0f, startPx, h), CORNER, CORNER, dimPaint)

        // Coloured selection band
        canvas.drawRoundRect(RectF(startPx, 0f, endPx, h), CORNER, CORNER, fillPaint)

        // Dim right of selection
        if (endPx < w)
            canvas.drawRoundRect(RectF(endPx, 0f, w, h), CORNER, CORNER, dimPaint)
    }
}
