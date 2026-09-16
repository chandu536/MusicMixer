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
 * Waveform view with:
 *  - Slim trim handles (4dp vertical bar + small grip nub) at start/end of selected region
 *  - Draggable white playhead line that scrolls during playback
 *
 * Handle design: a thin coloured vertical bar spanning full height, with a small
 * rounded rect "thumb" in the centre — takes almost no horizontal space so the
 * waveform remains fully visible even on short clips.
 *
 * Touch priority (closest wins):
 *   1. Trim start handle  (within HANDLE_SLOP of bar centre-x)
 *   2. Trim end handle    (within HANDLE_SLOP of bar centre-x)
 *   3. Playhead           (within PLAYHEAD_SLOP of line x, only when visible)
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
    // Slim handle bar
    private val handleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Thumb grip rect on the handle
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Playhead line
    private val playLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    // Playhead top diamond
    private val playDiamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }

    private val dp get() = resources.displayMetrics.density

    // Slim handle: bar is 4dp wide; thumb is 10dp wide × 20dp tall centred on bar
    private val BAR_W    get() = 4f  * dp   // handle bar width px
    private val THUMB_W  get() = 12f * dp   // grip thumb width px
    private val THUMB_H  get() = 22f * dp   // grip thumb height px

    private val HANDLE_SLOP  = 40f  // px touch radius for trim handles
    private val PLAYHEAD_SLOP = 36f // px touch radius for playhead

    private enum class Dragging { NONE, TRIM_START, TRIM_END, PLAYHEAD }
    private var dragging = Dragging.NONE

    // ── Public API ────────────────────────────────────────────────────────────

    fun bind(peaks: FloatArray, color: Int, startRatio: Float, endRatio: Float) {
        this.peaks = peaks; this.trackColor = color
        trimStartRatio = startRatio.coerceIn(0f, 1f)
        trimEndRatio   = endRatio.coerceIn(0f, 1f)
        invalidate()
    }

    fun setPlayPosition(ratio: Float) {
        playRatio = ratio.coerceIn(trimStartRatio, trimEndRatio)
        playheadVisible = true
        invalidate()
    }

    fun clearPlayhead() {
        playheadVisible = false
        playRatio = trimStartRatio
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

        // ── Waveform bars ──────────────────────────────────────────────────────
        barPaint.color = trackColor
        for (i in peaks.indices) {
            val x    = i * barW
            val barH = (peaks[i] * (mid - 4f)).coerceAtLeast(2f)
            canvas.drawRect(x + 0.3f, mid - barH, x + barW - 0.3f, mid + barH, barPaint)
        }

        val startPx = trimStartRatio * w
        val endPx   = trimEndRatio   * w

        // ── Dim outside trim region ────────────────────────────────────────────
        canvas.drawRect(0f, 0f, startPx, h, dimPaint)
        canvas.drawRect(endPx, 0f, w, h, dimPaint)

        // ── Active region border ───────────────────────────────────────────────
        borderPaint.color = trackColor
        canvas.drawRect(startPx, 1f, endPx, h - 1f, borderPaint)

        // ── Playhead (drawn below trim handles so handles stay on top) ──────────
        if (playheadVisible) {
            val phX = playRatio * w
            playLinePaint.strokeWidth = 2.5f * dp
            canvas.drawLine(phX, 0f, phX, h, playLinePaint)
            // Small diamond at top
            val r = 5f * dp
            val path = Path().apply {
                moveTo(phX,     2f)
                lineTo(phX + r, r + 2f)
                lineTo(phX,     r * 2f + 2f)
                lineTo(phX - r, r + 2f)
                close()
            }
            canvas.drawPath(path, playDiamondPaint)
        }

        // ── Start handle (green slim bar + thumb) ──────────────────────────────
        drawHandle(canvas, startPx, h, mid, Color.parseColor("#00D4AA"), isStart = true)

        // ── End handle (pink slim bar + thumb) ────────────────────────────────
        drawHandle(canvas, endPx, h, mid, Color.parseColor("#FF6B9D"), isStart = false)
    }

    /**
     * Draw a slim vertical handle bar centred on [cx].
     * The bar is [BAR_W] wide and spans full height.
     * A rounded thumb grip is centred on the bar.
     */
    private fun drawHandle(canvas: Canvas, cx: Float, h: Float, mid: Float, color: Int, isStart: Boolean) {
        handleBarPaint.color = color
        // Bar: thin vertical strip
        val halfBar = BAR_W / 2f
        canvas.drawRoundRect(
            RectF(cx - halfBar, 0f, cx + halfBar, h),
            halfBar, halfBar, handleBarPaint
        )
        // Thumb: wider rounded rect in the centre
        thumbPaint.color = color
        val tw = THUMB_W / 2f
        val th = THUMB_H / 2f
        canvas.drawRoundRect(
            RectF(cx - tw, mid - th, cx + tw, mid + th),
            6f * (resources.displayMetrics.density), 6f * (resources.displayMetrics.density),
            thumbPaint
        )
        // Small arrow inside the thumb
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        arrowPaint.setColor(Color.WHITE)
        arrowPaint.style = Paint.Style.FILL
        val aw = 4f * resources.displayMetrics.density
        val path = Path()
        if (isStart) {
            // ◀ pointing left
            path.moveTo(cx + aw, mid - aw * 1.4f)
            path.lineTo(cx - aw, mid)
            path.lineTo(cx + aw, mid + aw * 1.4f)
        } else {
            // ▶ pointing right
            path.moveTo(cx - aw, mid - aw * 1.4f)
            path.lineTo(cx + aw, mid)
            path.lineTo(cx - aw, mid + aw * 1.4f)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val w = width.toFloat()
        if (w == 0f) return false

        val startPx   = trimStartRatio * w
        val endPx     = trimEndRatio   * w
        val playheadX = playRatio * w

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dStart    = abs(x - startPx)
                val dEnd      = abs(x - endPx)
                val dPlayhead = if (playheadVisible) abs(x - playheadX) else Float.MAX_VALUE
                dragging = when {
                    dStart < HANDLE_SLOP && dEnd < HANDLE_SLOP ->
                        if (dEnd <= dStart) Dragging.TRIM_END else Dragging.TRIM_START
                    dStart    < HANDLE_SLOP  -> Dragging.TRIM_START
                    dEnd      < HANDLE_SLOP  -> Dragging.TRIM_END
                    dPlayhead < PLAYHEAD_SLOP -> Dragging.PLAYHEAD
                    else                     -> Dragging.NONE
                }
                if (dragging != Dragging.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true); true
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
                        // Move line visually — don't seek yet
                        playRatio = ratio.coerceIn(trimStartRatio, trimEndRatio)
                    }
                    Dragging.NONE -> {}
                }
                invalidate()
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // Seek fires only once on release
                if (dragging == Dragging.PLAYHEAD) onScrubChanged?.invoke(playRatio)
                dragging = Dragging.NONE
                true
            }

            else -> false
        }
    }
}
