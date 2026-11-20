package app.harvester.companion.wallpaper

import kotlin.math.abs

/**
 * Non-destructive playback clip window for a trimmed local motion wallpaper.
 * The original video file stays 100% untouched on disk (zero re-encoding, zero quality loss)
 * and ExoPlayer loops strictly inside [startMs]..[endMs].
 */
data class VideoClipBounds(
    val startMs: Long,
    val endMs: Long,
) {
    val isValid: Boolean
        get() = startMs >= 0L && endMs > startMs + 250L

    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Unified normalized framing transform shared identically between
 * Preview (WallpaperZoomableViewport) and Home (WallpaperBackground) for BOTH images and videos.
 *
 * - [scale]: 1f..4f zoom factor applied over natural ContentScale.Crop sizing.
 * - [offsetXFraction]: horizontal translation as a fraction of viewport width (0f = centered).
 * - [offsetYFraction]: vertical translation as a fraction of viewport height (0f = centered).
 */
data class WallpaperTransform(
    val scale: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
) {
    val isIdentity: Boolean
        get() = scale <= 1.01f &&
            abs(offsetXFraction) < 0.002f &&
            abs(offsetYFraction) < 0.002f

    /**
     * Storage-safe clamp preserving 1.0x aspect-overflow pan offsets (e.g., wide 16:9 media
     * framed on a portrait screen) while bounding extreme invalid values.
     */
    fun clamped(): WallpaperTransform {
        val safeScale = scale.coerceIn(1f, MAX_SCALE)
        return WallpaperTransform(
            scale = safeScale,
            offsetXFraction = offsetXFraction.coerceIn(-MAX_OFFSET_FRACTION, MAX_OFFSET_FRACTION),
            offsetYFraction = offsetYFraction.coerceIn(-MAX_OFFSET_FRACTION, MAX_OFFSET_FRACTION),
        )
    }

    /**
     * Exact pixel-boundary clamp for a concrete media aspect ratio and viewport aspect ratio
     * so neither photos nor videos can ever expose black background edges.
     */
    fun clampedForAspect(mediaAspect: Float, viewportAspect: Float): WallpaperTransform {
        val safeScale = scale.coerceIn(1f, MAX_SCALE)
        val (maxX, maxY) = computeMaxPanFractions(safeScale, mediaAspect, viewportAspect)
        return WallpaperTransform(
            scale = safeScale,
            offsetXFraction = offsetXFraction.coerceIn(-maxX, maxX),
            offsetYFraction = offsetYFraction.coerceIn(-maxY, maxY),
        )
    }

    companion object {
        const val MAX_SCALE = 4f
        const val MAX_OFFSET_FRACTION = 4f
        val Identity = WallpaperTransform(1f, 0f, 0f)

        /**
         * Returns `(cropFactorX, cropFactorY)` representing how much wider/taller the media is
         * than the viewport under `ContentScale.Crop` at `scale = 1.0f`.
         */
        fun computeCropFactors(mediaAspect: Float, viewportAspect: Float): Pair<Float, Float> {
            if (mediaAspect <= 0.01f || viewportAspect <= 0.01f) {
                return 1f to 1f
            }
            return if (mediaAspect > viewportAspect) {
                (mediaAspect / viewportAspect) to 1f
            } else {
                1f to (viewportAspect / mediaAspect)
            }
        }

        /**
         * Maximum valid `(offsetXFraction, offsetYFraction)` before any black edge would appear.
         * Allows full panning across hidden cropped edges even at `scale == 1.0f`.
         */
        fun computeMaxPanFractions(
            scale: Float,
            mediaAspect: Float,
            viewportAspect: Float,
        ): Pair<Float, Float> {
            val safeScale = scale.coerceIn(1f, MAX_SCALE)
            val (cropX, cropY) = computeCropFactors(mediaAspect, viewportAspect)
            val maxX = ((cropX * safeScale - 1f) / 2f).coerceAtLeast(0f)
            val maxY = ((cropY * safeScale - 1f) / 2f).coerceAtLeast(0f)
            return maxX to maxY
        }
    }
}
