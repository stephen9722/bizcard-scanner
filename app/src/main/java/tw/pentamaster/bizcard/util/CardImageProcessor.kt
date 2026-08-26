package tw.pentamaster.bizcard.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detects the four long outer edges of a business card and rectifies the perspective before OCR.
 *
 * This intentionally has no OpenCV/JNI dependency. The app targets current Android releases and
 * stays free of an extra native library/ABI surface. The detector is tuned for the capture screen:
 * a landscape business card filling most of the horizontal guide. If confidence is low, the
 * original photo is left untouched and OCR simply runs on it as before.
 */
object CardImageProcessor {

    data class Result(
        val applied: Boolean,
        val confidence: Double = 0.0
    )

    private data class EdgeLine(
        /** y at image center for horizontal lines, x at image center for vertical lines. */
        val base: Double,
        val slope: Double,
        val score: Double
    )

    private data class Quad(
        val topLeft: DPoint,
        val topRight: DPoint,
        val bottomRight: DPoint,
        val bottomLeft: DPoint,
        val confidence: Double
    )

    fun rectifyInPlace(file: File): Result {
        if (!file.exists() || file.length() <= 0L) return Result(false)

        val source = decodeOriented(file, MAX_SOURCE_EDGE) ?: return Result(false)
        try {
            val detectionBitmap = scaleToMaxEdge(source, DETECTION_EDGE)
            val quad = try {
                detectCard(detectionBitmap)
            } finally {
                if (detectionBitmap !== source) detectionBitmap.recycle()
            } ?: return Result(false)

            val detectorSize = detectorDimensions(source.width, source.height, DETECTION_EDGE)
            val sx = source.width.toDouble() / detectorSize.first.toDouble()
            val sy = source.height.toDouble() / detectorSize.second.toDouble()

            val srcQuad = listOf(
                DPoint(quad.topLeft.x * sx, quad.topLeft.y * sy),
                DPoint(quad.topRight.x * sx, quad.topRight.y * sy),
                DPoint(quad.bottomRight.x * sx, quad.bottomRight.y * sy),
                DPoint(quad.bottomLeft.x * sx, quad.bottomLeft.y * sy)
            )

            val rectified = rectify(source, srcQuad) ?: return Result(false, quad.confidence)
            try {
                if (!replaceJpegSafely(file, rectified)) return Result(false, quad.confidence)
            } finally {
                rectified.recycle()
            }
            return Result(true, quad.confidence)
        } catch (_: Throwable) {
            // Image preprocessing is an enhancement, never a reason to make scanning fail.
            return Result(false)
        } finally {
            source.recycle()
        }
    }

