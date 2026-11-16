package app.harvester.companion.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.harvester.companion.R

@Composable
fun ShadowedPencilIcon(
    tint: Color,
    shadowColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val drawable = remember(tint) {
        ContextCompat.getDrawable(context, R.drawable.ic_pencil)?.mutate()?.apply {
            setTint(tint.toArgb())
        }
    }

    Canvas(modifier = modifier.size(48.dp)) {
        if (drawable != null) {
            val padding = 16.dp.toPx()
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            drawable.setBounds(0, 0, w, h)

            val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val maskCanvas = Canvas(mask)
            drawable.draw(maskCanvas)

            val shadowPaint = Paint().apply {
                setShadowLayer(8f, 0f, 2f, shadowColor.toArgb())
            }
            drawContext.canvas.nativeCanvas.drawBitmap(mask, padding, padding, shadowPaint)

            drawable.setBounds(
                padding.toInt(), padding.toInt(),
                (padding + w).toInt(), (padding + h).toInt()
            )
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}
