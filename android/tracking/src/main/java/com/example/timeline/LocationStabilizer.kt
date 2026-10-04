package com.example.timeline

import kotlin.math.*

enum class MotionState { UNKNOWN, MOVING, STILL }

/** Conservative stillness evidence, not inertial position/dead reckoning. Times are monotonic. */
class MotionDetector {
    private var gyroTime: Long? = null
    private var accelerationTime: Long? = null
    private var quietSince: Long? = null
    private var gyroQuiet = false
    private var accelerationQuiet = false

    fun reset() {
        gyroTime = null; accelerationTime = null; quietSince = null
        gyroQuiet = false; accelerationQuiet = false
    }
    fun gyroscope(x: Double, y: Double, z: Double, time: Long) {
        if (!valid(x, y, z)) { reset(); return }
        if (gyroTime?.let { time <= it } == true) return
        if (gyroTime?.let { time - it > 1500 } == true) quietSince = null
        gyroTime = time; gyroQuiet = sqrt(x * x + y * y + z * z) < 0.08
        update(time)
    }
    fun acceleration(x: Double, y: Double, z: Double, time: Long) {
        if (!valid(x, y, z)) { reset(); return }
        if (accelerationTime?.let { time <= it } == true) return
        if (accelerationTime?.let { time - it > 1500 } == true) quietSince = null
        accelerationTime = time; accelerationQuiet = sqrt(x * x + y * y + z * z) < 0.18
        update(time)
    }
    private fun valid(x: Double, y: Double, z: Double) = x.isFinite() && y.isFinite() && z.isFinite()
    private fun fresh(now: Long) = listOf(gyroTime, accelerationTime).all { it != null && now - it in 0..1500 }
    private fun update(now: Long) {
        if (!fresh(now) || !gyroQuiet || !accelerationQuiet) quietSince = null
        else if (quietSince == null) quietSince = now
    }
    fun state(now: Long): MotionState = when {
        !fresh(now) -> MotionState.UNKNOWN
        !gyroQuiet || !accelerationQuiet -> MotionState.MOVING
        quietSince?.let { now - it >= 4000 } == true -> MotionState.STILL
        else -> MotionState.UNKNOWN
    }
}

/** Gravity low-pass fallback for devices without TYPE_LINEAR_ACCELERATION. */
class GravityRemover {
    private var gravity: DoubleArray? = null
    private var lastTime: Long? = null
    private var started: Long? = null
    fun reset() { gravity = null; lastTime = null; started = null }
    fun linear(x: Double, y: Double, z: Double, time: Long): DoubleArray? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) { reset(); return null }
        val values = doubleArrayOf(x, y, z)
        val previous = lastTime
        if (previous != null && time <= previous) return null
        if (previous == null || time - previous > 1500) { gravity = values; started = time; lastTime = time; return null }
        val alpha = exp(-(time - previous) / 800.0)
        val g = gravity!!
        for (i in 0..2) g[i] = alpha * g[i] + (1 - alpha) * values[i]
        lastTime = time
        return if (time - started!! >= 3000) DoubleArray(3) { values[it] - g[it] } else null
    }
}

data class StabilizedFix(val point: TrackPoint, val gap: Boolean = false, val heldStill: Boolean = false)

/** Accuracy-weighted 2D random-walk Kalman filter. GPS is always the absolute reference.
 * No integration of acceleration/gyro into distance. Output accuracy remains conservative.
 */
class LocationStabilizer {
    private var estimate: TrackPoint? = null
    private var raw: TrackPoint? = null
    private var acceptedTime: Long? = null
    private var seenTime: Long? = null
    private var variance = 0.0
    private var reacquisition: Pair<TrackPoint, Long>? = null
    fun reset() { estimate = null; raw = null; acceptedTime = null; seenTime = null; variance = 0.0; reacquisition = null }

    fun accept(point: TrackPoint, time: Long, motion: MotionState = MotionState.UNKNOWN,
               reliableSpeed: Double? = null): StabilizedFix? {
        if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0 ||
            !point.accuracy.isFinite() || point.accuracy !in 0f..30f || time < 0) return null
        if (seenTime?.let { time <= it } == true) return null
        seenTime = time
        val old = estimate
        if (old == null) return if (point.accuracy <= 20f) seed(point, time, false) else null
        val dt = (time - acceptedTime!!) / 1000.0
        if (dt > 20) {
            // After an outage, corroborate two good fixes before re-anchoring, never draw a gap.
            if (point.accuracy > 20f) { reacquisition = null; return null }
            val candidate = reacquisition
            reacquisition = point to time
            if (candidate == null || time - candidate.second !in 1000..10_000 ||
                distance(candidate.first, point) > 12 * (time - candidate.second) / 1000.0 + 10) return null
            return seed(point, time, true)
        }
        val meters = distance(old, point)
        val uncertainty = sqrt(raw!!.accuracy.toDouble().pow(2) + point.accuracy.toDouble().pow(2))
        val rawSpeed = distance(raw!!, point) / dt
        // Reject before exposing a position or advancing reverse-route waypoints.
        if (rawSpeed > 12 && meters > max(20.0, uncertainty * 2)) return null
        val movingByGps = reliableSpeed?.let { it.isFinite() && it > 0.6 } == true
        if (motion == MotionState.STILL && !movingByGps && point.accuracy >= old.accuracy * 0.7) {
            // A large contradiction is not proof of translation. Wait for motion evidence,
            // reliable speed, better accuracy, or the corroborated outage re-acquisition.
            if (meters > max(8.0, uncertainty * 1.5)) return null
            acceptedTime = time; raw = point
            val held = old.copy(timestamp = point.timestamp, accuracy = max(old.accuracy, point.accuracy))
            estimate = held
            return StabilizedFix(held, heldStill = true)
        }
        val predictedVariance = variance + 16.0 * dt // m², walking/running responsiveness
        val measurementVariance = max(3.0, point.accuracy.toDouble()).pow(2)
        val gain = predictedVariance / (predictedVariance + measurementVariance)
        val deltaLon = (point.longitude - old.longitude + 540) % 360 - 180
        variance = (1 - gain) * predictedVariance
        val filtered = point.copy(latitude = old.latitude + gain * (point.latitude - old.latitude),
            longitude = (old.longitude + gain * deltaLon + 540) % 360 - 180,
            accuracy = max(point.accuracy, sqrt(variance).toFloat()))
        estimate = filtered; raw = point; acceptedTime = time; reacquisition = null
        return StabilizedFix(filtered)
    }
    private fun seed(point: TrackPoint, time: Long, gap: Boolean): StabilizedFix {
        estimate = point; raw = point; acceptedTime = time; variance = max(3.0, point.accuracy.toDouble()).pow(2)
        reacquisition = null
        return StabilizedFix(point, gap)
    }
}

/** Shortest-angle, time-aware smoothing: 359° -> 1° never turns through south. */
class HeadingSmoother {
    private var heading: Double? = null
    private var lastTime: Long? = null
    fun reset() { heading = null; lastTime = null }
    fun accept(degrees: Double, time: Long): Double? {
        if (!degrees.isFinite()) return null
        if (lastTime?.let { time <= it } == true) return heading
        val normalized = (degrees % 360 + 360) % 360
        val dt = lastTime?.let { time - it }
        val old = heading
        heading = if (old == null || dt == null || dt > 2000) normalized
        else (old + relativeAngle(normalized, old) * (1 - exp(-dt / 150.0)) + 360) % 360
        lastTime = time
        return heading
    }
}
