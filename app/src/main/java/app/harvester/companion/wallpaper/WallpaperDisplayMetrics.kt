package app.harvester.companion.wallpaper

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Physical display resolution metrics without artificial downscaling ceilings.
 */
data class ScreenResolution(
    val widthPx: Int,
    val heightPx: Int,
) {
    val longEdge: Int get() = max(widthPx, heightPx)
    val shortEdge: Int get() = min(widthPx, heightPx)
}

/**
 * Resolves exact physical display bounds without artificial downscale caps,
 * ensuring wallpapers decode at the maximum native resolution the device screen can show.
 */
object WallpaperDisplayMetrics {

    /** Safe hardware GPU max bitmap/texture edge to avoid Canvas/GLES OOM on extreme photos. */
    const val GPU_MAX_TEXTURE_EDGE = 4096

    fun getNativeScreenResolution(ctx: Context): ScreenResolution {
        return try {
            val dm = ctx.resources.displayMetrics
            var w = dm.widthPixels
            var h = dm.heightPixels
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                w = max(w, bounds.width())
                h = max(h, bounds.height())
            }
            ScreenResolution(
                widthPx = w.coerceAtLeast(1080),
                heightPx = h.coerceAtLeast(1080),
            )
        } catch (_: Exception) {
            ScreenResolution(1080, 2400)
        }
    }

    /**
     * Computes exact pixel decode dimensions for Coil so images render at full native
     * display clarity (including crisp headroom when zoomed), safely clamped to GPU max texture.
     */
    fun getDecodeTargetSize(ctx: Context, zoomFactor: Float = 1f): Pair<Int, Int> {
        val screen = getNativeScreenResolution(ctx)
        val factor = zoomFactor.coerceIn(1f, 2.25f)
        val targetW = (screen.widthPx * factor).toInt().coerceIn(screen.widthPx, GPU_MAX_TEXTURE_EDGE)
        val targetH = (screen.heightPx * factor).toInt().coerceIn(screen.heightPx, GPU_MAX_TEXTURE_EDGE)
        return targetW to targetH
    }
}
