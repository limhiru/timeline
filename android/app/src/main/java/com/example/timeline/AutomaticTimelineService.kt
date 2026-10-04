package com.example.timeline

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import kotlin.math.abs

/** User-started location FGS. No boot receiver, hidden restart or background permission. */
class AutomaticTimelineService : Service(), LocationListener, SensorEventListener {
    companion object {
        const val START = "com.example.timeline.AUTO_START"
        const val STOP = "com.example.timeline.AUTO_STOP"
        internal var active: AutomaticTimelineService? = null
            private set
    }
    private val repository by lazy { AutomaticTimelineRepository.get(this) }
    private lateinit var locations: LocationManager
    private lateinit var sensors: SensorManager
    private lateinit var alarms: AlarmManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alarmIntent: PendingIntent
    private val handler = Handler(Looper.getMainLooper())
    private val policy = AdaptiveLocationPolicy()
    private val filter = LocationStabilizer()
    private val motion = MotionDetector()
    private val pulses = MovementPulseDetector()
    private val gravity = GravityRemover()
    private var enabled = false
    private var gpsInterval: Long? = null
    private var burstUntil: Long? = null
    private var breakSegment = true
    private var wasMoving = false
    private var significant: Sensor? = null
    private var armed = false
    private var stepActive = false
    private var accelerationActive = false
    private var acceleration: Sensor? = null
    private var gyro: Sensor? = null
    private var lastNotify = 0L
    private val ticker = Runnable { advance() }
    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            armed = false
            if (enabled && fresh(event.timestamp)) movement(SystemClock.elapsedRealtime())
            if (enabled) advance()
        }
    }
    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java); sensors = getSystemService(SensorManager::class.java)
        alarms = getSystemService(AlarmManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Timeline:checkpoint").apply { setReferenceCounted(false) }
        alarmIntent = PendingIntent.getBroadcast(this, 40, Intent(this, TimelineAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("automatic-timeline", "일별 타임라인 자동 기록", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (enabled) return START_NOT_STICKY
        try {
            startForeground(140, notification())
            check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "정확한 위치 권한을 허용해 주세요." }
            check(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "기기의 위치 서비스를 켜 주세요." }
            enabled = true; active = this
            policy.start(SystemClock.elapsedRealtime())
            significant = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            acceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val activityAllowed = Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            if (activityAllowed) {
                val step = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true) ?: sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                stepActive = step?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) } == true
            }
            repository.update { it.copy(status = null) }
            advance()
        } catch (error: Exception) {
            repository.update { it.copy(status = "자동 기록을 시작하지 못했습니다: ${error.message}") }; stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun fresh(timestampNanos: Long) = SystemClock.elapsedRealtimeNanos() - timestampNanos in 0..10_000_000_000L
    private fun movement(now: Long) {
        if (!enabled) return
        val alreadyMoving = policy.moving(now)
        policy.movement(now)
        if (!alreadyMoving) advance()
    }
    /** Alarm receiver only wakes an ALREADY running session; never starts location secretly. */
    internal fun advance() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            repository.update { it.copy(status = "위치 권한이 해제되어 자동 기록을 종료했습니다.") }; stopSelf(); return
        }
        policy.update(now)
        val moving = policy.moving(now)
        if (moving != wasMoving) {
            stopGps(); filter.reset(); motion.reset(); gravity.reset(); breakSegment = true
            wasMoving = moving; lastNotify = 0
        }
        if (moving) requestGps(AdaptiveLocationPolicy.MOVING_INTERVAL)
        else {
            if (burstUntil?.let { now >= it } == true) {
                policy.checkpointFinished(now); stopGps()
                repository.update { it.copy(status = "이번 위치 확인에서 정확한 GPS 신호를 얻지 못했습니다. 다음 주기에 다시 확인합니다.") }
            }
            if (gpsInterval == null && policy.checkpointDue(now)) {
                filter.reset(); breakSegment = true
                burstUntil = now + AdaptiveLocationPolicy.FIX_TIMEOUT
                wakeLock.acquire(AdaptiveLocationPolicy.FIX_TIMEOUT + 15_000)
                requestGps(1000)
            }
        }
        refreshSensors(moving)
        val next = burstUntil ?: policy.nextDeadline(now)
        repository.update { it.copy(enabled = true, moving = moving, acquiring = gpsInterval != null,
            nextCheck = System.currentTimeMillis() + (if (moving) AdaptiveLocationPolicy.MOVING_INTERVAL else next - now).coerceAtLeast(0)) }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, (next - now).coerceAtLeast(1000))
        // Inexact, allow-while-idle: no exact-alarm permission, Doze may postpone delivery.
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next.coerceAtLeast(now + 1000), alarmIntent)
        if (now - lastNotify >= 15_000 || lastNotify == 0L) {
            getSystemService(NotificationManager::class.java).notify(140, notification()); lastNotify = now
        }
    }
    private fun requestGps(interval: Long) {
        if (gpsInterval == interval) return
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("정확한 위치 권한이 해제되었습니다.")
            }
            locations.removeUpdates(this)
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, this, Looper.getMainLooper())
            gpsInterval = interval
        } catch (error: Exception) {
            stopGps(); policy.checkpointFinished(SystemClock.elapsedRealtime())
            repository.update { it.copy(status = "GPS를 사용할 수 없습니다: ${error.message}") }
        }
    }
    private fun stopGps() {
        locations.removeUpdates(this); gpsInterval = null; burstUntil = null
        if (wakeLock.isHeld) wakeLock.release()
    }
    private fun refreshSensors(moving: Boolean) {
        if (!moving && !armed) armed = significant?.let { sensors.requestTriggerSensor(trigger, it) } == true
        if (moving && armed) { sensors.cancelTriggerSensor(trigger, significant); armed = false }
        val needAcceleration = moving || (!armed && !stepActive)
        if (needAcceleration != accelerationActive) {
            motion.reset(); pulses.reset(); gravity.reset()
            if (needAcceleration) {
                accelerationActive = acceleration?.let { sensors.registerListener(this, it, 100_000, handler) } == true
                if (accelerationActive) gyro?.let { sensors.registerListener(this, it, 100_000, handler) }
            } else {
                acceleration?.let { sensors.unregisterListener(this, it) }; gyro?.let { sensors.unregisterListener(this, it) }
                accelerationActive = false
            }
        }
        repository.update { it.copy(detection = when {
            armed || stepActive || (moving && significant != null) -> "저전력 이동·걸음 센서"
            accelerationActive -> "가속도 센서 대체 감지 · 배터리 사용 증가"
            else -> "이동 센서 없음 · 30분 주기만 사용"
        }) }
    }
    override fun onLocationChanged(location: Location) {
        if (!enabled || gpsInterval == null || !location.hasAccuracy() ||
            abs(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) > 20_000_000_000L ||
            abs(System.currentTimeMillis() - location.time) > 20_000) return
        val now = SystemClock.elapsedRealtime()
        val lowerSpeed = if (location.hasSpeed() && location.hasSpeedAccuracy() && location.speedAccuracyMetersPerSecond.isFinite() && location.speedAccuracyMetersPerSecond >= 0)
            (location.speed - location.speedAccuracyMetersPerSecond).toDouble() else null
        // Only accepted GPS fixes can extend movement, not a noisy or rejected jump.
        val fix = filter.accept(TrackPoint(location.latitude, location.longitude, location.time, location.accuracy),
            location.elapsedRealtimeNanos / 1_000_000, motion.state(now), lowerSpeed) ?: return
        if (lowerSpeed?.let { it.isFinite() && it > .6 } == true && location.accuracy <= 20) {
            val alreadyMoving = policy.moving(now)
            policy.movement(now)
            if (!alreadyMoving) { breakSegment = true; wasMoving = true; burstUntil = null; if (wakeLock.isHeld) wakeLock.release() }
        }
        val moving = policy.moving(now)
        val added = repository.append(fix.point, moving, breakSegment || fix.gap, fix.heldStill)
        if (added) breakSegment = false
        repository.update { it.copy(status = null) }
        if (!moving) { policy.checkpointFinished(now); stopGps() }
        advance()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled || !fresh(event.timestamp)) return
        val time = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> if (event.values.firstOrNull()?.let { it > 0 } == true) movement(SystemClock.elapsedRealtime())
            Sensor.TYPE_GYROSCOPE -> if (accelerationActive) {
                if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) motion.reset()
                else motion.gyroscope(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), time)
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                if (!accelerationActive) return
                if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { motion.reset(); gravity.reset(); pulses.reset(); return }
                val values = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) DoubleArray(3) { event.values[it].toDouble() }
                    else gravity.linear(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(), time)
                values?.let {
                    motion.acceleration(it[0], it[1], it[2], time)
                    if (pulses.acceleration(it[0], it[1], it[2], time)) movement(SystemClock.elapsedRealtime())
                }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { motion.reset(); pulses.reset(); gravity.reset() }
    }
    override fun onProviderDisabled(provider: String) {
        repository.update { it.copy(status = "기기의 위치 서비스를 켜 주세요. GPS 신호를 기다립니다.") }
    }
    override fun onProviderEnabled(provider: String) { repository.update { it.copy(status = null) } }
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java).putExtra("timeline_home", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 41, Intent(this, AutomaticTimelineService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "automatic-timeline").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Timeline · 자동 기록").setContentText(if (wasMoving) "이동 감지 · 약 10초마다 기록" else "기본 약 30분 · 움직이면 빠르게 기록")
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "기록 종료", stop).build()).build()
    }
    override fun onDestroy() {
        enabled = false; if (active === this) active = null
        handler.removeCallbacksAndMessages(null); alarms.cancel(alarmIntent)
        stopGps(); sensors.unregisterListener(this); sensors.cancelTriggerSensor(trigger, significant)
        repository.update { it.copy(enabled = false, moving = false, acquiring = false, nextCheck = null) }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}

class TimelineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AutomaticTimelineService.active?.advance() }
}