    /** Returns the dimensions produced by [scaleToMaxEdge] without allocating a Bitmap. */
    private fun detectorDimensions(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest.toDouble()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun decodeOriented(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return decoded
        }

        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (_: Throwable) {
            decoded
        }
    }

    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val dims = detectorDimensions(source.width, source.height, maxEdge)
        if (dims.first == source.width && dims.second == source.height) return source
        return Bitmap.createScaledBitmap(source, dims.first, dims.second, true)
    }

    private fun detectCard(bitmap: Bitmap): Quad? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 240 || height < 240) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xff
            val g = (p ushr 8) and 0xff
            val b = p and 0xff
            gray[i] = (77 * r + 150 * g + 29 * b) ushr 8
        }

        val gradX = IntArray(pixels.size)
        val gradY = IntArray(pixels.size)
        var sumX = 0L
        var sumY = 0L
        var samples = 0L

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val tl = gray[i - width - 1]
                val tc = gray[i - width]
                val tr = gray[i - width + 1]
                val ml = gray[i - 1]
                val mr = gray[i + 1]
                val bl = gray[i + width - 1]
                val bc = gray[i + width]
                val br = gray[i + width + 1]

                val gx = abs(-tl + tr - 2 * ml + 2 * mr - bl + br)
                val gy = abs(-tl - 2 * tc - tr + bl + 2 * bc + br)
                gradX[i] = gx
                gradY[i] = gy

                if ((x and 3) == 0 && (y and 3) == 0) {
                    sumX += gx
                    sumY += gy
                    samples++
                }
            }
        }

        if (samples == 0L) return null
        val meanX = max(1.0, sumX.toDouble() / samples)
        val meanY = max(1.0, sumY.toDouble() / samples)

        val top = bestHorizontal(
            gradY, width, height,
            (height * 0.14).roundToInt(), (height * 0.48).roundToInt(), meanY
        )
        val bottom = bestHorizontal(
            gradY, width, height,
            (height * 0.52).roundToInt(), (height * 0.86).roundToInt(), meanY
        )
        val left = bestVertical(
            gradX, width, height,
            (width * 0.04).roundToInt(), (width * 0.42).roundToInt(), meanX
        )
        val right = bestVertical(
            gradX, width, height,
            (width * 0.58).roundToInt(), (width * 0.96).roundToInt(), meanX
        )

        val tl = intersect(top, left, width, height) ?: return null
        val tr = intersect(top, right, width, height) ?: return null
        val br = intersect(bottom, right, width, height) ?: return null
        val bl = intersect(bottom, left, width, height) ?: return null
        val points = listOf(tl, tr, br, bl)

        val topWidth = PerspectiveMath.distance(tl, tr)
        val bottomWidth = PerspectiveMath.distance(bl, br)
        val leftHeight = PerspectiveMath.distance(tl, bl)
        val rightHeight = PerspectiveMath.distance(tr, br)
        val avgWidth = (topWidth + bottomWidth) / 2.0
        val avgHeight = (leftHeight + rightHeight) / 2.0
        if (avgHeight < 1.0) return null

        val aspect = avgWidth / avgHeight
        val areaFraction = PerspectiveMath.polygonArea(points) / (width.toDouble() * height.toDouble())
        val widthFraction = avgWidth / width.toDouble()
        val heightFraction = avgHeight / height.toDouble()

        val marginX = width * 0.04
        val marginY = height * 0.04
        val allInside = points.all {
            it.x >= -marginX && it.x <= width + marginX &&
                it.y >= -marginY && it.y <= height + marginY
        }

        if (!allInside ||
            aspect !in 1.22..2.20 ||
            areaFraction !in 0.14..0.78 ||
            widthFraction !in 0.45..1.02 ||
            heightFraction !in 0.18..0.78 ||
            tr.x <= tl.x || br.x <= bl.x || bl.y <= tl.y || br.y <= tr.y
        ) return null

        val confidence = minOf(
            top.score / meanY,
            bottom.score / meanY,
            left.score / meanX,
            right.score / meanX
        )
        if (confidence < MIN_CONFIDENCE) return null

        return Quad(tl, tr, br, bl, confidence)
    }

    private fun bestHorizontal(
        gradient: IntArray,
        width: Int,
        height: Int,
        minBase: Int,
        maxBase: Int,
        globalMean: Double
    ): EdgeLine {
        var best = EdgeLine(height / 2.0, 0.0, 0.0)
        val centerX = (width - 1) / 2.0
        val xStart = (width * 0.07).roundToInt().coerceAtLeast(2)
        val xEnd = (width * 0.93).roundToInt().coerceAtMost(width - 3)
        val stepX = max(2, (xEnd - xStart) / 150)
        val strongThreshold = globalMean * 2.2

        var slope = -MAX_SLOPE
        while (slope <= MAX_SLOPE + 1e-9) {
            var base = minBase
            while (base <= maxBase) {
                var sum = 0.0
                var strong = 0
                var count = 0
                var x = xStart
                while (x <= xEnd) {
                    val y = (base + slope * (x - centerX)).roundToInt()
                    if (y in 3 until height - 3) {
                        var localMax = 0
                        for (dy in -2..2) {
                            localMax = max(localMax, gradient[(y + dy) * width + x])
                        }
                        sum += localMax
                        if (localMax >= strongThreshold) strong++
                        count++
                    }
                    x += stepX
                }
                if (count > 0) {
                    val continuity = strong.toDouble() / count.toDouble()
                    val score = (sum / count.toDouble()) * (0.65 + 0.35 * continuity)
                    if (score > best.score) best = EdgeLine(base.toDouble(), slope, score)
                }
                base += 3
            }
            slope += SLOPE_STEP
        }
        return best
    }

    private fun bestVertical(
        gradient: IntArray,
        width: Int,
        height: Int,
        minBase: Int,
        maxBase: Int,
        globalMean: Double
    ): EdgeLine {
        var best = EdgeLine(width / 2.0, 0.0, 0.0)
        val centerY = (height - 1) / 2.0
        val yStart = (height * 0.18).roundToInt().coerceAtLeast(2)
        val yEnd = (height * 0.82).roundToInt().coerceAtMost(height - 3)
        val stepY = max(2, (yEnd - yStart) / 130)
        val strongThreshold = globalMean * 2.2

        var slope = -MAX_SLOPE
        while (slope <= MAX_SLOPE + 1e-9) {
            var base = minBase
            while (base <= maxBase) {
                var sum = 0.0
                var strong = 0
                var count = 0
                var y = yStart
                while (y <= yEnd) {
                    val x = (base + slope * (y - centerY)).roundToInt()
                    if (x in 3 until width - 3) {
                        var localMax = 0
                        for (dx in -2..2) {
                            localMax = max(localMax, gradient[y * width + x + dx])
                        }
                        sum += localMax
                        if (localMax >= strongThreshold) strong++
                        count++
                    }
                    y += stepY
                }
                if (count > 0) {
                    val continuity = strong.toDouble() / count.toDouble()
                    val score = (sum / count.toDouble()) * (0.65 + 0.35 * continuity)
                    if (score > best.score) best = EdgeLine(base.toDouble(), slope, score)
                }
                base += 3
            }
            slope += SLOPE_STEP
        }
        return best
    }

    /** Intersects y = hSlope*x+hB with x = vSlope*y+vD. */
    private fun intersect(
        horizontal: EdgeLine,
        vertical: EdgeLine,
        width: Int,
        height: Int
    ): DPoint? {
        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val hB = horizontal.base - horizontal.slope * centerX
        val vD = vertical.base - vertical.slope * centerY
        val denominator = 1.0 - vertical.slope * horizontal.slope
        if (abs(denominator) < 1e-8) return null
        val x = (vertical.slope * hB + vD) / denominator
        val y = horizontal.slope * x + hB
        if (!x.isFinite() || !y.isFinite()) return null
        return DPoint(x, y)
    }

    private fun rectify(source: Bitmap, quad: List<DPoint>): Bitmap? {
        if (quad.size != 4) return null
        val tl = quad[0]
        val tr = quad[1]
        val br = quad[2]
        val bl = quad[3]

        val rawWidth = max(PerspectiveMath.distance(tl, tr), PerspectiveMath.distance(bl, br))
        val rawHeight = max(PerspectiveMath.distance(tl, bl), PerspectiveMath.distance(tr, br))
        if (rawWidth < 80.0 || rawHeight < 50.0) return null

        val longest = max(rawWidth, rawHeight)
        val outputScale = min(1.0, MAX_OUTPUT_EDGE / longest)
        val outWidth = max(1, (rawWidth * outputScale).roundToInt())
        val outHeight = max(1, (rawHeight * outputScale).roundToInt())
        if (outWidth < 64 || outHeight < 40) return null

        val map = PerspectiveMath.unitSquareToQuad(tl, tr, br, bl) ?: return null
        val srcWidth = source.width
        val srcHeight = source.height
        val srcPixels = IntArray(srcWidth * srcHeight)
        source.getPixels(srcPixels, 0, srcWidth, 0, 0, srcWidth, srcHeight)
        val dstPixels = IntArray(outWidth * outHeight)

        for (y in 0 until outHeight) {
            val v = if (outHeight == 1) 0.0 else y.toDouble() / (outHeight - 1).toDouble()
            for (x in 0 until outWidth) {
                val u = if (outWidth == 1) 0.0 else x.toDouble() / (outWidth - 1).toDouble()
                val p = map.map(u, v)
                dstPixels[y * outWidth + x] = bilinear(srcPixels, srcWidth, srcHeight, p.x, p.y)
            }
        }

        return Bitmap.createBitmap(dstPixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    private fun bilinear(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val safeX = x.coerceIn(0.0, (width - 1).toDouble())
        val safeY = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = safeX.toInt()
        val y0 = safeY.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = safeX - x0
        val fy = safeY - y0

        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]

        fun channel(shift: Int): Int {
            val c00 = (p00 ushr shift) and 0xff
            val c10 = (p10 ushr shift) and 0xff
            val c01 = (p01 ushr shift) and 0xff
            val c11 = (p11 ushr shift) and 0xff
            val top = c00 + (c10 - c00) * fx
            val bottom = c01 + (c11 - c01) * fx
            return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
        }

        val a = channel(24)
        val r = channel(16)
        val g = channel(8)
        val b = channel(0)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun replaceJpegSafely(target: File, bitmap: Bitmap): Boolean {
        val temp = File(target.parentFile, target.name + ".rectified.tmp")
        val backup = File(target.parentFile, target.name + ".original.tmp")
        temp.delete()
        backup.delete()

        return try {
            FileOutputStream(temp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) return false
                out.flush()
            }
            if (temp.length() <= 0L) return false

            if (!target.renameTo(backup)) return false
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                return false
            }
            backup.delete()
            true
        } catch (_: Exception) {
            temp.delete()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            false
        } finally {
            temp.delete()
            if (target.exists()) backup.delete()
        }
    }

    private const val MAX_SOURCE_EDGE = 2600
    private const val DETECTION_EDGE = 900
    private const val MAX_OUTPUT_EDGE = 2200.0
    private const val MAX_SLOPE = 0.34
    private const val SLOPE_STEP = 0.04
    private const val MIN_CONFIDENCE = 1.55
}
