package com.example.timeline

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import android.view.Surface
import android.view.WindowManager
import kotlin.math.*

class TrackingService : Service(), LocationListener, SensorEventListener {
    private val repository get() = (application as TimelineApplication).repository
    private val engine get() = repository.engine
    private lateinit var locations: LocationManager
    private lateinit var sensors: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var sensorsActive = false
    private var motionActive = false
    private var compassSensor: Sensor? = null
    private val motionDetector = MotionDetector()
    private val gravityRemover = GravityRemover()
    private val headingSmoother = HeadingSmoother()
    private val isWatch by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) }
    private var lastSave = 0L
    private var lastFix = 0L
    private var lastHeading = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            engine.tick(now)
            refreshSensors()
            val motion = motionDetector.state(now)
            engine.state = engine.state.copy(motion = motion, heldStill = engine.state.heldStill && motion == MotionState.STILL)
            if (lastFix != 0L && now - lastFix > 20_000) engine.state = engine.state.copy(position = null, awaitingFix = true, heldStill = false)
            if (lastHeading != 0L && now - lastHeading > 10_000) engine.state = engine.state.copy(heading = null)
            if (now - lastSave >= 5_000) { repository.save(); lastSave = now }
            repository.publish()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tracking", "경로 기록과 안내", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: STOP
        if (action == STOP) {
            engine.stop(SystemClock.elapsedRealtime()); repository.save(); finish(); return START_NOT_STICKY
        }
        if (action == PAUSE) {
            engine.togglePause(SystemClock.elapsedRealtime()); repository.save(); repository.publish()
            motionDetector.reset(); gravityRemover.reset(); refreshSensors()
            notifyStatus(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            repository.message("정확한 위치 권한을 허용해 주세요."); stopSelf(); return START_NOT_STICKY
        }
        if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            repository.message("기기의 GPS 위치 서비스를 켜 주세요."); stopSelf(); return START_NOT_STICKY
        }
        if (action == START && engine.state.mode != Mode.IDLE) return START_NOT_STICKY
        if (action == RETURN && engine.state.track.points.size < 2) {
            repository.message("경로를 먼저 기록해 주세요."); stopSelf(); return START_NOT_STICKY
        }
        try {
            startForeground(100, notification())
            if (action == START) engine.start(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            if (action == RETURN) { engine.beginReturn(SystemClock.elapsedRealtime()); repository.save() }
            engine.state = engine.state.copy(serviceRunning = true)
            lastFix = 0L; lastHeading = 0L
            // GPS timestamps are checked before accepting fixes; no cached location is used.
            locations.removeUpdates(this)
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (isWatch) 2000L else 1000L, 0f, this, Looper.getMainLooper())
            sensors.unregisterListener(this); sensorsActive = false; motionActive = false
            motionDetector.reset(); gravityRemover.reset(); headingSmoother.reset()
            refreshSensors()
            handler.removeCallbacks(ticker); handler.post(ticker)
            repository.publish(); notifyStatus()
        } catch (error: Exception) {
            engine.stop(SystemClock.elapsedRealtime()); repository.save()
            repository.message("위치 기록을 시작하지 못했습니다: ${error.message}"); finish()
        }
        return START_NOT_STICKY
    }
    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy() || abs(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) > 20_000_000_000L) return
        val now = SystemClock.elapsedRealtime()
        // Use only a conservative lower speed bound; missing speed is not evidence of rest.
        val speed = if (location.hasSpeed() && location.hasSpeedAccuracy() &&
            location.speedAccuracyMetersPerSecond.isFinite() && location.speedAccuracyMetersPerSecond >= 0f)
            (location.speed - location.speedAccuracyMetersPerSecond).toDouble() else null
        val accepted = engine.accept(TrackPoint(location.latitude, location.longitude, location.time, location.accuracy),
            System.currentTimeMillis(), location.elapsedRealtimeNanos / 1_000_000,
            motionDetector.state(now), speed)
        if (accepted) lastFix = SystemClock.elapsedRealtime()
        repository.publish()
        if (engine.state.mode == Mode.IDLE) finish()
    }
    override fun onProviderDisabled(provider: String) {
        engine.state = engine.state.copy(position = null, awaitingFix = true)
        repository.message("GPS 신호를 기다리는 중입니다. 위치 서비스를 확인해 주세요.")
    }
    override fun onProviderEnabled(provider: String) { repository.message(null) }
    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        val time = event.timestamp / 1_000_000
        // Do not feed gyro/acceleration arrays to getRotationMatrixFromVector.
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (!motionActive) return
                if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) motionDetector.reset()
                else motionDetector.gyroscope(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), time)
                return
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                if (!motionActive) return
                if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { motionDetector.reset(); gravityRemover.reset(); return }
                val linear = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION)
                    DoubleArray(3) { event.values[it].toDouble() }
                else gravityRemover.linear(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), time)
                linear?.let { motionDetector.acceleration(it[0], it[1], it[2], time) }
                return
            }
        }
        if (event.sensor != compassSensor || !sensorsActive) return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            headingSmoother.reset(); engine.state = engine.state.copy(heading = null); repository.publish(); return
        }
        val matrix = FloatArray(9); val remapped = FloatArray(9); val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        @Suppress("DEPRECATION") val rotation = getSystemService(WindowManager::class.java).defaultDisplay.rotation
        val axes = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val magnetic = Math.toDegrees(orientation[0].toDouble())
        val p = engine.state.position
        val declination = p?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() } ?: 0.0
        val heading = (magnetic + declination + 360) % 360
        engine.state = engine.state.copy(heading = headingSmoother.accept(heading, time))
        lastHeading = SystemClock.elapsedRealtime(); repository.publish()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) return
        if (sensor == compassSensor) {
            headingSmoother.reset(); engine.state = engine.state.copy(heading = null); repository.publish()
        } else motionDetector.reset()
    }
    private fun refreshSensors() {
        // Motion evidence must continue while the recording screen is hidden, unlike compass UI.
        val needMotion = engine.state.mode == Mode.RECORDING || engine.state.mode == Mode.RETURNING
        if (needMotion != motionActive) {
            motionActive = needMotion; motionDetector.reset(); gravityRemover.reset()
            val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val acceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (needMotion && gyro != null && acceleration != null) {
                // 10 Hz, unbatched: stale/batched samples never authorize stationary locking.
                sensors.registerListener(this, gyro, 100_000)
                sensors.registerListener(this, acceleration, 100_000)
            } else {
                gyro?.let { sensors.unregisterListener(this, it) }
                acceleration?.let { sensors.unregisterListener(this, it) }
            }
        }
        val wanted = repository.uiVisible
        if (wanted == sensorsActive) return
        sensorsActive = wanted
        headingSmoother.reset()
        if (wanted) {
            // Prefer OS gyro + accelerometer + magnetometer fusion. Never use game rotation
            // vector for compass north (its yaw reference drifts).
            compassSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            compassSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            compassSensor?.let { sensors.unregisterListener(this, it) }
            engine.state = engine.state.copy(heading = null)
        }
    }
    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Timeline")
            .setContentText(when (engine.state.mode) { Mode.RETURNING -> "지나온 경로로 돌아가는 중"; Mode.PAUSED -> "경로 기록 일시정지"; else -> "GPS 경로 기록 중" })
            .setOngoing(true).setContentIntent(launch).addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "종료", stop).build()).build()
    }
    private fun notifyStatus() { getSystemService(NotificationManager::class.java).notify(100, notification()) }
    private fun finish() {
        engine.state = engine.state.copy(serviceRunning = false, heading = null)
        repository.publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        locations.removeUpdates(this); sensors.unregisterListener(this)
        engine.stop(SystemClock.elapsedRealtime()); repository.save()
        engine.state = engine.state.copy(serviceRunning = false, heading = null)
        repository.publish(); super.onDestroy()
    }
    companion object {
        const val START = "com.example.timeline.START"
        const val STOP = "com.example.timeline.STOP"
        const val PAUSE = "com.example.timeline.PAUSE"
        const val RETURN = "com.example.timeline.RETURN"
    }
}
