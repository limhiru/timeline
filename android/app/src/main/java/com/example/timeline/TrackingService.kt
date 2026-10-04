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
    private var headingAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var lastSave = 0L
    private var lastFix = 0L
    private var lastHeading = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            engine.tick(now)
            if (lastFix != 0L && now - lastFix > 20_000) engine.state = engine.state.copy(position = null, awaitingFix = true)
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
            // GPS timestamps are checked before accepting fixes; no cached location is used.
            locations.removeUpdates(this)
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            sensors.unregisterListener(this)
            sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
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
        val accepted = engine.accept(TrackPoint(location.latitude, location.longitude, location.time, location.accuracy), System.currentTimeMillis())
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
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            engine.state = engine.state.copy(heading = null); repository.publish(); return
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
        val old = engine.state.heading
        engine.state = engine.state.copy(heading = if (old == null) heading else (old + relativeAngle(heading, old) * 0.2 + 360) % 360)
        lastHeading = SystemClock.elapsedRealtime(); repository.publish()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { headingAccuracy = accuracy }
    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
