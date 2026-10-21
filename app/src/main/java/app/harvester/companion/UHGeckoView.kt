package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.core.view.NestedScrollingChild
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

class UHGeckoView(context: Context) : GeckoView(context), NestedScrollingChild {

    private var lastY: Int = 0
    private val scrollOffset = IntArray(2)
    private val scrollConsumed = IntArray(2)
    private var gestureCanReachParent = true

    enum class InitialScrollDirection { NOT_YET, DOWN, UP }
    private var initialScrollDirection = InitialScrollDirection.NOT_YET
    private var initialDownY: Float = 0f
    private var nestedOffsetY: Int = 0
    private var childHelper: NestedScrollingChildHelper = NestedScrollingChildHelper(this)

    private var handledResult: Int = 0
    private var scrollableDirections: Int = 0
    private var overscrollDirections: Int = 0

    init {
        isNestedScrollingEnabled = true
    }

    private fun canOverscrollTop(): Boolean {
        return (overscrollDirections and PanZoomController.OVERSCROLL_DIRECTION_TOP) != 0
    }

    private fun isTouchHandledByWebsite(): Boolean {
        return (handledResult and PanZoomController.INPUT_RESULT_HANDLED_CONTENT) != 0
    }

    private fun isTouchHandledByBrowser(): Boolean {
        return (handledResult and PanZoomController.INPUT_RESULT_HANDLED) != 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val event = MotionEvent.obtain(ev)
        val action = ev.actionMasked
        val eventY = event.y.toInt()

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                val allowScroll = !shouldPinOnScreen() && isTouchHandledByBrowser()
                var deltaY = lastY - eventY

                if (allowScroll && dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset)) {
                    deltaY -= scrollConsumed[1]
                    event.offsetLocation(0f, (-scrollOffset[1]).toFloat())
                    nestedOffsetY += scrollOffset[1]
                }

                lastY = eventY - scrollOffset[1]

                if (allowScroll && dispatchNestedScroll(0, scrollOffset[1], 0, deltaY, scrollOffset)) {
                    lastY -= scrollOffset[1]
                    event.offsetLocation(0f, scrollOffset[1].toFloat())
                    nestedOffsetY += scrollOffset[1]
                }

                if (initialScrollDirection == InitialScrollDirection.NOT_YET) {
                    if (event.y > initialDownY) initialScrollDirection = InitialScrollDirection.UP
                    else if (event.y < initialDownY) initialScrollDirection = InitialScrollDirection.DOWN
                }
            }

            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateInputResult(event)
                initialScrollDirection = InitialScrollDirection.NOT_YET
                nestedOffsetY = 0
                lastY = eventY
                initialDownY = event.y
                event.recycle()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handledResult = 0
                scrollableDirections = 0
                overscrollDirections = 0
                stopNestedScroll()
                parent?.requestDisallowInterceptTouchEvent(false)
                gestureCanReachParent = true
            }
        }

        val eventHandled = super.onTouchEvent(event)
        event.recycle()
        return eventHandled
    }

    private fun updateInputResult(event: MotionEvent) {
        val eventAction = event.actionMasked
        super.onTouchEventForDetailResult(event).accept { detail ->
            if (!gestureCanReachParent || detail == null) return@accept

            handledResult = detail.handledResult
            scrollableDirections = detail.scrollableDirections
            overscrollDirections = detail.overscrollDirections

            if (eventAction == MotionEvent.ACTION_DOWN) {
                gestureCanReachParent = canOverscrollTop()
                when (initialScrollDirection) {
                    InitialScrollDirection.NOT_YET -> {
                        if (gestureCanReachParent && !isTouchHandledByWebsite()) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    InitialScrollDirection.UP -> {
                        if (gestureCanReachParent && !isTouchHandledByWebsite()) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    InitialScrollDirection.DOWN -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
        }
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int): Boolean = childHelper.startNestedScroll(axes)

    override fun stopNestedScroll() = childHelper.stopNestedScroll()

    override fun hasNestedScrollingParent(): Boolean = childHelper.hasNestedScrollingParent()

    override fun dispatchNestedScroll(dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, offsetInWindow: IntArray?): Boolean {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)
    }

    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?): Boolean {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)
    }

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean): Boolean {
        return childHelper.dispatchNestedFling(velocityX, velocityY, consumed)
    }

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean {
        return childHelper.dispatchNestedPreFling(velocityX, velocityY)
    }
}
