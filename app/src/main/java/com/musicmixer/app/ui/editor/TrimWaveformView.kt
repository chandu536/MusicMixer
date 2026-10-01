package com.musicmixer.app.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Waveform view with draggable trim handles and a draggable playhead.
 *
 * Touch priority (first match wins):
 *   1. Playhead  — highest; grab anywhere within PLAYHEAD_SLOP of the line
 *   2. Trim-end handle  — thumb rect hit area
 *   3. Trim-start handle — thumb rect hit area
 *
 * Handles respond when the finger touches anywhere inside the thumb rectangle
 * (not just near the thin bar edge).  Playhead is drawn on top of handles.
 */
class TrimWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var peaks: FloatArray = FloatArray(0)
    private var trackColor: Int = Color.parseColor("#6C63FF")

    // ── Trim ──────────────────────────────────────────────────────────────────
    var trimStartRatio: Float = 0f; private set
    var trimEndRatio:   Float = 1f; private set
    var onTrimChanged: ((Float, Float) -> Unit)? = null

    // ── Playhead ──────────────────────────────────────────────────────────────
    /** Ratio 0..1 within the FULL clip duration. */
    private var playRatio: Float = 0f
    private var playheadVisible = false
    /** Fired once on finger-up after dragging the playhead. */
    var onScrubChanged: ((ratio: Float) -> Unit)? = null

    // ── Paints ────────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#161728"); style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#AA000000")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val handleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val playLinePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val playDiamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }

    private val dp get() = resources.displayMetrics.density

    private val BAR_W   get() = 4f  * dp   // handle thin bar width px
    private val THUMB_W get() = 28f * dp   // thumb hit-area width px (wider = easier to grab)
    private val THUMB_H get() = 44f * dp   // thumb hit-area height px

    // Touch slop for playhead line (px either side)
    private val PLAYHEAD_SLOP get() = 28f * dp

    private enum class Dragging { NONE, TRIM_START, TRIM_END, PLAYHEAD }
    private var dragging = Dragging.NONE

    // ── Public API ────────────────────────────────────────────────────────────

    fun bind(peaks: FloatArray, color: Int, startRatio: Float, endRatio: Float) {
        this.peaks      = peaks
        this.trackColor = color
        trimStartRatio  = startRatio.coerceIn(0f, 1f)
        trimEndRatio    = endRatio.coerceIn(0f, 1f)
        // Clamp playhead into new trim region; always keep it visible
        playRatio       = playRatio.coerceIn(trimStartRatio, trimEndRatio)
        playheadVisible = true
        invalidate()
    }

    fun setPlayPosition(ratio: Float) {
        playRatio       = ratio.coerceIn(trimStartRatio, trimEndRatio)
        playheadVisible = true
        invalidate()
    }

    /**
     * Reset playhead to trim-start WITHOUT resetting to 0.
     * Called by stopPreview() — keeps the line where the user scrubbed to
     * only if the user hasn't dragged it themselves; otherwise just shows it.
     */
    fun clearPlayhead() {
        playRatio       = trimStartRatio
        playheadVisible = true
        invalidate()
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val h = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 }
            ?: (72 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (peaks.isEmpty()) return

        val mid  = h / 2f
        val barW = w / peaks.size

        // ── Waveform ──────────────────────────────────────────────────────────
        barPaint.color = trackColor
        for (i in peaks.indices) {
            val x    = i * barW
            val barH = (peaks[i] * (mid - 4f)).coerceAtLeast(2f)
            canvas.drawRect(x + 0.3f, mid - barH, x + barW - 0.3f, mid + barH, barPaint)
        }

        val startPx = trimStartRatio * w
        val endPx   = trimEndRatio   * w

        // ── Dim outside trim ──────────────────────────────────────────────────
        canvas.drawRect(0f, 0f, startPx, h, dimPaint)
        canvas.drawRect(endPx, 0f, w, h, dimPaint)

        // ── Border ────────────────────────────────────────────────────────────
        borderPaint.color = trackColor
        canvas.drawRect(startPx, 1f, endPx, h - 1f, borderPaint)

        // ── Trim handles (drawn before playhead so playhead is on top) ─────────
        drawHandle(canvas, startPx, h, mid, Color.parseColor("#00D4AA"), isStart = true)
        drawHandle(canvas, endPx,   h, mid, Color.parseColor("#FF6B9D"), isStart = false)

        // ── Playhead (drawn last → visually on top of handles) ────────────────
        if (playheadVisible) {
            val phX = playRatio * w
            playLinePaint.strokeWidth = 3f * dp
            canvas.drawLine(phX, 0f, phX, h, playLinePaint)
            // Diamond at top
            val r = 6f * dp
            val path = Path().apply {
                moveTo(phX,     2f)
                lineTo(phX + r, r + 2f)
                lineTo(phX,     r * 2f + 2f)
                lineTo(phX - r, r + 2f)
                close()
            }
            canvas.drawPath(path, playDiamondPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, h: Float, mid: Float, color: Int, isStart: Boolean) {
        handleBarPaint.color = color
        val halfBar = BAR_W / 2f
        canvas.drawRoundRect(RectF(cx - halfBar, 0f, cx + halfBar, h),
            halfBar, halfBar, handleBarPaint)

        thumbPaint.color = color
        val tw = THUMB_W / 2f
        val th = THUMB_H / 2f
        val r  = 6f * dp
        canvas.drawRoundRect(RectF(cx - tw, mid - th, cx + tw, mid + th), r, r, thumbPaint)

        // Arrow inside thumb
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setColor(Color.WHITE); style = Paint.Style.FILL
        }
        val aw   = 5f * dp
        val path = Path()
        if (isStart) {
            path.moveTo(cx + aw, mid - aw * 1.4f)
            path.lineTo(cx - aw, mid)
            path.lineTo(cx + aw, mid + aw * 1.4f)
        } else {
            path.moveTo(cx - aw, mid - aw * 1.4f)
            path.lineTo(cx + aw, mid)
            path.lineTo(cx - aw, mid + aw * 1.4f)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    // ── Touch helpers ─────────────────────────────────────────────────────────

    /** Returns true if [x],[y] is inside the thumb rectangle centred on [cx]. */
    private fun inThumb(x: Float, y: Float, cx: Float): Boolean {
        val h   = height.toFloat()
        val mid = h / 2f
        val tw  = THUMB_W / 2f
        val th  = THUMB_H / 2f
        return x >= cx - tw && x <= cx + tw && y >= mid - th && y <= mid + th
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val w = width.toFloat()
        if (w == 0f) return false

        val startPx   = trimStartRatio * w
        val endPx     = trimEndRatio   * w
        val playheadX = playRatio * w

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dPlayhead = if (playheadVisible) abs(x - playheadX) else Float.MAX_VALUE

                dragging = when {
                    // 1. Playhead has highest priority — grab by x-proximity
                    dPlayhead < PLAYHEAD_SLOP -> Dragging.PLAYHEAD

                    // 2. Trim handles — grab by thumb rect hit-test
                    inThumb(x, y, endPx)   -> Dragging.TRIM_END
                    inThumb(x, y, startPx) -> Dragging.TRIM_START

                    // 3. Fallback: x-proximity to handle bars (for when thumb is off-screen edge)
                    abs(x - endPx)   < THUMB_W / 2f -> Dragging.TRIM_END
                    abs(x - startPx) < THUMB_W / 2f -> Dragging.TRIM_START

                    else -> Dragging.NONE
                }
                if (dragging != Dragging.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                } else false
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging == Dragging.NONE) return false
                val ratio = (x / w).coerceIn(0f, 1f)
                when (dragging) {
                    Dragging.TRIM_START -> {
                        trimStartRatio = min(ratio, trimEndRatio - 0.01f)
                        if (playRatio < trimStartRatio) playRatio = trimStartRatio
                        onTrimChanged?.invoke(trimStartRatio, trimEndRatio)
                    }
                    Dragging.TRIM_END -> {
                        trimEndRatio = max(ratio, trimStartRatio + 0.01f)
                        if (playRatio > trimEndRatio) playRatio = trimEndRatio
                        onTrimChanged?.invoke(trimStartRatio, trimEndRatio)
                    }
                    Dragging.PLAYHEAD -> {
                        // Move playhead freely; do NOT call clearPlayhead/stopPreview here
                        playRatio = ratio.coerceIn(trimStartRatio, trimEndRatio)
                    }
                    Dragging.NONE -> {}
                }
                invalidate()
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging == Dragging.PLAYHEAD) {
                    // Fire scrub callback — EditorActivity decides whether to seek or just update
                    onScrubChanged?.invoke(playRatio)
                }
                dragging = Dragging.NONE
                invalidate()
                true
            }

            else -> false
        }
    }
}
