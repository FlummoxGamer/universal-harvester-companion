package app.harvester.companion.compose.base.progressbar

import android.view.View
import androidx.annotation.IntRange
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import app.harvester.companion.R

private const val DEFAULT_STROKE_WIDTH = 5
private const val DEFAULT_GLOW_RADIUS = 2
private const val DEFAULT_ANIMATION_SPEED = 1000
private const val MAX_PERCENTAGE = 100
private const val LIGHTEN_TRACK_COLOR_FACTOR = 0.2f

fun Color.lighten(factor: Float): Color {
    val red = (red + (1f - red) * factor).coerceIn(0f, 1f)
    val green = (green + (1f - green) * factor).coerceIn(0f, 1f)
    val blue = (blue + (1f - blue) * factor).coerceIn(0f, 1f)
    return Color(red, green, blue, alpha)
}

@Composable
fun AnimatedProgressBar(
    @IntRange(from = 0, to = 100) progress: Int,
    modifier: Modifier = Modifier,
    color: List<Color>? = null,
    trackColor: Color? = null,
    strokeWidth: Dp = DEFAULT_STROKE_WIDTH.dp,
    glowRadius: Dp? = DEFAULT_GLOW_RADIUS.dp,
    strokeCap: StrokeCap = StrokeCap.Butt,
    gradientAnimationSpeed: Duration = DEFAULT_ANIMATION_SPEED.milliseconds,
    progressAnimSpec: AnimationSpec<Int> = tween(durationMillis = 0),
) {
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    LaunchedEffect(progress) {
        if (progress == 0) {
            @Suppress("DEPRECATION")
            view.announceForAccessibility("Loading")
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.surface
    val trackBrush = remember(trackColor) {
        SolidColor(trackColor ?: backgroundColor.lighten(LIGHTEN_TRACK_COLOR_FACTOR))
    }

    val gradientColors = listOf(
        Color(0xFF457B9D),
        Color(0xFF1D3557),
        Color(0xFF457B9D)
    )
    val colors = remember(color, gradientColors, layoutDirection) {
        val colorList = color ?: gradientColors
        if (layoutDirection == LayoutDirection.Rtl) colorList.asReversed() else colorList
    }

    val animatedProgress by animateIntAsState(
        targetValue = progress.coerceIn(0, MAX_PERCENTAGE),
        animationSpec = progressAnimSpec,
    )

    val offset by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = gradientAnimationSpeed.inWholeMilliseconds.toInt(),
            easing = LinearEasing,
        ),
    )

    val brush: ShaderBrush = remember(offset) {
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val step = 1f / colors.size
                val start = step / 2

                val originalSpots = List(colors.size) { start + (step * it) }
                val transformedSpots = originalSpots.map { spot ->
                    val shiftedSpot = (spot + offset)
                    if (shiftedSpot > 1f) shiftedSpot - 1f else shiftedSpot
                }

                val pairs = colors.zip(transformedSpots).sortedBy { it.second }

                val margin = size.width / 2
                return LinearGradientShader(
                    colors = pairs.map { it.first },
                    colorStops = pairs.map { it.second },
                    from = Offset(-margin, 0f),
                    to = Offset(size.width + margin, 0f),
                )
            }
        }
    }

    Canvas(
        modifier.fillMaxWidth().semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = animatedProgress.toFloat(),
                range = 0f..100f,
                steps = 100,
            )
        }
    ) {
        val width = this.size.width
        val height = this.size.height

        val paint = Paint().apply {
            this.isAntiAlias = true
            this.style = PaintingStyle.Stroke
            this.strokeWidth = strokeWidth.toPx()
            this.strokeCap = strokeCap
            this.shader = brush.createShader(size)
        }

        glowRadius?.let { radius ->
            paint.nativePaint.apply {
                setShadowLayer(radius.toPx(), 0f, 0f, backgroundColor.toArgb())
            }
        }

        if (animatedProgress > 0 && animatedProgress < MAX_PERCENTAGE) {
            trackBrush.let { tBrush ->
                drawLine(
                    brush = tBrush,
                    start = Offset(0f, height / 2f),
                    end = Offset(width, height / 2f),
                    cap = strokeCap,
                    strokeWidth = strokeWidth.toPx(),
                )
            }
            val progressWidth = width * animatedProgress / MAX_PERCENTAGE
            drawIntoCanvas { canvas ->
                val startOffset: Offset
                val endOffset: Offset

                if (layoutDirection == LayoutDirection.Ltr) {
                    startOffset = Offset(0f, height / 2f)
                    endOffset = Offset(progressWidth, height / 2f)
                } else {
                    startOffset = Offset(width, height / 2f)
                    endOffset = Offset(width - progressWidth, height / 2f)
                }

                canvas.drawLine(
                    p1 = startOffset,
                    p2 = endOffset,
                    paint = paint,
                )
            }
        }
    }
}
