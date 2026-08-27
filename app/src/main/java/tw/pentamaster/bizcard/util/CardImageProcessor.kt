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
 * Detects a business card, crops it, and rectifies perspective before OCR.
 *
 * The normal pass is tuned for a card that fills most of the camera guide. If that fails, a
 * second pass accepts a much smaller but strongly rectangular card. That coarse distant-card
 * result is used to crop a padded ROI, enlarge it, and run the strict detector again. This lets
 * gallery photos with a small card on a desk be corrected without broadly lowering the normal
 * detector's thresholds and increasing false crops.
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
    ) {
        val points: List<DPoint>
            get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private enum class DetectionMode { NORMAL, DISTANT }

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
            val srcQuad = quad.points.map { DPoint(it.x * sx, it.y * sy) }

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

    /**
     * Unlike [scaleToMaxEdge], this may enlarge a small ROI for a second, more accurate pass.
     * The scale cap prevents an extremely small false candidate from creating a huge bitmap.
     */
    private fun scaleForRefinement(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= 0) return source
        val wanted = REFINEMENT_EDGE.toDouble() / longest.toDouble()
        val scale = wanted.coerceIn(1.0, MAX_REFINEMENT_SCALE)
        if (scale <= 1.01) return source
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun detectCard(bitmap: Bitmap): Quad? {
        detectCandidate(bitmap, DetectionMode.NORMAL)?.let { return it }

        val coarse = detectCandidate(bitmap, DetectionMode.DISTANT) ?: return null
        val refined = refineDistantCandidate(bitmap, coarse)
        if (refined != null) return refined

        // A very strong coarse candidate is safer than leaving a clearly visible small card
        // uncropped. We still reject marginal distant candidates when refinement cannot confirm.
        return coarse.takeIf { it.confidence >= DISTANT_FALLBACK_CONFIDENCE }
    }

    private fun refineDistantCandidate(bitmap: Bitmap, coarse: Quad): Quad? {
        val xs = coarse.points.map { it.x }
        val ys = coarse.points.map { it.y }
        val rawMinX = xs.minOrNull() ?: return null
        val rawMaxX = xs.maxOrNull() ?: return null
        val rawMinY = ys.minOrNull() ?: return null
        val rawMaxY = ys.maxOrNull() ?: return null
        val cardWidth = rawMaxX - rawMinX
        val cardHeight = rawMaxY - rawMinY
        if (cardWidth < 40.0 || cardHeight < 24.0) return null

        val padX = cardWidth * DISTANT_ROI_PADDING
        val padY = cardHeight * DISTANT_ROI_PADDING
        val left = (rawMinX - padX).roundToInt().coerceIn(0, bitmap.width - 2)
        val top = (rawMinY - padY).roundToInt().coerceIn(0, bitmap.height - 2)
        val right = (rawMaxX + padX).roundToInt().coerceIn(left + 2, bitmap.width)
        val bottom = (rawMaxY + padY).roundToInt().coerceIn(top + 2, bitmap.height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth < 80 || cropHeight < 60) return null

        val crop = try {
            Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        } catch (_: Throwable) {
            return null
        }

        var refinedBitmap: Bitmap? = null
        return try {
            refinedBitmap = scaleForRefinement(crop)
            val local = detectCandidate(refinedBitmap, DetectionMode.NORMAL) ?: return null
            val sx = cropWidth.toDouble() / refinedBitmap.width.toDouble()
            val sy = cropHeight.toDouble() / refinedBitmap.height.toDouble()
            fun map(p: DPoint) = DPoint(left + p.x * sx, top + p.y * sy)
            Quad(
                topLeft = map(local.topLeft),
                topRight = map(local.topRight),
                bottomRight = map(local.bottomRight),
                bottomLeft = map(local.bottomLeft),
                confidence = max(coarse.confidence, local.confidence)
            )
        } finally {
            if (refinedBitmap != null && refinedBitmap !== crop) refinedBitmap.recycle()
            if (crop !== bitmap) crop.recycle()
        }
    }

    private fun detectCandidate(bitmap: Bitmap, mode: DetectionMode): Quad? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 180 || height < 140) return null

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

        val topRange = if (mode == DetectionMode.NORMAL) 0.14 to 0.48 else 0.06 to 0.49
        val bottomRange = if (mode == DetectionMode.NORMAL) 0.52 to 0.86 else 0.51 to 0.94
        val leftRange = if (mode == DetectionMode.NORMAL) 0.04 to 0.42 else 0.02 to 0.49
        val rightRange = if (mode == DetectionMode.NORMAL) 0.58 to 0.96 else 0.51 to 0.98

        val top = bestHorizontal(
            gradY, width, height,
            (height * topRange.first).roundToInt(),
            (height * topRange.second).roundToInt(),
            meanY
        )
        val bottom = bestHorizontal(
            gradY, width, height,
            (height * bottomRange.first).roundToInt(),
            (height * bottomRange.second).roundToInt(),
            meanY
        )
        val left = bestVertical(
            gradX, width, height,
            (width * leftRange.first).roundToInt(),
            (width * leftRange.second).roundToInt(),
            meanX
        )
        val right = bestVertical(
            gradX, width, height,
            (width * rightRange.first).roundToInt(),
            (width * rightRange.second).roundToInt(),
            meanX
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
        if (avgHeight < 1.0 || avgWidth < 1.0) return null

        val aspect = avgWidth / avgHeight
        val areaFraction = PerspectiveMath.polygonArea(points) / (width.toDouble() * height.toDouble())
        val widthFraction = avgWidth / width.toDouble()
        val heightFraction = avgHeight / height.toDouble()
        val widthBalance = min(topWidth, bottomWidth) / max(topWidth, bottomWidth)
        val heightBalance = min(leftHeight, rightHeight) / max(leftHeight, rightHeight)
        val centerX = points.sumOf { it.x } / 4.0 / width.toDouble()
        val centerY = points.sumOf { it.y } / 4.0 / height.toDouble()

        val marginX = width * 0.04
        val marginY = height * 0.04
        val allInside = points.all {
            it.x >= -marginX && it.x <= width + marginX &&
                it.y >= -marginY && it.y <= height + marginY
        }
        if (!allInside || tr.x <= tl.x || br.x <= bl.x || bl.y <= tl.y || br.y <= tr.y) {
            return null
        }

        val geometryOk = if (mode == DetectionMode.NORMAL) {
            aspect in 1.22..2.20 &&
                areaFraction in 0.14..0.78 &&
                widthFraction in 0.45..1.02 &&
                heightFraction in 0.18..0.78
        } else {
            // Smaller candidates must look more like a real rectangular business card before they
            // are allowed into the refinement pass.
            aspect in 1.28..2.12 &&
                areaFraction in 0.025..0.46 &&
                widthFraction in 0.20..0.82 &&
                heightFraction in 0.09..0.58 &&
                widthBalance >= 0.68 &&
                heightBalance >= 0.68 &&
                centerX in 0.12..0.88 &&
                centerY in 0.12..0.88
        }
        if (!geometryOk) return null

        val confidence = minOf(
            top.score / meanY,
            bottom.score / meanY,
            left.score / meanX,
            right.score / meanX
        )
        val requiredConfidence = if (mode == DetectionMode.NORMAL) {
            MIN_CONFIDENCE
        } else {
            DISTANT_MIN_CONFIDENCE
        }
        if (confidence < requiredConfidence) return null

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
    private const val REFINEMENT_EDGE = 900
    private const val MAX_REFINEMENT_SCALE = 3.0
    private const val DISTANT_ROI_PADDING = 0.24
    private const val MAX_OUTPUT_EDGE = 2200.0
    private const val MAX_SLOPE = 0.34
    private const val SLOPE_STEP = 0.04
    private const val MIN_CONFIDENCE = 1.55
    private const val DISTANT_MIN_CONFIDENCE = 1.70
    private const val DISTANT_FALLBACK_CONFIDENCE = 2.25
}
