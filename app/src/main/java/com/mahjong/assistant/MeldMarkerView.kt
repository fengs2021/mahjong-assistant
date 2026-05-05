package com.mahjong.assistant

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 副露标注视图 — 支持双指缩放/平移 + 四点标注
 *
 * 模式:
 *   PAN — 默认, 单指平移, 双指缩放
 *   MARK — 标注中, 单指点放置角点(1→2→3→4形成四边形)
 *   ADJUST — 调整中, 拖拽已有角点
 */

class MeldMarkerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Annotation(val points: MutableList<PointF> = mutableListOf(), var label: String = "未识别") {
        val bounds: Rect get() {
            if (points.size < 4) return Rect()
            val xs = points.map { it.x }; val ys = points.map { it.y }
            return Rect(xs.min().toInt(), ys.min().toInt(), xs.max().toInt(), ys.max().toInt())
        }
    }

    enum class Mode { PAN, MARK, ADJUST }
    var mode = Mode.PAN
    var sourceBitmap: Bitmap? = null
    private var displayMatrix = Matrix()
    private var displayMatrixInverse = Matrix()
    private val annotations = mutableListOf<Annotation>()
    private var currentAnnotation: Annotation? = null
    private var dragIndex = -1

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastFocusX = 0f; private var lastFocusY = 0f
    private var panStartX = 0f; private var panStartY = 0f
    private var isPanning = false

    private val pointRadius = 24f
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00FF00.toInt(); style = Paint.Style.FILL; alpha = 180 }
    private val pointStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00FF00.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFF4444.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA00FF00.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f) }

    // 标注完成回调
    var onAnnotationComplete: ((Annotation) -> Unit)? = null

    fun setImage(bitmap: Bitmap) {
        sourceBitmap = bitmap
        annotations.clear(); currentAnnotation = null; mode = Mode.PAN
        resetMatrix(); invalidate()
    }

    fun startAnnotation() {
        if (sourceBitmap == null) return
        currentAnnotation = Annotation()
        mode = Mode.MARK
        invalidate()
    }

    fun cancelAnnotation() {
        currentAnnotation = null; mode = Mode.PAN; invalidate()
    }

    fun removeAnnotation(index: Int) {
        if (index in annotations.indices) { annotations.removeAt(index); invalidate() }
    }

    fun getAnnotations(): List<Annotation> = annotations.toList()

    /** 从原始bitmap中按标注框裁切 */
    fun cropAnnotation(ann: Annotation): Bitmap? {
        val bmp = sourceBitmap ?: return null
        val b = ann.bounds
        if (b.width() < 10 || b.height() < 10) return null
        // 裁外包矩形(保留透视倾斜，不矫正)
        val x = b.left.coerceIn(0, bmp.width - 1)
        val y = b.top.coerceIn(0, bmp.height - 1)
        val w = (b.width()).coerceAtMost(bmp.width - x)
        val h = (b.height()).coerceAtMost(bmp.height - y)
        return try { Bitmap.createBitmap(bmp, x, y, w, h) } catch (_: Exception) { null }
    }

    // ═══════ 绘制 ═══════
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF1A2A1A.toInt())
        sourceBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, displayMatrix, null)
        }
        // 已完成标注
        for (ann in annotations) drawAnnotation(canvas, ann, false)
        // 正在标注
        currentAnnotation?.let { drawAnnotation(canvas, it, true) }
    }

    private fun drawAnnotation(canvas: Canvas, ann: Annotation, isActive: Boolean) {
        val pts = ann.points
        if (pts.isEmpty()) return
        val floatPts = FloatArray(pts.size * 2)
        for (i in pts.indices) { floatPts[i*2] = pts[i].x; floatPts[i*2+1] = pts[i].y }
        displayMatrix.mapPoints(floatPts)

        if (isActive && pts.size > 1) {
            // 虚线连到当前最后一点
            for (i in 0 until pts.size - 1) {
                canvas.drawLine(floatPts[i*2], floatPts[i*2+1], floatPts[(i+1)*2], floatPts[(i+1)*2+1], activeLinePaint)
            }
        }
        if (pts.size == 4) {
            // 实线四边形
            val poly = Path().apply {
                moveTo(floatPts[0], floatPts[1])
                for (i in 1..3) lineTo(floatPts[i*2], floatPts[i*2+1])
                close()
            }
            canvas.drawPath(poly, linePaint)
        }
        // 角点
        for (i in pts.indices) {
            canvas.drawCircle(floatPts[i*2], floatPts[i*2+1], pointRadius, pointPaint)
            canvas.drawCircle(floatPts[i*2], floatPts[i*2+1], pointRadius, pointStroke)
        }
    }

    // ═══════ 触摸 ═══════
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val x = event.x; val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.ADJUST) {
                    dragIndex = hitTestAnnotation(x, y)
                    if (dragIndex >= 0) return true
                }
                if (mode == Mode.MARK && currentAnnotation != null) {
                    // 放置角点
                    val src = floatArrayOf(x, y)
                    displayMatrixInverse.mapPoints(src)
                    currentAnnotation!!.points.add(PointF(src[0], src[1]))
                    invalidate()
                    if (currentAnnotation!!.points.size == 4) {
                        annotations.add(currentAnnotation!!)
                        onAnnotationComplete?.invoke(currentAnnotation!!)
                        currentAnnotation = null
                        mode = Mode.PAN
                    }
                    return true
                }
                panStartX = x; panStartY = y; isPanning = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.ADJUST && dragIndex >= 0 && currentAnnotation != null) {
                    val src = floatArrayOf(x, y)
                    displayMatrixInverse.mapPoints(src)
                    currentAnnotation!!.points[dragIndex].set(src[0], src[1])
                    invalidate()
                    return true
                }
                if (isPanning && event.pointerCount == 1) {
                    val dx = x - panStartX; val dy = y - panStartY
                    if (abs(dx) > 5f || abs(dy) > 5f) {
                        displayMatrix.postTranslate(dx, dy)
                        displayMatrix.invert(displayMatrixInverse)
                        panStartX = x; panStartY = y
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false; dragIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(px: Float, py: Float, ann: Annotation): Int {
        for (i in ann.points.indices) {
            val src = floatArrayOf(ann.points[i].x, ann.points[i].y)
            displayMatrix.mapPoints(src)
            val dx = px - src[0]; val dy = py - src[1]
            if (sqrt(dx*dx + dy*dy) < pointRadius * 2) return i
        }
        return -1
    }

    private fun hitTestAnnotation(px: Float, py: Float): Int {
        if (currentAnnotation == null) return -1
        return hitTest(px, py, currentAnnotation!!)
    }

    /** 进入调整模式 — 选中已有标注的第index个 */
    fun startAdjust(index: Int) {
        if (index !in annotations.indices) return
        currentAnnotation = annotations[index]
        annotations.removeAt(index)
        mode = Mode.ADJUST
        invalidate()
    }

    fun finishAdjust() {
        currentAnnotation?.let { annotations.add(it) }
        currentAnnotation = null; mode = Mode.PAN; invalidate()
    }

    // ═══════ 缩放 ═══════
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX; lastFocusY = detector.focusY
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.coerceIn(0.5f, 3.0f)
            displayMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            displayMatrix.invert(displayMatrixInverse)
            invalidate()
            return true
        }
    }

    private fun resetMatrix() {
        val bmp = sourceBitmap ?: return
        displayMatrix.reset()
        val scale = width.toFloat() / bmp.width
        displayMatrix.postTranslate(0f, 0f)
        displayMatrix.postScale(scale, scale)
        displayMatrix.invert(displayMatrixInverse)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (sourceBitmap != null) resetMatrix()
    }
}
