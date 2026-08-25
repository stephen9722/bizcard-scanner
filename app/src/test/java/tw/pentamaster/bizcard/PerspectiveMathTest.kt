package tw.pentamaster.bizcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tw.pentamaster.bizcard.util.DPoint
import tw.pentamaster.bizcard.util.PerspectiveMath

class PerspectiveMathTest {

    @Test
    fun rectangleMapsAllFourCorners() {
        val map = PerspectiveMath.unitSquareToQuad(
            DPoint(10.0, 20.0),
            DPoint(210.0, 20.0),
            DPoint(210.0, 120.0),
            DPoint(10.0, 120.0)
        )
        assertNotNull(map)

        assertPoint(DPoint(10.0, 20.0), map!!.map(0.0, 0.0))
        assertPoint(DPoint(210.0, 20.0), map.map(1.0, 0.0))
        assertPoint(DPoint(210.0, 120.0), map.map(1.0, 1.0))
        assertPoint(DPoint(10.0, 120.0), map.map(0.0, 1.0))
        assertPoint(DPoint(110.0, 70.0), map.map(0.5, 0.5))
    }

    @Test
    fun trapezoidStillMapsAllFourCornersExactly() {
        val tl = DPoint(40.0, 30.0)
        val tr = DPoint(250.0, 50.0)
        val br = DPoint(225.0, 165.0)
        val bl = DPoint(25.0, 145.0)
        val map = PerspectiveMath.unitSquareToQuad(tl, tr, br, bl)
        assertNotNull(map)

        assertPoint(tl, map!!.map(0.0, 0.0))
        assertPoint(tr, map.map(1.0, 0.0))
        assertPoint(br, map.map(1.0, 1.0))
        assertPoint(bl, map.map(0.0, 1.0))
    }

    @Test
    fun polygonAreaIsStableForClockwiseOrCounterClockwiseInput() {
        val clockwise = listOf(
            DPoint(0.0, 0.0), DPoint(4.0, 0.0), DPoint(4.0, 3.0), DPoint(0.0, 3.0)
        )
        val counterClockwise = clockwise.reversed()
        assertEquals(12.0, PerspectiveMath.polygonArea(clockwise), 1e-9)
        assertEquals(12.0, PerspectiveMath.polygonArea(counterClockwise), 1e-9)
    }

    private fun assertPoint(expected: DPoint, actual: DPoint) {
        assertEquals(expected.x, actual.x, 1e-6)
        assertEquals(expected.y, actual.y, 1e-6)
    }
}
