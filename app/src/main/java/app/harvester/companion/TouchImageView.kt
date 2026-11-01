package app.harvester.companion

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.OnDoubleTapListener
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("unused")
open class TouchImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    AppCompatImageView(context, attrs, defStyle) {
    var currentZoom = 0f
        private set

    private var touchMatrix: Matrix
    private var prevMatrix: Matrix
    var isZoomEnabled = true
    var isSuperZoomEnabled = true
    private var isRotateImageToFitScreen = false

    var orientationChangeFixedPixel: FixedPixel? = FixedPixel.CENTER
    var viewSizeChangeFixedPixel: FixedPixel? = FixedPixel.CENTER
    private var orientationJustChanged = false

    private var imageActionState: ImageActionState? = null
    private var userSpecifiedMinScale = 0f
    private var minScale = 0f
    private var maxScaleIsSetByMultiplier = false
    private var maxScaleMultiplier = 0f
    private var maxScale = 0f
    private var superMinScale = 0f
    private var superMaxScale = 0f
    private var floatMatrix: FloatArray

    var doubleTapScale = 0f
    private var fling: Fling? = null
    private var orientation = 0
    private var touchScaleType: ScaleType? = null
    private var imageRenderedAtLeastOnce = false
    private var onDrawReady = false
    private var delayedZoomVariables: ZoomVariables? = null

    private var viewWidth = 0
    private var viewHeight = 0
    private var prevViewWidth = 0
    private var prevViewHeight = 0

    private var matchViewWidth = 0f
    private var matchViewHeight = 0f
    private var prevMatchViewWidth = 0f
    private var prevMatchViewHeight = 0f
    private var scaleDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector
    private var touchCoordinatesListener: OnTouchCoordinatesListener? = null
    private var doubleTapListener: OnDoubleTapListener? = null
    private var userTouchListener: OnTouchListener? = null
    private var touchImageViewListener: OnTouchImageViewListener? = null

    init {
        super.setClickable(true)
        orientation = resources.configuration.orientation
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        touchMatrix = Matrix()
        prevMatrix = Matrix()
        floatMatrix = FloatArray(9)
        currentZoom = 1f
        if (touchScaleType == null) {
            touchScaleType = ScaleType.FIT_CENTER
        }
        minScale = 1f
        maxScale = 3f
        superMinScale = SUPER_MIN_MULTIPLIER * minScale
        superMaxScale = SUPER_MAX_MULTIPLIER * maxScale
        imageMatrix = touchMatrix
        scaleType = ScaleType.MATRIX
        setState(ImageActionState.NONE)
        onDrawReady = false
        super.setOnTouchListener(PrivateOnTouchListener())
    }

    fun setRotateImageToFitScreen(rotateImageToFitScreen: Boolean) {
        isRotateImageToFitScreen = rotateImageToFitScreen
    }

    override fun setOnTouchListener(onTouchListener: OnTouchListener?) {
        userTouchListener = onTouchListener
    }

    fun setOnTouchImageViewListener(onTouchImageViewListener: OnTouchImageViewListener) {
        touchImageViewListener = onTouchImageViewListener
    }

    fun setOnDoubleTapListener(onDoubleTapListener: OnDoubleTapListener) {
        doubleTapListener = onDoubleTapListener
    }

    fun setOnTouchCoordinatesListener(onTouchCoordinatesListener: OnTouchCoordinatesListener) {
        touchCoordinatesListener = onTouchCoordinatesListener
    }

    override fun setImageResource(resId: Int) {
        imageRenderedAtLeastOnce = false
        super.setImageResource(resId)
        savePreviousImageValues()
        fitImageToView()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        imageRenderedAtLeastOnce = false
        super.setImageBitmap(bm)
        savePreviousImageValues()
        fitImageToView()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        imageRenderedAtLeastOnce = false
        super.setImageDrawable(drawable)
        savePreviousImageValues()
        fitImageToView()
    }

    override fun setImageURI(uri: Uri?) {
        imageRenderedAtLeastOnce = false
        super.setImageURI(uri)
        savePreviousImageValues()
        fitImageToView()
    }

    override fun setScaleType(type: ScaleType) {
        if (type == ScaleType.MATRIX) {
            super.setScaleType(ScaleType.MATRIX)
        } else {
            touchScaleType = type
            if (onDrawReady) {
                setZoom(this)
            }
        }
    }

    override fun getScaleType() = touchScaleType!!

