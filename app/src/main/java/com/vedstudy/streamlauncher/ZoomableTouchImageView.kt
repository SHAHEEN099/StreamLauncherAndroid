package com.vedstudy.streamlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

enum class ReaderColorMode {
    NORMAL,
    NIGHT,
    SEPIA
}

class ZoomableTouchImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()

    private var scale = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var origWidth = 0f
    private var origHeight = 0f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    var onSingleTapListener: (() -> Unit)? = null

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    fun applyColorMode(colorMode: ReaderColorMode) {
        when (colorMode) {
            ReaderColorMode.NORMAL -> {
                colorFilter = null
            }
            ReaderColorMode.NIGHT -> {
                // Invert colors + adjust gamma to create a comfortable dark paper reading experience
                val invertMatrix = ColorMatrix(floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 240f,
                    0f, -0.85f, 0f, 0f, 240f,
                    0f, 0f, -0.85f, 0f, 240f,
                    0f, 0f, 0f, 1f, 0f
                ))
                colorFilter = ColorMatrixColorFilter(invertMatrix)
            }
            ReaderColorMode.SEPIA -> {
                // Warm soothing sepia paper tone
                val sepiaMatrix = ColorMatrix().apply {
                    setScale(1.0f, 0.93f, 0.82f, 1.0f)
                }
                colorFilter = ColorMatrixColorFilter(sepiaMatrix)
            }
        }
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            origWidth = bm.width.toFloat()
            origHeight = bm.height.toFloat()
            resetZoom()
        }
    }

    fun resetZoom() {
        scale = 1f
        fitToScreen()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToScreen()
    }

    private fun fitToScreen() {
        val bm = drawable ?: return
        val bmWidth = bm.intrinsicWidth.toFloat()
        val bmHeight = bm.intrinsicHeight.toFloat()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0 || bmWidth <= 0 || bmHeight <= 0) return

        val scaleX = viewWidth / bmWidth
        val scaleY = viewHeight / bmHeight
        val fitScale = minOf(scaleX, scaleY)

        matrix.reset()
        matrix.setScale(fitScale, fitScale)

        val redundantXSpace = viewWidth - (fitScale * bmWidth)
        val redundantYSpace = viewHeight - (fitScale * bmHeight)

        matrix.postTranslate(redundantXSpace / 2f, redundantYSpace / 2f)
        scale = 1f
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
                if (scale > 1f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = ZOOM
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && scale > 1f) {
                    val dx = curr.x - start.x
                    val dy = curr.y - start.y
                    matrix.set(savedMatrix)
                    matrix.postTranslate(dx, dy)
                    checkMatrixBounds()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (scale <= 1f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        imageMatrix = matrix
        return true
    }

    private fun checkMatrixBounds() {
        val values = FloatArray(9)
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val bm = drawable ?: return
        val currentBmWidth = bm.intrinsicWidth * values[Matrix.MSCALE_X]
        val currentBmHeight = bm.intrinsicHeight * values[Matrix.MSCALE_Y]

        var fixTransX = 0f
        var fixTransY = 0f

        if (currentBmWidth < viewWidth) {
            fixTransX = (viewWidth - currentBmWidth) / 2f - transX
        } else if (transX > 0) {
            fixTransX = -transX
        } else if (transX + currentBmWidth < viewWidth) {
            fixTransX = viewWidth - (transX + currentBmWidth)
        }

        if (currentBmHeight < viewHeight) {
            fixTransY = (viewHeight - currentBmHeight) / 2f - transY
        } else if (transY > 0) {
            fixTransY = -transY
        } else if (transY + currentBmHeight < viewHeight) {
            fixTransY = viewHeight - (transY + currentBmHeight)
        }

        if (fixTransX != 0f || fixTransY != 0f) {
            matrix.postTranslate(fixTransX, fixTransY)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = scale
            scale *= mScaleFactor

            if (scale > maxScale) {
                scale = maxScale
                mScaleFactor = maxScale / origScale
            } else if (scale < minScale) {
                scale = minScale
                mScaleFactor = minScale / origScale
            }

            if (scale > 1f) {
                parent?.requestDisallowInterceptTouchEvent(true)
            } else {
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            matrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            checkMatrixBounds()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapListener?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.2f) {
                resetZoom()
            } else {
                val targetScale = 2.5f
                val factor = targetScale / scale
                scale = targetScale
                matrix.postScale(factor, factor, e.x, e.y)
                checkMatrixBounds()
                imageMatrix = matrix
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return true
        }
    }
}
