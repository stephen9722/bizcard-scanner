package tw.pentamaster.bizcard.util

import kotlin.math.hypot

/** Lightweight geometry used by the card rectifier. Kept Android-free so it is unit-testable. */
data class DPoint(val x: Double, val y: Double)

data class ProjectiveMap(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val e: Double,
    private val f: Double,
    private val g: Double,
    private val h: Double
) {
    /** Maps normalized destination coordinates (u, v), both 0..1, into source pixels. */
    fun map(u: Double, v: Double): DPoint {
        val denominator = g * u + h * v + 1.0
        if (kotlin.math.abs(denominator) < 1e-9) return DPoint(Double.NaN, Double.NaN)
        return DPoint(
            x = (a * u + b * v + c) / denominator,
            y = (d * u + e * v + f) / denominator
        )
    }
}

object PerspectiveMath {

    /**
     * Builds a projective transform from a unit square into the quadrilateral TL, TR, BR, BL.
     * This is the inverse mapping we want for rasterization: each output pixel asks which source
     * pixel it should sample, so no holes appear in the rectified bitmap.
     */
    fun unitSquareToQuad(
        topLeft: DPoint,
        topRight: DPoint,
        bottomRight: DPoint,
        bottomLeft: DPoint
    ): ProjectiveMap? {
        val x0 = topLeft.x
        val y0 = topLeft.y
        val x1 = topRight.x
        val y1 = topRight.y
        val x2 = bottomRight.x
        val y2 = bottomRight.y
        val x3 = bottomLeft.x
        val y3 = bottomLeft.y

        val dx3 = x0 - x1 + x2 - x3
        val dy3 = y0 - y1 + y2 - y3

        val g: Double
        val h: Double
        if (kotlin.math.abs(dx3) < 1e-9 && kotlin.math.abs(dy3) < 1e-9) {
            g = 0.0
            h = 0.0
        } else {
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            if (kotlin.math.abs(det) < 1e-9) return null
            g = (dx3 * dy2 - dx2 * dy3) / det
            h = (dx1 * dy3 - dx3 * dy1) / det
        }

        return ProjectiveMap(
            a = x1 - x0 + g * x1,
            b = x3 - x0 + h * x3,
            c = x0,
            d = y1 - y0 + g * y1,
            e = y3 - y0 + h * y3,
            f = y0,
            g = g,
            h = h
        )
    }

    fun distance(a: DPoint, b: DPoint): Double = hypot(a.x - b.x, a.y - b.y)

    fun polygonArea(points: List<DPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            sum += p.x * q.y - q.x * p.y
        }
        return kotlin.math.abs(sum) * 0.5
    }
}
