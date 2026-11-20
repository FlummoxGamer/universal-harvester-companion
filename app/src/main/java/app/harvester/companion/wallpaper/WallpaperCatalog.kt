package app.harvester.companion.wallpaper

import android.content.Context
import kotlin.math.roundToInt

/**
 * Curated wallpaper catalog with verified Motion assets, deduplicated collections,
 * and dynamic full-screen-resolution Unsplash URL resolution (q=100, no 2000px cap).
 */
object WallpaperCatalog {

    private const val REPO_CDN_BASE =
        "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers"

    val categorizedWallpapers: Map<String, List<String>> = linkedMapOf(
        // Verified existing assets in /wallpapers/motion/ (removed broken 404 motion_2 & motion_3)
        "Motion" to listOf(
            "$REPO_CDN_BASE/motion/motion_1.mp4",
            "$REPO_CDN_BASE/motion/motion_4.mp4",
            "$REPO_CDN_BASE/motion/motion_5.mp4",
            "$REPO_CDN_BASE/motion/motion_6.mp4",
            "$REPO_CDN_BASE/motion/motion_7.mp4",
            "$REPO_CDN_BASE/motion/motion_8.mp4",
            "$REPO_CDN_BASE/motion/motion_9.mp4",
            "$REPO_CDN_BASE/motion/motion_10.mp4",
        ),
        "Anime" to listOf(
            "$REPO_CDN_BASE/anime/anime_1.webp",
            "$REPO_CDN_BASE/anime/anime_2.webp",
            "$REPO_CDN_BASE/anime/anime_3.webp",
            "$REPO_CDN_BASE/anime/anime_4.webp",
            "$REPO_CDN_BASE/anime/anime_5.webp",
            "$REPO_CDN_BASE/anime/anime_6.webp",
            "$REPO_CDN_BASE/anime/anime_7.webp",
            "$REPO_CDN_BASE/anime/anime_8.webp",
            "$REPO_CDN_BASE/anime/anime_9.webp",
            "$REPO_CDN_BASE/anime/anime_10.webp",
            "$REPO_CDN_BASE/anime/anime_11.webp",
            "$REPO_CDN_BASE/anime/anime_12.webp",
            "$REPO_CDN_BASE/anime/anime_13.webp",
        ),
        "Cinematic" to listOf(
            "https://images.unsplash.com/photo-1478720568477-152d9b164e26",
            "https://images.unsplash.com/photo-1485846234645-a62644f84728",
            "https://images.unsplash.com/photo-1536440136628-849c177e76a1",
            "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba",
            "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c",
            "https://images.unsplash.com/photo-1440404653325-ab127d49abc1",
            "https://images.unsplash.com/photo-1485095329183-d0797cdc5676",
        ),
        "Abstract" to listOf(
            "https://images.unsplash.com/photo-1579546929518-9e396f3cc809",
            "https://images.unsplash.com/photo-1554034483-04fda0d3507b",
            "https://images.unsplash.com/photo-1507608616759-54f48f0af0ee",
            "https://images.unsplash.com/photo-1557672172-298e090bd0f1",
            "https://images.unsplash.com/photo-1604871000636-074fa5117945",
            "https://images.unsplash.com/photo-1567095761054-7a02e69e5c43",
            "https://images.unsplash.com/photo-1541701494587-cb58502866ab",
            "https://images.unsplash.com/photo-1558591710-4b4a1ae0f04d",
        ),
        // Deduplicated distinct liquid/metallic abstract photography
        "Liquid Metal" to listOf(
            "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e",
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe",
            "https://images.unsplash.com/photo-1614850715649-1d0106293bd1",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853",
            "https://images.unsplash.com/photo-1620121692029-d088224ddc74",
            "https://images.unsplash.com/photo-1618005198919-d3d4b5a92ead",
            "https://images.unsplash.com/photo-1634017839464-5c339ebe3cb4",
            "https://images.unsplash.com/photo-1635070041078-e363dbe005cb",
        ),
    )

    fun canonicalSourceKey(url: String): String = url.substringBefore('?').trim()

    fun isUnsplashUrl(url: String): Boolean = url.contains("images.unsplash.com", ignoreCase = true)

    /**
     * Lightweight crisp thumbnail URL for scrolling grids (fast loading, minimal bandwidth).
     */
    fun thumbnailUrl(rawUrl: String, widthPx: Int = 540): String {
        if (!isUnsplashUrl(rawUrl)) return rawUrl
        val base = rawUrl.substringBefore('?')
        return "$base?q=85&w=${widthPx.coerceIn(320, 900)}&auto=format&fit=crop"
    }

    /**
     * Full native screen-max URL (q=100, dynamically sized to device long edge with zoom headroom).
     */
    fun fullResolutionUrl(ctx: Context, rawUrl: String): String {
        if (!isUnsplashUrl(rawUrl)) return rawUrl
        val base = rawUrl.substringBefore('?')
        val screen = WallpaperDisplayMetrics.getNativeScreenResolution(ctx)
        val requestedEdge = (screen.longEdge * 1.5f).roundToInt()
            .coerceIn(2160, WallpaperDisplayMetrics.GPU_MAX_TEXTURE_EDGE)
        return "$base?q=100&w=$requestedEdge&auto=format&fit=crop"
    }
}
