package com.ymagenuz.snipdotranslate.select.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.ymagenuz.snipdotranslate.select.core.AreaRect
import com.ymagenuz.snipdotranslate.select.core.Pen
import com.ymagenuz.snipdotranslate.select.core.Pt
import com.ymagenuz.snipdotranslate.select.core.SelectionModel
import kotlin.math.hypot
import kotlin.math.max

private const val MAX_ZOOM = 5f
private const val LOUPE_ZOOM = 1.8f
private const val LOUPE_RADIUS_DP = 60f
private const val LOUPE_OFFSET_DP = 120f

/**
 * Shows a frozen image and turns touches into pen strokes. Nothing is drawn over the
 * image until the user selects something, so the screen looks untouched until then.
 * Strokes drawn before the scan finishes are kept and applied when it does.
 */
class SelectionCanvasView(context: Context, private val image: Bitmap) : View(context) {
    private class PendingStroke(val pen: Pen, val radius: Float) {
        val points = mutableListOf<Pt>()
        var cancelled = false
    }

    private val density = resources.displayMetrics.density
    private val view = Matrix()
    private val inverse = Matrix()
    private var fitScale = 1f
    private val point = FloatArray(2)

    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(96, 47, 111, 237) }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(160, 60, 60, 60)
    }

    private var model: SelectionModel? = null
    private val pending = mutableListOf<PendingStroke>()
    private var currentPending: PendingStroke? = null

    private var strokeActive = false
    private var zooming = false
    private var trailPath = Path()
    private var strokeStart = Pt(0f, 0f)
    private var strokeLast = Pt(0f, 0f)
    private var touchX = 0f
    private var touchY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f

    var pen = Pen.TEXT
    var brushRadiusPx = 0f
    var onStrokeActive: (Boolean) -> Unit = {}
    var onSelectionChanged: () -> Unit = {}
    var onArea: (AreaRect) -> Unit = {}

    /** Supplies the scanned words and applies any strokes already drawn. */
    fun setModel(newModel: SelectionModel) {
        model = newModel
        pending.filterNot { it.cancelled }.forEach { stroke ->
            newModel.beginStroke(stroke.pen, stroke.points.first(), stroke.radius)
            stroke.points.drop(1).forEach(newModel::extendStroke)
            newModel.endStroke()
        }
        pending.clear()
        onSelectionChanged()
        invalidate()
    }

    fun refresh() = invalidate()

    /** A screenshot matches the screen exactly; a photo is fitted and centred. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fitScale = minOf(w.toFloat() / image.width, h.toFloat() / image.height)
        view.setScale(fitScale, fitScale)
        keepImageOnScreen()
    }

    private fun currentRadius(): Float =
        if (pen == Pen.PLUS || pen == Pen.MINUS) brushRadiusPx / scale() else 0f

    private fun scale(): Float {
        val values = FloatArray(9)
        view.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun toImage(x: Float, y: Float): Pt {
        view.invert(inverse)
        point[0] = x
        point[1] = y
        inverse.mapPoints(point)
        return Pt(point[0], point[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginStroke(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN -> beginZoom(event)
            MotionEvent.ACTION_MOVE -> if (zooming) updateZoom(event) else extendStroke(event)
            MotionEvent.ACTION_POINTER_UP -> if (zooming && event.pointerCount <= 2) zooming = false
            MotionEvent.ACTION_UP -> if (zooming) zooming = false else endStroke()
            MotionEvent.ACTION_CANCEL -> cancelStroke()
        }
        return true
    }

    private fun beginStroke(x: Float, y: Float) {
        val at = toImage(x, y)
        touchX = x
        touchY = y
        strokeStart = at
        strokeLast = at
        trailPath = Path().apply { moveTo(at.x, at.y) }
        strokeActive = true
        onStrokeActive(true)

        val current = model
        if (current != null) {
            current.beginStroke(pen, at, currentRadius())
            onSelectionChanged()
        } else {
            currentPending = PendingStroke(pen, currentRadius()).also {
                it.points += at
                pending += it
            }
        }
        invalidate()
    }

    private fun extendStroke(event: MotionEvent) {
        if (!strokeActive) return
        for (index in 0 until event.historySize) {
            extendTo(event.getHistoricalX(index), event.getHistoricalY(index))
        }
        extendTo(event.x, event.y)
        onSelectionChanged()
        invalidate()
    }

    private fun extendTo(x: Float, y: Float) {
        val at = toImage(x, y)
        touchX = x
        touchY = y
        strokeLast = at
        trailPath.lineTo(at.x, at.y)
        model?.extendStroke(at)
        currentPending?.points?.add(at)
    }

    private fun endStroke() {
        if (!strokeActive) return
        strokeActive = false
        onStrokeActive(false)
        val area = model?.endStroke()
        currentPending = null
        onSelectionChanged()
        invalidate()
        area?.let(onArea)
    }

    private fun cancelStroke() {
        if (!strokeActive) return
        strokeActive = false
        onStrokeActive(false)
        model?.cancelStroke()
        currentPending?.cancelled = true
        currentPending = null
        onSelectionChanged()
        invalidate()
    }

    private fun beginZoom(event: MotionEvent) {
        cancelStroke()
        zooming = true
        readFingers(event)
    }

    private fun readFingers(event: MotionEvent) {
        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
        lastSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)).coerceAtLeast(1f)
    }

    private fun updateZoom(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)).coerceAtLeast(1f)

        val factor = (span / lastSpan).coerceIn(fitScale / scale(), MAX_ZOOM * fitScale / scale())
        view.postScale(factor, factor, focusX, focusY)
        view.postTranslate(focusX - lastFocusX, focusY - lastFocusY)
        keepImageOnScreen()

        lastFocusX = focusX
        lastFocusY = focusY
        lastSpan = span
        invalidate()
    }

    private fun keepImageOnScreen() {
        val values = FloatArray(9)
        view.getValues(values)
        val zoom = values[Matrix.MSCALE_X]
        values[Matrix.MTRANS_X] = placed(values[Matrix.MTRANS_X], width.toFloat(), image.width * zoom)
        values[Matrix.MTRANS_Y] = placed(values[Matrix.MTRANS_Y], height.toFloat(), image.height * zoom)
        view.setValues(values)
    }

    /** Centres content smaller than the view; otherwise leaves no gap at either edge. */
    private fun placed(offset: Float, viewSize: Float, contentSize: Float): Float =
        if (contentSize <= viewSize) (viewSize - contentSize) / 2f else offset.coerceIn(viewSize - contentSize, 0f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(image, view, null)
        canvas.save()
        canvas.concat(view)
        drawMarks(canvas)
        canvas.restore()
        if (strokeActive && pen != Pen.AREA) drawLoupe(canvas)
    }

    private fun drawMarks(canvas: Canvas) {
        model?.let { current ->
            current.selectedIds.forEach { id ->
                val corners = current.words[id].quad.corners
                val path = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                canvas.drawPath(path, highlight)
            }
        }
        if (!strokeActive) return

        val inverseScale = 1f / scale()
        when (pen) {
            Pen.PLUS, Pen.MINUS -> {
                val color = if (pen == Pen.PLUS) Color.argb(60, 47, 111, 237) else Color.argb(60, 220, 60, 60)
                trail.color = color
                trail.strokeWidth = brushRadiusPx * 2f * inverseScale
                canvas.drawPath(trailPath, trail)
            }
            Pen.LASSO -> {
                trail.color = Color.argb(200, 47, 111, 237)
                trail.strokeWidth = 2f * density * inverseScale
                canvas.drawPath(trailPath, trail)
            }
            Pen.AREA -> {
                trail.color = Color.argb(220, 0, 137, 123)
                trail.strokeWidth = 2f * density * inverseScale
                canvas.drawRect(
                    minOf(strokeStart.x, strokeLast.x),
                    minOf(strokeStart.y, strokeLast.y),
                    max(strokeStart.x, strokeLast.x),
                    max(strokeStart.y, strokeLast.y),
                    trail,
                )
            }
            Pen.TEXT, Pen.PARAGRAPH -> Unit
        }
    }

    /** A magnified view above the finger, which would otherwise cover the word. */
    private fun drawLoupe(canvas: Canvas) {
        val radius = LOUPE_RADIUS_DP * density
        val offset = LOUPE_OFFSET_DP * density
        val centerX = touchX.coerceIn(radius, width - radius)
        val centerY = if (touchY - offset - radius > 0) touchY - offset else touchY + offset

        val clip = Path().apply { addCircle(centerX, centerY, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.translate(centerX, centerY)
        canvas.scale(LOUPE_ZOOM, LOUPE_ZOOM)
        canvas.translate(-touchX, -touchY)
        canvas.drawBitmap(image, view, null)
        canvas.concat(view)
        drawMarks(canvas)
        canvas.restore()
        canvas.drawCircle(centerX, centerY, radius, loupeBorder)
    }
}
