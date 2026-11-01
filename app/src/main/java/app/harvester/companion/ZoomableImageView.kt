package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ClickableViewAccessibility")
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val start = PointF()
    private val mid = PointF()
    private var mode = NONE
    private var minScale = 1f
    private var maxScale = 3f

    private var scaleDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null

    init {
        scaleType = ScaleType.MATRIX
        setupGestures()
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = ZOOM
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var scaleFactor = detector.scaleFactor
                val values = FloatArray(9)
                matrix.getValues(values)
                val currentScale = values[Matrix.MSCALE_X]
                val targetScale = currentScale * scaleFactor

                if (targetScale < minScale) {
                    scaleFactor = minScale / currentScale
                } else if (targetScale > maxScale) {
                    scaleFactor = maxScale / currentScale
                }

                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                imageMatrix = matrix
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val values = FloatArray(9)
                matrix.getValues(values)
                val currentScale = values[Matrix.MSCALE_X]
                if (currentScale > minScale) {
                    resetZoom()
                } else {
                    matrix.postScale(2f, 2f, e.x, e.y)
                    imageMatrix = matrix
                }
                return true
            }
        })
    }

    fun resetZoom() {
        val drawable = drawable ?: return
        val viewWidth = width - paddingLeft - paddingRight
        val viewHeight = height - paddingTop - paddingBottom
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight

        if (drawableWidth <= 0 || drawableHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        val scale = max(viewWidth.toFloat() / drawableWidth, viewHeight.toFloat() / drawableHeight)
        matrix.reset()
        matrix.postScale(scale, scale)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f
        matrix.postTranslate(dx, dy)
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)
        gestureDetector?.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                savedMatrix.set(matrix)
                mid.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
                mode = ZOOM
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && !scaleDetector!!.isInProgress) {
                    matrix.set(savedMatrix)
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                    imageMatrix = matrix
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                constrainMatrix()
            }
        }
        return true
    }

    private fun constrainMatrix() {
        val values = FloatArray(9)
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        val drawable = drawable ?: return
        val rect = android.graphics.RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        matrix.mapRect(rect)

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() < width) {
            deltaX = (width - rect.width()) / 2f - rect.left
        } else if (rect.left > 0) {
            deltaX = -rect.left
        } else if (rect.right < width) {
            deltaX = width - rect.right
        }

        if (rect.height() < height) {
            deltaY = (height - rect.height()) / 2f - rect.top
        } else if (rect.top > 0) {
            deltaY = -rect.top
        } else if (rect.bottom < height) {
            deltaY = height - rect.bottom
        }

        matrix.postTranslate(deltaX, deltaY)
        imageMatrix = matrix
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
