package app.harvester.companion.utils.reorderable

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface DragGestureDetector {
    suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
        onH1Popup: () -> Unit
    )

    object Press : DragGestureDetector {
        override suspend fun PointerInputScope.detect(
            onDragStart: (Offset) -> Unit,
            onDragEnd: () -> Unit,
            onDragCancel: () -> Unit,
            onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
            onH1Popup: () -> Unit
        ) {
            detectDragGestures(onDragStart, onDragEnd, onDragCancel, onDrag)
        }
    }

    object H1 : DragGestureDetector {
        override suspend fun PointerInputScope.detect(
            onDragStart: (Offset) -> Unit,
            onDragEnd: () -> Unit,
            onDragCancel: () -> Unit,
            onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
            onH1Popup: () -> Unit
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var isDragReady = false
                var isPopupShowing = false
                var isDragging = false
                var offset = Offset.Zero

                val dragJob = launch {
                    delay(150)
                    isDragReady = true
                }
                val popupJob = launch {
                    delay(500)
                    if (!isDragging && !isPopupShowing && offset.getDistance() < 20f) {
                        isPopupShowing = true
                        onH1Popup()
                    }
                }

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (isPopupShowing) break

                    val delta = change.positionChange()
                    offset += delta

                    if (offset.getDistance() > 20f) {
                        popupJob.cancel()
                        if (isDragReady && !isDragging) {
                            isDragging = true
                            onDragStart(down.position)
                        } else if (!isDragReady) {
                            dragJob.cancel()
                        }
                    }

                    if (isDragging) {
                        onDrag(change, delta)
                        change.consume()
                    }
                }

                dragJob.cancel()
                popupJob.cancel()
                if (isDragging) onDragEnd() else onDragCancel()
            }
        }
    }

    object LongPress : DragGestureDetector {
        override suspend fun PointerInputScope.detect(
            onDragStart: (Offset) -> Unit,
            onDragEnd: () -> Unit,
            onDragCancel: () -> Unit,
            onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
            onH1Popup: () -> Unit
        ) {
            detectDragGesturesAfterLongPress(onDragStart, onDragEnd, onDragCancel, onDrag)
        }
    }
}
