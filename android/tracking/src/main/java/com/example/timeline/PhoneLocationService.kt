package com.example.timeline

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import com.google.android.gms.wearable.*
import java.util.UUID

private data class LivePhoneFix(val point: TrackPoint, val sampleMillis: Long, val sequence: Long,
                              val session: String, val motion: MotionState, val speed: Double?)
private object PhoneLocationFeed {
    val lease = LocationSharingLease()
    @Volatile var fix: LivePhoneFix? = null
    fun reply(now: Long): WirePhoneFix? {
        if (!lease.needsGps(now)) return null
        val live = fix ?: return null
        val age = now - live.sampleMillis
        if (age !in 0..20_000) return null
        return WirePhoneFix(live.session, live.sequence, live.point.latitude, live.point.longitude,
            live.point.accuracy, age, live.motion, live.speed)
    }
}

/** Started only by the phone activity with the user's location permission and explicit switch. */
class PhoneLocationService : Service(), LocationListener, SensorEventListener {
    private val repository get() = (application as TimelineApplication).repository
    private lateinit var locations: LocationManager
    private lateinit var sensors: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val detector = MotionDetector()
    private val gravity = GravityRemover()
    private val session = UUID.randomUUID().toString()
    private var sequence = 0L
    private var sampling = false
    private var lastSample = -1L
    private val ticker = object : Runnable {
        override fun run() {
            refreshSampling(); handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("watch-location", "워치 위치 공유", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            repository.message("워치 위치 공유에는 폰의 정확한 위치 권한이 필요합니다."); stopSelf(); return START_NOT_STICKY
        }
        try {
            val launch = PendingIntent.getActivity(this, 200, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 201, Intent(this, PhoneLocationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            startForeground(200, Notification.Builder(this, "watch-location").setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Timeline · 워치 위치 공유")
                .setContentText("연결된 워치가 폰 위치를 요청하면 GPS를 사용합니다.")
                .setContentIntent(launch).setOngoing(true)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "공유 끄기", stop).build()).build())
            PhoneLocationFeed.lease.enable(SystemClock.elapsedRealtime())
            repository.engine.state = repository.engine.state.copy(phoneSharing = true); repository.publish()
            handler.removeCallbacks(ticker); handler.post(ticker)
        } catch (error: Exception) { repository.message("워치 위치 공유를 시작하지 못했습니다: ${error.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun refreshSampling() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSampling(); stopSelf(); return
        }
        val wanted = PhoneLocationFeed.lease.needsGps(SystemClock.elapsedRealtime())
        if (wanted == sampling) return
        if (!wanted) { stopSampling(); return }
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            sampling = true; lastSample = -1
            val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val acceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (gyro != null && acceleration != null) {
                sensors.registerListener(this, gyro, 100_000); sensors.registerListener(this, acceleration, 100_000)
            }
        } catch (error: Exception) {
            stopSampling(); repository.message("폰 GPS 공유를 사용할 수 없습니다: ${error.message}"); stopSelf()
        }
    }
    private fun stopSampling() {
        locations.removeUpdates(this); sensors.unregisterListener(this); sampling = false
        PhoneLocationFeed.fix = null; detector.reset(); gravity.reset()
    }
    override fun onLocationChanged(location: Location) {
        if (!sampling || !location.hasAccuracy()) return
        val now = SystemClock.elapsedRealtime(); val sample = location.elapsedRealtimeNanos / 1_000_000
        if (now - sample !in 0..20_000 || sample <= lastSample || !location.accuracy.isFinite() || location.accuracy !in 0f..10_000f ||
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return
        lastSample = sample
        val speed = if (location.hasSpeed() && location.hasSpeedAccuracy() && location.speed.isFinite() &&
            location.speedAccuracyMetersPerSecond.isFinite() && location.speedAccuracyMetersPerSecond >= 0f)
            (location.speed - location.speedAccuracyMetersPerSecond).toDouble().coerceIn(0.0, 100.0) else null
        PhoneLocationFeed.fix = LivePhoneFix(TrackPoint(location.latitude, location.longitude, location.time, location.accuracy),
            sample, ++sequence, session, detector.state(now), speed)
    }
    override fun onProviderDisabled(provider: String) { PhoneLocationFeed.fix = null }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        if (!sampling) return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { detector.reset(); gravity.reset(); return }
        val time = event.timestamp / 1_000_000
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE)
            detector.gyroscope(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), time)
        else {
            val linear = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) DoubleArray(3) { event.values[it].toDouble() }
                else gravity.linear(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), time)
            linear?.let { detector.acceleration(it[0], it[1], it[2], time) }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) detector.reset() }
    override fun onDestroy() {
        PhoneLocationFeed.lease.disable(); handler.removeCallbacksAndMessages(null); stopSampling()
        repository.engine.state = repository.engine.state.copy(phoneSharing = false); repository.publish()
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    companion object { const val START = "com.example.timeline.SHARE_LOCATION_START"; const val STOP = "com.example.timeline.SHARE_LOCATION_STOP" }
}

/** Same package and signing certificate are enforced by the Wear Data Layer. */
class PhoneLocationListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PhoneLocationProtocol.REQUEST_PATH) return
        val token = PhoneLocationProtocol.readRequest(event.data) ?: return
        val from = event.sourceNodeId
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.none { it.id == from && it.isNearby }) return@addOnSuccessListener
            val now = SystemClock.elapsedRealtime()
            // Requests renew a lease, but cannot enable sharing or start a phone FGS.
            PhoneLocationFeed.lease.request(now)
            val permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val bytes = PhoneLocationProtocol.reply(token, if (permitted) PhoneLocationFeed.reply(now) else null)
            Wearable.getMessageClient(this).sendMessage(from, PhoneLocationProtocol.REPLY_PATH, bytes)
        }
    }
}
