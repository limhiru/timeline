package com.example.timeline

import java.util.UUID
import kotlin.math.*

data class TrackPoint(
    val latitude: Double, val longitude: Double, val timestamp: Long,
    val accuracy: Float, val segment: Int = 0
)
data class Track(
    val id: String = UUID.randomUUID().toString(), val started: Long = System.currentTimeMillis(),
    val points: List<TrackPoint> = emptyList(), val elapsedSeconds: Long = 0
) {
    val distance: Double get() = points.zipWithNext().sumOf { (a, b) -> if (a.segment == b.segment) distance(a, b) else 0.0 }
}
fun distance(a: TrackPoint, b: TrackPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val dLat = p2 - p1; val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLon / 2).pow(2)
    return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
fun bearing(a: TrackPoint, b: TrackPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val delta = Math.toRadians(b.longitude - a.longitude)
    return (Math.toDegrees(atan2(sin(delta) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(delta))) + 360) % 360
}
fun relativeAngle(bearing: Double, heading: Double) = (bearing - heading + 540) % 360 - 180

enum class Mode { IDLE, RECORDING, PAUSED, RETURNING }
data class TrackerState(
    val track: Track = Track(), val mode: Mode = Mode.IDLE,
    val position: TrackPoint? = null, val heading: Double? = null,
    val targetIndex: Int? = null, val awaitingFix: Boolean = false,
    val message: String? = null, val history: List<Track> = emptyList(), val serviceRunning: Boolean = false
) {
    val target: TrackPoint? get() = targetIndex?.let { track.points.getOrNull(it) }
    val targetDistance: Double? get() = target?.let { t -> position?.let { distance(it, t) } }
    val targetAngle: Double? get() = target?.let { t -> position?.let { p -> heading?.let { relativeAngle(bearing(p, t), it) } } }
}

/** Pure navigation/recording logic; service owns clocks and sensor lifetimes. */
class RouteEngine {
    var state = TrackerState()
    private var segment = 0
    private var anchorMillis: Long? = null
    private var baseSeconds = 0L
    fun start(now: Long, monotonicMillis: Long) {
        segment = 0; baseSeconds = 0; anchorMillis = monotonicMillis
        state = state.copy(track = Track(started = now), mode = Mode.RECORDING, position = null,
            targetIndex = null, awaitingFix = true, heading = null, message = null)
    }
    fun tick(monotonicMillis: Long) {
        if (state.mode == Mode.RECORDING) {
            val elapsed = baseSeconds + ((monotonicMillis - (anchorMillis ?: monotonicMillis)) / 1000).coerceAtLeast(0)
            state = state.copy(track = state.track.copy(elapsedSeconds = elapsed))
        }
    }
    fun togglePause(monotonicMillis: Long) {
        when (state.mode) {
            Mode.RECORDING -> { tick(monotonicMillis); baseSeconds = state.track.elapsedSeconds; anchorMillis = null; state = state.copy(mode = Mode.PAUSED) }
            Mode.PAUSED -> { segment++; anchorMillis = monotonicMillis; state = state.copy(mode = Mode.RECORDING) }
            else -> Unit
        }
    }
    fun stop(monotonicMillis: Long) {
        tick(monotonicMillis); anchorMillis = null
        state = state.copy(mode = Mode.IDLE, targetIndex = null, awaitingFix = false)
    }
    fun beginReturn(monotonicMillis: Long) {
        if (state.track.points.size < 2) { state = state.copy(message = "경로를 먼저 기록해 주세요."); return }
        tick(monotonicMillis); anchorMillis = null
        // Wait for a new GPS fix rather than navigating from an old saved position.
        state = state.copy(mode = Mode.RETURNING, targetIndex = null, position = null, awaitingFix = true, message = null)
    }
    fun accept(point: TrackPoint, now: Long): Boolean {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0 ||
            !point.accuracy.isFinite() || point.accuracy !in 0f..30f || abs(now - point.timestamp) > 20_000) return false
        state = state.copy(position = point, awaitingFix = false)
        if (state.mode == Mode.RETURNING) {
            var index = state.targetIndex ?: state.track.points.indices.minWithOrNull(compareBy<Int> { distance(point, state.track.points[it]) }.thenByDescending { it }) ?: return true
            val threshold = point.accuracy.toDouble().coerceIn(8.0, 15.0)
            while (distance(point, state.track.points[index]) <= threshold) {
                if (index == 0) {
                    state = state.copy(mode = Mode.IDLE, targetIndex = null, message = "출발점에 도착했습니다.")
                    return true
                }
                index--
            }
            state = state.copy(targetIndex = index)
        }
        if (state.mode != Mode.RECORDING) return true
        val last = state.track.points.lastOrNull()
        if (last != null) {
            if (point.timestamp <= last.timestamp) return true
            if (last.segment == segment) {
                val meters = distance(last, point); val seconds = (point.timestamp - last.timestamp) / 1000.0
                if (meters < 3 || meters / seconds > 12) return true
            }
        }
        state = state.copy(track = state.track.copy(points = state.track.points + point.copy(segment = segment)))
        return true
    }
    fun select(track: Track) {
        if (state.mode == Mode.IDLE) state = state.copy(track = track, position = null, targetIndex = null, heading = null)
    }
}
