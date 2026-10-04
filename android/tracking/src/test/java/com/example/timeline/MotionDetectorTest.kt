package com.example.timeline

import org.junit.Assert.*
import org.junit.Test

class MotionDetectorTest {
    private fun quiet(detector: MotionDetector, from: Long, to: Long) {
        for (time in from..to step 100) {
            detector.gyroscope(.01, 0.0, 0.0, time)
            detector.acceleration(.03, 0.0, 0.0, time)
        }
    }
    @Test fun requiresBothFreshSensorsAndFourSecondsOfQuiet() {
        val detector = MotionDetector()
        assertEquals(MotionState.UNKNOWN, detector.state(0))
        quiet(detector, 0, 3900)
        assertEquals(MotionState.UNKNOWN, detector.state(3900))
        quiet(detector, 4000, 4100)
        assertEquals(MotionState.STILL, detector.state(4100))
        assertEquals(MotionState.UNKNOWN, detector.state(6000))
    }
    @Test fun gyroAloneCannotProveStillness() {
        val detector = MotionDetector()
        for (time in 0L..10_000L step 100) detector.gyroscope(0.0, 0.0, 0.0, time)
        assertEquals(MotionState.UNKNOWN, detector.state(10_000))
    }
    @Test fun eitherRotationOrAccelerationImmediatelyUnlocks() {
        val detector = MotionDetector(); quiet(detector, 0, 4100)
        detector.gyroscope(.3, 0.0, 0.0, 4200)
        assertEquals(MotionState.MOVING, detector.state(4200))
        quiet(detector, 4300, 8400)
        assertEquals(MotionState.STILL, detector.state(8400))
        detector.acceleration(.6, 0.0, 0.0, 8500)
        assertEquals(MotionState.MOVING, detector.state(8500))
    }
    @Test fun staleAndInvalidSamplesNeverAuthorizeLock() {
        val detector = MotionDetector(); quiet(detector, 0, 4100)
        quiet(detector, 8000, 8100)
        assertEquals(MotionState.UNKNOWN, detector.state(8100))
        detector.gyroscope(Double.NaN, 0.0, 0.0, 8200)
        assertEquals(MotionState.UNKNOWN, detector.state(8200))
        detector.reset(); assertEquals(MotionState.UNKNOWN, detector.state(8200))
    }
    @Test fun gravityFallbackWarmsUpAndDetectsMotion() {
        val gravity = GravityRemover()
        assertNull(gravity.linear(0.0, 0.0, 9.81, 0))
        for (time in 100L..2900L step 100) assertNull(gravity.linear(0.0, 0.0, 9.81, time))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), gravity.linear(0.0, 0.0, 9.81, 3000), .001)
        assertTrue(gravity.linear(1.0, 0.0, 9.81, 3100)!![0] > .8)
        assertNull(gravity.linear(0.0, 0.0, 9.81, 6000))
    }
    @Test fun headingWrapAndRateIndependentSmoothing() {
        val heading = HeadingSmoother(); heading.accept(359.0, 0)
        val value = heading.accept(1.0, 100)!!
        assertTrue(value > 359 || value < 1)
        heading.reset(); assertEquals(180.0, heading.accept(180.0, 100)!!, .001)
        assertEquals(180.0, heading.accept(200.0, 99)!!, .001)
        assertEquals(90.0, heading.accept(90.0, 4000)!!, .001)
        assertNull(heading.accept(Double.NaN, 5000))
    }
}