    val isZoomed: Boolean
        get() = currentZoom != 1f

    val zoomedRect: RectF
        get() {
            if (touchScaleType == ScaleType.FIT_XY) {
                throw UnsupportedOperationException("getZoomedRect() not supported with FIT_XY")
            }
            val topLeft = transformCoordTouchToBitmap(0f, 0f, true)
            val bottomRight = transformCoordTouchToBitmap(viewWidth.toFloat(), viewHeight.toFloat(), true)
            val w = getDrawableWidth(drawable).toFloat()
            val h = getDrawableHeight(drawable).toFloat()
            return RectF(topLeft.x / w, topLeft.y / h, bottomRight.x / w, bottomRight.y / h)
        }

    fun savePreviousImageValues() {
        if (viewHeight != 0 && viewWidth != 0) {
            touchMatrix.getValues(floatMatrix)
            prevMatrix.setValues(floatMatrix)
            prevMatchViewHeight = matchViewHeight
            prevMatchViewWidth = matchViewWidth
            prevViewHeight = viewHeight
            prevViewWidth = viewWidth
        }
    }

    public override fun onSaveInstanceState(): Parcelable {
        touchMatrix.getValues(floatMatrix)
        return bundleOf(
            "parent" to super.onSaveInstanceState(),
            "orientation" to orientation,
            "saveScale" to currentZoom,
            "matchViewHeight" to matchViewHeight,
            "matchViewWidth" to matchViewWidth,
            "viewWidth" to viewWidth,
            "viewHeight" to viewHeight,
            "matrix" to floatMatrix,
            "imageRendered" to imageRenderedAtLeastOnce,
            "viewSizeChangeFixedPixel" to viewSizeChangeFixedPixel,
            "orientationChangeFixedPixel" to orientationChangeFixedPixel
        )
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        if (state is Bundle) {
            super.onRestoreInstanceState(BundleCompat.getParcelable(state, "parent", Parcelable::class.java))
            currentZoom = state.getFloat("saveScale")
            floatMatrix = state.getFloatArray("matrix")!!
            prevMatrix.setValues(floatMatrix)
            prevMatchViewHeight = state.getFloat("matchViewHeight")
            prevMatchViewWidth = state.getFloat("matchViewWidth")
            prevViewHeight = state.getInt("viewHeight")
            prevViewWidth = state.getInt("viewWidth")
            imageRenderedAtLeastOnce = state.getBoolean("imageRendered")
            viewSizeChangeFixedPixel = BundleCompat.getSerializable(state, "viewSizeChangeFixedPixel", FixedPixel::class.java)
            orientationChangeFixedPixel = BundleCompat.getSerializable(state, "orientationChangeFixedPixel", FixedPixel::class.java)
            val oldOrientation = state.getInt("orientation")
            if (orientation != oldOrientation) {
                orientationJustChanged = true
            }
            return
        }
        super.onRestoreInstanceState(state)
    }

    override fun onDraw(canvas: Canvas) {
        onDrawReady = true
        imageRenderedAtLeastOnce = true
        if (delayedZoomVariables != null) {
            setZoom(delayedZoomVariables!!.scale, delayedZoomVariables!!.focusX, delayedZoomVariables!!.focusY, delayedZoomVariables!!.scaleType)
            delayedZoomVariables = null
        }
        super.onDraw(canvas)
    }

