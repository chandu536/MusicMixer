package com.musicmixer.app.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.musicmixer.app.model.AudioTrack

/**
 * A compact waveform thumbnail used inside each track card.
 * Draws peak bars for the full waveform, then overlays a
 * semi-transparent highlight for the trim region.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var peaks: FloatArray = FloatArray(0)
    private var trimStartRatio: Float = 0f
    private var trimEndRatio: Float = 1f
    private var trackColor: Int = Color.parseColor("#6C63FF")

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#80000000")
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun bind(track: AudioTrack, color: Int) {
        peaks = track.waveformPeaks
        trimStartRatio = if (track.durationMs > 0) (track.trimStartMs.toFloat() / track.durationMs) else 0f
        trimEndRatio = if (track.durationMs > 0) (track.trimEndMs.toFloat() / track.durationMs) else 1f
        trackColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (peaks.isEmpty() || w == 0f || h == 0f) return

        val mid = h / 2f
        val barCount = peaks.size
        val barW = w / barCount

        // Background
        bgPaint.color = Color.parseColor("#0D0E1A")
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Bars
        barPaint.color = trackColor
        for (i in peaks.indices) {
            val x = i * barW
            val barH = (peaks[i] * (mid - 2f)).coerceAtLeast(1f)
            canvas.drawRect(x + 0.5f, mid - barH, x + barW - 0.5f, mid + barH, barPaint)
        }

        // Dim non-selected regions
        val trimStartPx = trimStartRatio * w
        val trimEndPx = trimEndRatio * w
        if (trimStartPx > 0) canvas.drawRect(0f, 0f, trimStartPx, h, dimPaint)
        if (trimEndPx < w) canvas.drawRect(trimEndPx, 0f, w, h, dimPaint)

        // Active region border
        activePaint.color = trackColor
        canvas.drawRect(trimStartPx, 1f, trimEndPx, h - 1f, activePaint)
    }
}
