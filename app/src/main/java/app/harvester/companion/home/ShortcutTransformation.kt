package app.harvester.companion.home

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.abs

class TransparentCornerTransformation : Transformation {
    override val cacheKey: String = "transparent_corners_v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        return removeSolidCorners(input)
    }

    companion object {
        /**
         * Fast bulk-array flood fill that strips uniform white/light-neutral square box
         * backgrounds from favicon corners while preserving full-bleed colored brand icons
         * (such as solid red YouTube or dark GitHub/X badges).
         */
        fun removeSolidCorners(input: Bitmap): Bitmap {
            val w = input.width
            val h = input.height
            if (w < 2 || h < 2) return input

            val total = w * h
            val pixels = IntArray(total)
            input.getPixels(pixels, 0, w, 0, 0, w, h)

            val c1 = pixels[0]
            val c2 = pixels[w - 1]
            val c3 = pixels[(h - 1) * w]
            val c4 = pixels[total - 1]

            // Already transparent corners -> nothing to strip
            if (Color.alpha(c1) < 20) return input

            // Only strip uniform light neutral/white box corners so colored brand squares stay intact
            if (!isNeutralLightBackground(c1)) return input

            val tolerance = 28
            if (!isColorSimilar(c1, c2, tolerance) ||
                !isColorSimilar(c1, c3, tolerance) ||
                !isColorSimilar(c1, c4, tolerance)
            ) {
                return input
            }

            val visited = BooleanArray(total)
            val queue = IntArray(total)
            var head = 0
            var tail = 0

            fun enqueue(idx: Int) {
                if (!visited[idx]) {
                    visited[idx] = true
                    queue[tail++] = idx
                }
            }

            enqueue(0)
            enqueue(w - 1)
            enqueue((h - 1) * w)
            enqueue(total - 1)

            var modified = false
            while (head < tail) {
                val idx = queue[head++]
                val pixel = pixels[idx]
                if (isColorSimilar(pixel, c1, tolerance)) {
                    pixels[idx] = Color.TRANSPARENT
                    modified = true
                    val x = idx % w
                    val y = idx / w
                    if (x + 1 < w) enqueue(idx + 1)
                    if (x - 1 >= 0) enqueue(idx - 1)
                    if (y + 1 < h) enqueue(idx + w)
                    if (y - 1 >= 0) enqueue(idx - w)
                }
            }

            if (!modified) return input

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }

        private fun isNeutralLightBackground(color: Int): Boolean {
            val a = Color.alpha(color)
            if (a < 200) return false
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            return minC >= 224 && (maxC - minC) <= 24
        }

        private fun isColorSimilar(c1: Int, c2: Int, tolerance: Int): Boolean {
            return abs(Color.red(c1) - Color.red(c2)) <= tolerance &&
                abs(Color.green(c1) - Color.green(c2)) <= tolerance &&
                abs(Color.blue(c1) - Color.blue(c2)) <= tolerance
        }
    }
}