    public override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newOrientation = resources.configuration.orientation
        if (newOrientation != orientation) {
            orientationJustChanged = true
            orientation = newOrientation
        }
        savePreviousImageValues()
    }

    var maxZoom: Float
        get() = maxScale
        set(max) {
            maxScale = max
            superMaxScale = SUPER_MAX_MULTIPLIER * maxScale
            maxScaleIsSetByMultiplier = false
        }

    fun setMaxZoomRatio(max: Float) {
        maxScaleMultiplier = max
        maxScale = minScale * maxScaleMultiplier
        superMaxScale = SUPER_MAX_MULTIPLIER * maxScale
        maxScaleIsSetByMultiplier = true
    }

    var minZoom: Float
        get() = minScale
        set(min) {
            userSpecifiedMinScale = min
            if (min == AUTOMATIC_MIN_ZOOM) {
                if (touchScaleType == ScaleType.CENTER || touchScaleType == ScaleType.CENTER_CROP) {
                    val drawable = drawable
                    val drawableWidth = getDrawableWidth(drawable)
                    val drawableHeight = getDrawableHeight(drawable)
                    if (drawable != null && drawableWidth > 0 && drawableHeight > 0) {
                        val widthRatio = viewWidth.toFloat() / drawableWidth
                        val heightRatio = viewHeight.toFloat() / drawableHeight
                        minScale = if (touchScaleType == ScaleType.CENTER) {
                            min(widthRatio, heightRatio)
                        } else {
                            min(widthRatio, heightRatio) / max(widthRatio, heightRatio)
                        }
                    }
                } else {
                    minScale = 1.0f
                }
            } else {
                minScale = userSpecifiedMinScale
            }
            if (maxScaleIsSetByMultiplier) {
                setMaxZoomRatio(maxScaleMultiplier)
            }
            superMinScale = SUPER_MIN_MULTIPLIER * minScale
        }

    fun resetZoom() {
        currentZoom = 1f
        fitImageToView()
    }

    fun resetZoomAnimated() {
        setZoomAnimated(1f, 0.5f, 0.5f)
    }

    fun setZoom(scale: Float) {
        setZoom(scale, 0.5f, 0.5f)
    }

    fun setZoom(scale: Float, focusX: Float, focusY: Float) {
        setZoom(scale, focusX, focusY, touchScaleType)
    }

    fun setZoom(scale: Float, focusX: Float, focusY: Float, scaleType: ScaleType?) {
        if (!onDrawReady) {
            delayedZoomVariables = ZoomVariables(scale, focusX, focusY, scaleType)
            return
        }
        if (userSpecifiedMinScale == AUTOMATIC_MIN_ZOOM) {
            minZoom = AUTOMATIC_MIN_ZOOM
            if (currentZoom < minScale) {
                currentZoom = minScale
            }
        }
        if (scaleType != touchScaleType) {
            setScaleType(scaleType!!)
        }
        resetZoom()
        scaleImage(scale.toDouble(), viewWidth / 2.toFloat(), viewHeight / 2.toFloat(), isSuperZoomEnabled)
        touchMatrix.getValues(floatMatrix)
        floatMatrix[Matrix.MTRANS_X] = -(focusX * imageWidth - viewWidth * 0.5f)
        floatMatrix[Matrix.MTRANS_Y] = -(focusY * imageHeight - viewHeight * 0.5f)
        touchMatrix.setValues(floatMatrix)
        fixTrans()
        savePreviousImageValues()
        imageMatrix = touchMatrix
    }

    fun setZoom(imageSource: TouchImageView) {
        val center = imageSource.scrollPosition
        setZoom(imageSource.currentZoom, center.x, center.y, imageSource.scaleType)
    }

    val scrollPosition: PointF
        get() {
            val drawable = drawable ?: return PointF(.5f, .5f)
            val drawableWidth = getDrawableWidth(drawable)
            val drawableHeight = getDrawableHeight(drawable)
            val point = transformCoordTouchToBitmap(viewWidth / 2.toFloat(), viewHeight / 2.toFloat(), true)
            point.x /= drawableWidth.toFloat()
            point.y /= drawableHeight.toFloat()
            return point
        }

    private fun orientationMismatch(drawable: Drawable?): Boolean {
        return viewWidth > viewHeight != drawable!!.intrinsicWidth > drawable.intrinsicHeight
    }

    private fun getDrawableWidth(drawable: Drawable?): Int {
        return if (orientationMismatch(drawable) && isRotateImageToFitScreen) {
            drawable!!.intrinsicHeight
        } else
            drawable!!.intrinsicWidth
    }

    private fun getDrawableHeight(drawable: Drawable?): Int {
        return if (orientationMismatch(drawable) && isRotateImageToFitScreen) {
            drawable!!.intrinsicWidth
        } else
            drawable!!.intrinsicHeight
    }

    fun setScrollPosition(focusX: Float, focusY: Float) {
        setZoom(currentZoom, focusX, focusY)
    }

    private fun fixTrans() {
        touchMatrix.getValues(floatMatrix)
        val transX = floatMatrix[Matrix.MTRANS_X]
        val transY = floatMatrix[Matrix.MTRANS_Y]
        var offset = 0f
        if (isRotateImageToFitScreen && orientationMismatch(drawable)) {
            offset = imageWidth
        }
        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), imageWidth, offset)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), imageHeight, 0f)
        touchMatrix.postTranslate(fixTransX, fixTransY)
    }

    private fun fixScaleTrans() {
        fixTrans()
        touchMatrix.getValues(floatMatrix)
        if (imageWidth < viewWidth) {
            var xOffset = (viewWidth - imageWidth) / 2
            if (isRotateImageToFitScreen && orientationMismatch(drawable)) {
                xOffset += imageWidth
            }
            floatMatrix[Matrix.MTRANS_X] = xOffset
        }
        if (imageHeight < viewHeight) {
            floatMatrix[Matrix.MTRANS_Y] = (viewHeight - imageHeight) / 2
        }
        touchMatrix.setValues(floatMatrix)
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float, offset: Float): Float {
        val minTrans: Float
        val maxTrans: Float
        if (contentSize <= viewSize) {
            minTrans = offset
            maxTrans = offset + viewSize - contentSize
        } else {
            minTrans = offset + viewSize - contentSize
            maxTrans = offset
        }
        if (trans < minTrans) return -trans + minTrans
        return if (trans > maxTrans) -trans + maxTrans else 0f
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            0f
        } else
            delta
    }

    private val imageWidth: Float
        get() = matchViewWidth * currentZoom

    private val imageHeight: Float
        get() = matchViewHeight * currentZoom

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val drawable = drawable
        if (drawable == null || drawable.intrinsicWidth == 0 || drawable.intrinsicHeight == 0) {
            setMeasuredDimension(0, 0)
            return
        }
        val drawableWidth = getDrawableWidth(drawable)
        val drawableHeight = getDrawableHeight(drawable)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val totalViewWidth = setViewSize(widthMode, widthSize, drawableWidth)
        val totalViewHeight = setViewSize(heightMode, heightSize, drawableHeight)
        if (!orientationJustChanged) {
            savePreviousImageValues()
        }
        setMeasuredDimension(totalViewWidth, totalViewHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w - paddingRight -paddingLeft
        viewHeight = h - paddingTop - paddingBottom
        fitImageToView()
    }

    private fun fitImageToView() {
        val fixedPixel = if (orientationJustChanged) orientationChangeFixedPixel else viewSizeChangeFixedPixel
        orientationJustChanged = false
        val drawable = drawable
        if (drawable == null || drawable.intrinsicWidth == 0 || drawable.intrinsicHeight == 0) {
            return
        }
        @Suppress("SENSELESS_COMPARISON")
        if (touchMatrix == null || prevMatrix == null) {
            return
        }
        if (userSpecifiedMinScale == AUTOMATIC_MIN_ZOOM) {
            minZoom = AUTOMATIC_MIN_ZOOM
            if (currentZoom < minScale) {
                currentZoom = minScale
            }
        }
        val drawableWidth = getDrawableWidth(drawable)
        val drawableHeight = getDrawableHeight(drawable)

        var scaleX = viewWidth.toFloat() / drawableWidth
        var scaleY = viewHeight.toFloat() / drawableHeight
        when (touchScaleType) {
            ScaleType.CENTER -> {
                scaleY = 1f
                scaleX = scaleY
            }
            ScaleType.CENTER_CROP -> {
                scaleY = max(scaleX, scaleY)
                scaleX = scaleY
            }
            ScaleType.CENTER_INSIDE -> {
                run {
                    scaleY = min(1f, min(scaleX, scaleY))
                    scaleX = scaleY
                }
                run {
                    scaleY = min(scaleX, scaleY)
                    scaleX = scaleY
                }
            }
            ScaleType.FIT_CENTER, ScaleType.FIT_START, ScaleType.FIT_END -> {
                scaleY = min(scaleX, scaleY)
                scaleX = scaleY
            }
            ScaleType.FIT_XY -> Unit
            else -> Unit
        }

        val redundantXSpace = viewWidth - scaleX * drawableWidth
        val redundantYSpace = viewHeight - scaleY * drawableHeight
        matchViewWidth = viewWidth - redundantXSpace
        matchViewHeight = viewHeight - redundantYSpace
        if (!isZoomed && !imageRenderedAtLeastOnce) {
            if (isRotateImageToFitScreen && orientationMismatch(drawable)) {
                touchMatrix.setRotate(90f)
                touchMatrix.postTranslate(drawableWidth.toFloat(), 0f)
                touchMatrix.postScale(scaleX, scaleY)
            } else {
                touchMatrix.setScale(scaleX, scaleY)
            }
            when (touchScaleType) {
                ScaleType.FIT_START -> touchMatrix.postTranslate(0f, 0f)
                ScaleType.FIT_END -> touchMatrix.postTranslate(redundantXSpace, redundantYSpace)
                else -> touchMatrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)
            }
            currentZoom = 1f
        } else {
            if (prevMatchViewWidth == 0f || prevMatchViewHeight == 0f) {
                savePreviousImageValues()
            }
            prevMatrix.getValues(floatMatrix)
            floatMatrix[Matrix.MSCALE_X] = matchViewWidth / drawableWidth * currentZoom
            floatMatrix[Matrix.MSCALE_Y] = matchViewHeight / drawableHeight * currentZoom
            val transX = floatMatrix[Matrix.MTRANS_X]
            val transY = floatMatrix[Matrix.MTRANS_Y]

            val prevActualWidth = prevMatchViewWidth * currentZoom
            val actualWidth = imageWidth
            floatMatrix[Matrix.MTRANS_X] =
                newTranslationAfterChange(transX, prevActualWidth, actualWidth, prevViewWidth, viewWidth, drawableWidth, fixedPixel)

            val prevActualHeight = prevMatchViewHeight * currentZoom
            val actualHeight = imageHeight
            floatMatrix[Matrix.MTRANS_Y] =
                newTranslationAfterChange(transY, prevActualHeight, actualHeight, prevViewHeight, viewHeight, drawableHeight, fixedPixel)
            touchMatrix.setValues(floatMatrix)
        }
        fixTrans()
        imageMatrix = touchMatrix
    }

    private fun setViewSize(mode: Int, size: Int, drawableWidth: Int): Int {
        return when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(drawableWidth, size)
            MeasureSpec.UNSPECIFIED -> drawableWidth
            else -> size
        }
    }

    private fun newTranslationAfterChange(
        trans: Float,
        prevImageSize: Float,
        imageSize: Float,
        prevViewSize: Int,
        viewSize: Int,
        drawableSize: Int,
        sizeChangeFixedPixel: FixedPixel?
    ): Float {
        return when {
            imageSize < viewSize -> {
                (viewSize - drawableSize * floatMatrix[Matrix.MSCALE_X]) * 0.5f
            }
            trans > 0 -> {
                -((imageSize - viewSize) * 0.5f)
            }
            else -> {
                var fixedPixelPositionInView = 0.5f
                if (sizeChangeFixedPixel == FixedPixel.BOTTOM_RIGHT) {
                    fixedPixelPositionInView = 1.0f
                } else if (sizeChangeFixedPixel == FixedPixel.TOP_LEFT) {
                    fixedPixelPositionInView = 0.0f
                }
                val fixedPixelPositionInImage = (-trans + fixedPixelPositionInView * prevViewSize) / prevImageSize
                -(fixedPixelPositionInImage * imageSize - viewSize * fixedPixelPositionInView)
            }
        }
    }

    private fun setState(imageActionState: ImageActionState) {
        this.imageActionState = imageActionState
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        touchMatrix.getValues(floatMatrix)
        val x = floatMatrix[Matrix.MTRANS_X]
        return if (imageWidth < viewWidth) {
            false
        } else if (x >= -1 && direction < 0) {
            false
        } else abs(x) + viewWidth + 1 < imageWidth || direction <= 0
    }

    override fun canScrollVertically(direction: Int): Boolean {
        touchMatrix.getValues(floatMatrix)
        val y = floatMatrix[Matrix.MTRANS_Y]
        return if (imageHeight < viewHeight) {
            false
        } else if (y >= -1 && direction < 0) {
            false
        } else abs(y) + viewHeight + 1 < imageHeight || direction <= 0
    }

    private inner class GestureListener : SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return doubleTapListener?.onSingleTapConfirmed(e) ?: performClick()
        }

        override fun onLongPress(e: MotionEvent) {
            performLongClick()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            fling?.cancelFling()
            fling = Fling(velocityX.toInt(), velocityY.toInt()).also { compatPostOnAnimation(it) }
            return super.onFling(e1, e2, velocityX, velocityY)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            var consumed = false
            if (isZoomEnabled) {
                doubleTapListener?.let { consumed = it.onDoubleTap(e) }
                if (imageActionState == ImageActionState.NONE) {
                    val maxZoomScale = if (doubleTapScale == 0f) maxScale else doubleTapScale
                    val targetZoom = if (currentZoom == minScale) maxZoomScale else minScale
                    val doubleTap = DoubleTapZoom(targetZoom, e.x, e.y, false)
                    compatPostOnAnimation(doubleTap)
                    consumed = true
                }
            }
            return consumed
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            return doubleTapListener?.onDoubleTapEvent(e) ?: false
        }
    }

    private inner class PrivateOnTouchListener : OnTouchListener {
        private val last = PointF()
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (drawable == null) {
                setState(ImageActionState.NONE)
                return false
            }
            if (isZoomEnabled) {
                scaleDetector.onTouchEvent(event)
            }
            gestureDetector.onTouchEvent(event)
            val curr = PointF(event.x, event.y)
            if (imageActionState == ImageActionState.NONE || imageActionState == ImageActionState.DRAG || imageActionState == ImageActionState.FLING) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        last.set(curr)
                        fling?.cancelFling()
                        setState(ImageActionState.DRAG)
                    }
                    MotionEvent.ACTION_MOVE -> if (imageActionState == ImageActionState.DRAG) {
                        val deltaX = curr.x - last.x
                        val deltaY = curr.y - last.y
                        val fixTransX = getFixDragTrans(deltaX, viewWidth.toFloat(), imageWidth)
                        val fixTransY = getFixDragTrans(deltaY, viewHeight.toFloat(), imageHeight)
                        touchMatrix.postTranslate(fixTransX, fixTransY)
                        fixTrans()
                        last[curr.x] = curr.y
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> setState(ImageActionState.NONE)
                }
            }

            touchCoordinatesListener?.let {
                val bitmapPoint = transformCoordTouchToBitmap(event.x, event.y, true)
                it.onTouchCoordinate(v, event, bitmapPoint)
            }

            imageMatrix = touchMatrix
            userTouchListener?.onTouch(v, event)
            touchImageViewListener?.onMove()
            return true
        }
    }

    private inner class ScaleListener : SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            setState(ImageActionState.ZOOM)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleImage(detector.scaleFactor.toDouble(), detector.focusX, detector.focusY, isSuperZoomEnabled)
            touchImageViewListener?.onMove()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            setState(ImageActionState.NONE)
            var animateToZoomBoundary = false
            var targetZoom: Float = currentZoom
            if (currentZoom > maxScale) {
                targetZoom = maxScale
                animateToZoomBoundary = true
            } else if (currentZoom < minScale) {
                targetZoom = minScale
                animateToZoomBoundary = true
            }
            if (animateToZoomBoundary) {
                val doubleTap = DoubleTapZoom(targetZoom, (viewWidth / 2).toFloat(), (viewHeight / 2).toFloat(), isSuperZoomEnabled)
                compatPostOnAnimation(doubleTap)
            }
        }
    }

    private fun scaleImage(deltaScale: Double, focusX: Float, focusY: Float, stretchImageToSuper: Boolean) {
        var deltaScaleLocal = deltaScale
        val lowerScale: Float
        val upperScale: Float
        if (stretchImageToSuper) {
            lowerScale = superMinScale
            upperScale = superMaxScale
        } else {
            lowerScale = minScale
            upperScale = maxScale
        }
        val origScale = currentZoom
        currentZoom *= deltaScaleLocal.toFloat()
        if (currentZoom > upperScale) {
            currentZoom = upperScale
            deltaScaleLocal = upperScale / origScale.toDouble()
        } else if (currentZoom < lowerScale) {
            currentZoom = lowerScale
            deltaScaleLocal = lowerScale / origScale.toDouble()
        }
        touchMatrix.postScale(deltaScaleLocal.toFloat(), deltaScaleLocal.toFloat(), focusX, focusY)
        fixScaleTrans()
    }

    private inner class DoubleTapZoom(targetZoom: Float, focusX: Float, focusY: Float, stretchImageToSuper: Boolean) : Runnable {
        private val startTime: Long
        private val startZoom: Float
        private val targetZoom: Float
        private val bitmapX: Float
        private val bitmapY: Float
        private val stretchImageToSuper: Boolean
        private val interpolator = AccelerateDecelerateInterpolator()
        private val startTouch: PointF
        private val endTouch: PointF
        override fun run() {
            if (drawable == null) {
                setState(ImageActionState.NONE)
                return
            }
            val t = interpolate()
            val deltaScale = calculateDeltaScale(t)
            scaleImage(deltaScale, bitmapX, bitmapY, stretchImageToSuper)
            translateImageToCenterTouchPosition(t)
            fixScaleTrans()
            imageMatrix = touchMatrix
            touchImageViewListener?.onMove()
            if (t < 1f) {
                compatPostOnAnimation(this)
            } else {
                setState(ImageActionState.NONE)
            }
        }

        private fun translateImageToCenterTouchPosition(t: Float) {
            val targetX = startTouch.x + t * (endTouch.x - startTouch.x)
            val targetY = startTouch.y + t * (endTouch.y - startTouch.y)
            val curr = transformCoordBitmapToTouch(bitmapX, bitmapY)
            touchMatrix.postTranslate(targetX - curr.x, targetY - curr.y)
        }

        private fun interpolate(): Float {
            val currTime = System.currentTimeMillis()
            var elapsed = (currTime - startTime) / DEFAULT_ZOOM_TIME.toFloat()
            elapsed = min(1f, elapsed)
            return interpolator.getInterpolation(elapsed)
        }

        private fun calculateDeltaScale(t: Float): Double {
            val zoom = startZoom + t * (targetZoom - startZoom).toDouble()
            return zoom / currentZoom
        }

        init {
            setState(ImageActionState.ANIMATE_ZOOM)
            startTime = System.currentTimeMillis()
            startZoom = currentZoom
            this.targetZoom = targetZoom
            this.stretchImageToSuper = stretchImageToSuper
            val bitmapPoint = transformCoordTouchToBitmap(focusX, focusY, false)
            bitmapX = bitmapPoint.x
            bitmapY = bitmapPoint.y
            startTouch = transformCoordBitmapToTouch(bitmapX, bitmapY)
            endTouch = PointF((viewWidth / 2).toFloat(), (viewHeight / 2).toFloat())
        }
    }

    protected fun transformCoordTouchToBitmap(x: Float, y: Float, clipToBitmap: Boolean): PointF {
        touchMatrix.getValues(floatMatrix)
        val origW = drawable.intrinsicWidth.toFloat()
        val origH = drawable.intrinsicHeight.toFloat()
        val transX = floatMatrix[Matrix.MTRANS_X]
        val transY = floatMatrix[Matrix.MTRANS_Y]
        var finalX = (x - transX) * origW / imageWidth
        var finalY = (y - transY) * origH / imageHeight
        if (clipToBitmap) {
            finalX = min(max(finalX, 0f), origW)
            finalY = min(max(finalY, 0f), origH)
        }
        return PointF(finalX, finalY)
    }

    protected fun transformCoordBitmapToTouch(bx: Float, by: Float): PointF {
        touchMatrix.getValues(floatMatrix)
        val origW = drawable.intrinsicWidth.toFloat()
        val origH = drawable.intrinsicHeight.toFloat()
        val px = bx / origW
        val py = by / origH
        val finalX = floatMatrix[Matrix.MTRANS_X] + imageWidth * px
        val finalY = floatMatrix[Matrix.MTRANS_Y] + imageHeight * py
        return PointF(finalX, finalY)
    }

    private inner class Fling(velocityX: Int, velocityY: Int) : Runnable {
        var scroller: CompatScroller
        var currX: Int
        var currY: Int

        init {
            setState(ImageActionState.FLING)
            scroller = CompatScroller(context)
            touchMatrix.getValues(floatMatrix)
            var startX = floatMatrix[Matrix.MTRANS_X].toInt()
            val startY = floatMatrix[Matrix.MTRANS_Y].toInt()
            val minX: Int
            val maxX: Int
            val minY: Int
            val maxY: Int
            if (isRotateImageToFitScreen && orientationMismatch(drawable)) {
                startX -= imageWidth.toInt()
            }
            if (imageWidth > viewWidth) {
                minX = viewWidth - imageWidth.toInt()
                maxX = 0
            } else {
                maxX = startX
                minX = maxX
            }
            if (imageHeight > viewHeight) {
                minY = viewHeight - imageHeight.toInt()
                maxY = 0
            } else {
                maxY = startY
                minY = maxY
            }
            scroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY)
            currX = startX
            currY = startY
        }

        fun cancelFling() {
            setState(ImageActionState.NONE)
            scroller.forceFinished(true)
        }

        override fun run() {
            touchImageViewListener?.onMove()
            if (scroller.isFinished) {
                return
            }
            if (scroller.computeScrollOffset()) {
                val newX = scroller.currX
                val newY = scroller.currY
                val transX = newX - currX
                val transY = newY - currY
                currX = newX
                currY = newY
                touchMatrix.postTranslate(transX.toFloat(), transY.toFloat())
                fixTrans()
                imageMatrix = touchMatrix
                compatPostOnAnimation(this)
            }
        }
    }

    private inner class CompatScroller(context: Context?) {
        var overScroller: OverScroller = OverScroller(context)
        fun fling(startX: Int, startY: Int, velocityX: Int, velocityY: Int, minX: Int, maxX: Int, minY: Int, maxY: Int) {
            overScroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY)
        }

        fun forceFinished(finished: Boolean) {
            overScroller.forceFinished(finished)
        }

        val isFinished: Boolean
            get() = overScroller.isFinished

        fun computeScrollOffset(): Boolean {
            overScroller.computeScrollOffset()
            return overScroller.computeScrollOffset()
        }

        val currX: Int
            get() = overScroller.currX

        val currY: Int
            get() = overScroller.currY
    }

    private fun compatPostOnAnimation(runnable: Runnable) {
        postOnAnimation(runnable)
    }

    fun setZoomAnimated(scale: Float, focusX: Float, focusY: Float) {
        setZoomAnimated(scale, focusX, focusY, DEFAULT_ZOOM_TIME)
    }

    fun setZoomAnimated(scale: Float, focusX: Float, focusY: Float, zoomTimeMs: Int) {
        val animation = AnimatedZoom(scale, PointF(focusX, focusY), zoomTimeMs)
        compatPostOnAnimation(animation)
    }

    fun setZoomAnimated(scale: Float, focusX: Float, focusY: Float, zoomTimeMs: Int, listener: OnZoomFinishedListener?) {
        val animation = AnimatedZoom(scale, PointF(focusX, focusY), zoomTimeMs)
        animation.setListener(listener)
        compatPostOnAnimation(animation)
    }

    fun setZoomAnimated(scale: Float, focusX: Float, focusY: Float, listener: OnZoomFinishedListener?) {
        val animation = AnimatedZoom(scale, PointF(focusX, focusY), DEFAULT_ZOOM_TIME)
        animation.setListener(listener)
        compatPostOnAnimation(animation)
    }

    private inner class AnimatedZoom(targetZoom: Float, focus: PointF, zoomTimeMillis: Int) : Runnable {
        private val zoomTimeMillis: Int
        private val startTime: Long
        private val startZoom: Float
        private val targetZoom: Float
        private val startFocus: PointF
        private val targetFocus: PointF
        private val interpolator = LinearInterpolator()
        private var zoomFinishedListener: OnZoomFinishedListener? = null

        init {
            setState(ImageActionState.ANIMATE_ZOOM)
            startTime = System.currentTimeMillis()
            startZoom = currentZoom
            this.targetZoom = targetZoom
            this.zoomTimeMillis = zoomTimeMillis
            startFocus = scrollPosition
            targetFocus = focus
        }

        override fun run() {
            val t = interpolate()
            val nextZoom = startZoom + (targetZoom - startZoom) * t
            val nextX = startFocus.x + (targetFocus.x - startFocus.x) * t
            val nextY = startFocus.y + (targetFocus.y - startFocus.y) * t
            setZoom(nextZoom, nextX, nextY)
            if (t < 1f) {
                compatPostOnAnimation(this)
            } else {
                setState(ImageActionState.NONE)
                zoomFinishedListener?.onZoomFinished()
            }
        }

        private fun interpolate(): Float {
            var elapsed = (System.currentTimeMillis() - startTime) / zoomTimeMillis.toFloat()
            elapsed = min(1f, elapsed)
            return interpolator.getInterpolation(elapsed)
        }

        fun setListener(listener: OnZoomFinishedListener?) {
            this.zoomFinishedListener = listener
        }
    }

    companion object {
        private const val SUPER_MIN_MULTIPLIER = .75f
        private const val SUPER_MAX_MULTIPLIER = 1.25f
        private const val DEFAULT_ZOOM_TIME = 500
        const val AUTOMATIC_MIN_ZOOM = -1.0f
    }
}

enum class FixedPixel { CENTER, TOP_LEFT, BOTTOM_RIGHT }
enum class ImageActionState { NONE, DRAG, ZOOM, FLING, ANIMATE_ZOOM }
interface OnTouchImageViewListener { fun onMove() }
interface OnTouchCoordinatesListener { fun onTouchCoordinate(view: View, event: MotionEvent, bitmapPoint: PointF) }
interface OnZoomFinishedListener { fun onZoomFinished() }
data class ZoomVariables(val scale: Float, val focusX: Float, val focusY: Float, val scaleType: android.widget.ImageView.ScaleType?)
