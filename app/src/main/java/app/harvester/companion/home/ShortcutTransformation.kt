package app.harvester.companion.home

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation
import java.util.LinkedList

class TransparentCornerTransformation : Transformation {
    override val cacheKey: String = "transparent_corners"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 2 || h < 2) return input

        val c1 = input.getPixel(0, 0)
        val c2 = input.getPixel(w - 1, 0)
        val c3 = input.getPixel(0, h - 1)
        val c4 = input.getPixel(w - 1, h - 1)

        val tolerance = 30
        if (isColorSimilar(c1, c2, tolerance) && 
            isColorSimilar(c1, c3, tolerance) && 
            isColorSimilar(c1, c4, tolerance)) {
            
            if (Color.alpha(c1) == 0) return input

            val result = input.copy(Bitmap.Config.ARGB_8888, true)
            val visited = Array(w) { BooleanArray(h) }
            val queue = LinkedList<Pair<Int, Int>>()

            queue.add(Pair(0, 0))
            queue.add(Pair(w - 1, 0))
            queue.add(Pair(0, h - 1))
            queue.add(Pair(w - 1, h - 1))

            while (queue.isNotEmpty()) {
                val (x, y) = queue.poll()
                if (x < 0 || x >= w || y < 0 || y >= h || visited[x][y]) continue
                visited[x][y] = true

                val pixel = result.getPixel(x, y)
                if (isColorSimilar(pixel, c1, tolerance)) {
                    result.setPixel(x, y, Color.TRANSPARENT)
                    queue.add(Pair(x + 1, y))
                    queue.add(Pair(x - 1, y))
                    queue.add(Pair(x, y + 1))
                    queue.add(Pair(x, y - 1))
                }
            }
            return result
        }
        return input
    }

    private fun isColorSimilar(c1: Int, c2: Int, tolerance: Int): Boolean {
        return Math.abs(Color.red(c1) - Color.red(c2)) <= tolerance &&
               Math.abs(Color.green(c1) - Color.green(c2)) <= tolerance &&
               Math.abs(Color.blue(c1) - Color.blue(c2)) <= tolerance
    }
}
