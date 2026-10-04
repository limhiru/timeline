package com.example.timeline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val repository get() = (application as TimelineApplication).repository
    private val automatic by lazy { AutomaticTimelineRepository.get(this) }
    private var pendingAction: String? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingAction; pendingAction = null
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action?.let { runService(it) }
        } else repository.message("경로 기록에는 정확한 위치 권한이 필요합니다. 설정에서 위치 권한과 ‘정확한 위치’를 허용해 주세요.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by repository.state.collectAsState()
            val automaticState by automatic.state.collectAsState()
            PhoneShell(state, automaticState, getSharedPreferences("phone-ui", MODE_PRIVATE),
                intent.getBooleanExtra("timeline_home", false), ::command, { repository.message(null) }) {
                TimelineScreen(state, ::command, { repository.engine.select(it); repository.publish() }, { repository.message(null) })
            }
        }
    }
    override fun onStart() { super.onStart(); repository.uiVisible = true }
    override fun onStop() { repository.uiVisible = false; super.onStop() }
    private fun command(action: String) {
        if (action == TrackingService.STOP || action == TrackingService.PAUSE || action == PhoneLocationService.STOP || action == AutomaticTimelineService.STOP) { runService(action); return }
        val needsActivity = action == AutomaticTimelineService.START && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || needsActivity) {
            pendingAction = action
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            if (needsActivity) permissions += Manifest.permission.ACTIVITY_RECOGNITION
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = action
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else runService(action)
        }
    }
    private fun runService(action: String) {
        try {
            if (action == AutomaticTimelineService.STOP) { stopService(Intent(this, AutomaticTimelineService::class.java)); return }
            if (action == AutomaticTimelineService.START) {
                startForegroundService(Intent(this, AutomaticTimelineService::class.java).setAction(action)); return
            }
            if (action == PhoneLocationService.STOP) { stopService(Intent(this, PhoneLocationService::class.java)); return }
            if (action == PhoneLocationService.START) {
                startForegroundService(Intent(this, PhoneLocationService::class.java).setAction(action)); return
            }
            val intent = Intent(this, TrackingService::class.java).setAction(action)
            if (action == TrackingService.START || action == TrackingService.RETURN) startForegroundService(intent) else startService(intent)
        } catch (error: Exception) { repository.message("기록 서비스를 실행하지 못했습니다: ${error.message}") }
    }
}
private val blue = Color(0xFF2997FF)
internal fun dateTime(time: Long, pattern: String) = SimpleDateFormat(pattern, Locale.KOREA).format(Date(time))
private fun kilometers(distance: Double) = String.format(Locale.KOREA, "%.2f", distance / 1000)

@Composable
private fun TimelineScreen(state: TrackerState, command: (String) -> Unit, select: (Track) -> Unit, dismiss: () -> Unit) {
    var showHistory by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("나의 타임라인", fontSize = 26.sp, color = Color.White)
                    Text(when (state.mode) { Mode.RECORDING -> "오늘의 길을 기록하고 있어요."; Mode.PAUSED -> "잠시 쉬어 가세요."; Mode.RETURNING -> "지나온 길을 따라 돌아가세요."; else -> "오늘의 길을 기억하세요." }, color = Color.Gray, fontSize = 14.sp)
                }
                TextButton(onClick = { showHistory = true }) { Text("기록") }
            }
            Text(dateTime(state.track.started, "yyyy년 M월 d일 (E)"), color = Color.Gray, fontSize = 12.sp)
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("워치에 위치 공유", fontSize = 16.sp)
                        Text(if (state.phoneSharing) "켜짐 · 연결된 워치의 요청에 폰 GPS 사용" else "워치가 폰 GPS·정지 감지 값을 사용합니다.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = state.phoneSharing, onCheckedChange = {
                        command(if (it) PhoneLocationService.START else PhoneLocationService.STOP)
                    }, modifier = Modifier.semantics { contentDescription = "워치에 위치 공유" })
                }
            }
            RouteCanvas(state, Modifier.fillMaxWidth().height(270.dp))
            if (state.awaitingFix) Text("정확한 GPS 신호를 기다리는 중…", color = blue)
            state.position?.let { position ->
                Text("GPS 추정 오차 약 ${position.accuracy.roundToInt()} m" +
                    if (state.heldStill) " · 센서 정지 보정" else " · 좌표 필터 적용",
                    color = Color.Gray, fontSize = 12.sp)
            }
            if (state.mode == Mode.RETURNING) Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    DirectionArrow(state.targetAngle, Modifier.size(54.dp))
                    Column {
                        Text(if (state.target == null) "현재 위치 확인 중" else "이전 경로 지점으로 이동", fontSize = 18.sp)
                        Text(state.targetDistance?.let { "다음 지점까지 ${it.roundToInt()} m" } ?: "GPS 신호를 기다려 주세요.", color = blue)
                        if (state.heading == null) Text("나침반 센서를 기다리는 중", color = Color.Gray, fontSize = 12.sp)
                        Text("저장한 GPS 지점 기준 안내", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            Panel {
                Row { Text("오늘 지나온 길", fontSize = 20.sp, modifier = Modifier.weight(1f)); Text("${state.track.points.size}개 지점", color = Color.Gray, fontSize = 12.sp) }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFF292D31))
                val points = state.track.points
                if (points.isEmpty()) Text("기록 시작을 누르고 걸어 보세요.\n실제 GPS 위치와 시간이 여기에 표시됩니다.", color = Color.Gray, modifier = Modifier.padding(vertical = 22.dp))
                else {
                    val selected = if (points.size <= 6) points else (0..5).map { points[it * (points.size - 1) / 5] }
                    selected.asReversed().forEach { point ->
                        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.width(16.dp).height(46.dp)) {
                                drawLine(Color.Gray, Offset(center.x, 0f), Offset(center.x, size.height), 2f)
                                drawCircle(if (point == points.last()) blue else Color.Gray, 5.dp.toPx())
                            }
                            Text(dateTime(point.timestamp, "HH:mm"), color = Color.Gray, modifier = Modifier.width(65.dp).padding(start = 8.dp), fontSize = 14.sp)
                            Column {
                                Text(if (point == points.first()) "출발" else if (point == points.last()) "마지막 기록 위치" else "경유 지점", color = if (point == points.last()) blue else Color.White)
                                Text(String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude), color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Panel {
                val distance = state.track.distance
                val pace = if (distance > 0) (state.track.elapsedSeconds / (distance / 1000)).roundToInt() else 0
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("이동 거리", kilometers(distance), "km", Modifier.weight(1f))
                    Metric("이동 시간", "%02d:%02d".format(state.track.elapsedSeconds / 60, state.track.elapsedSeconds % 60), "분:초", Modifier.weight(1f))
                    Metric("평균 페이스", if (pace > 0) "${pace / 60}′%02d″".format(pace % 60) else "—", "/km", Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.mode == Mode.RECORDING || state.mode == Mode.PAUSED) {
                    OutlinedButton(onClick = { command(TrackingService.PAUSE) }, modifier = Modifier.weight(1f)) { Text(if (state.mode == Mode.PAUSED) "재개" else "일시정지") }
                    OutlinedButton(onClick = { command(TrackingService.STOP) }, modifier = Modifier.weight(1f)) { Text("종료") }
                } else if (state.mode == Mode.IDLE) {
                    Button(onClick = { command(TrackingService.START) }, modifier = Modifier.weight(1f)) { Text("기록 시작") }
                }
                OutlinedButton(onClick = { command(if (state.mode == Mode.RETURNING) TrackingService.STOP else TrackingService.RETURN) }, enabled = state.track.points.size >= 2, modifier = Modifier.weight(1f)) {
                    Text(if (state.mode == Mode.RETURNING) "안내 종료" else "되돌아가기")
                }
            }
        }
    }
    state.message?.let { message -> AlertDialog(onDismissRequest = dismiss, title = { Text("Timeline") }, text = { Text(message) }, confirmButton = { TextButton(onClick = dismiss) { Text("확인") } }) }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("저장한 경로") }, text = {
        if (state.history.isEmpty()) Text("저장된 경로가 없습니다.") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.history, key = { it.id }) { track -> TextButton(onClick = { select(track); showHistory = false }, enabled = state.mode == Mode.IDLE) {
                Column(Modifier.fillMaxWidth()) { Text(dateTime(track.started, "M월 d일 HH:mm")); Text("${kilometers(track.distance)} km · ${track.elapsedSeconds / 60}분", color = Color.Gray) }
            } }
        }
    }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("닫기") } })
}
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF111416), RoundedCornerShape(26.dp)).border(1.dp, Color(0xFF292D31), RoundedCornerShape(26.dp)).padding(20.dp), content = content)
}
@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) { Text(label, fontSize = 12.sp, color = Color.Gray); Text(value, fontSize = 24.sp, maxLines = 1); Text(unit, fontSize = 12.sp, color = Color.Gray) }
}
@Composable
private fun DirectionArrow(angle: Double?, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = if (angle == null) "방향 센서 확인 중" else "이전 지점 방향 ${angle.roundToInt()}도" }) {
        if (angle == null) drawCircle(Color.Gray, style = Stroke(2.dp.toPx()))
        else rotate(angle.toFloat()) {
            val top = Offset(center.x, size.height * 0.1f)
            drawLine(blue, Offset(center.x, size.height * .9f), top, 5.dp.toPx(), StrokeCap.Round)
            drawLine(blue, top, Offset(size.width * .2f, size.height * .4f), 5.dp.toPx(), StrokeCap.Round)
            drawLine(blue, top, Offset(size.width * .8f, size.height * .4f), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}
@Composable
internal fun RouteCanvas(state: TrackerState, modifier: Modifier) {
    Box(modifier.background(Color(0xFF0D161C), RoundedCornerShape(26.dp)).border(1.dp, Color(0xFF292D31), RoundedCornerShape(26.dp))) {
        Canvas(Modifier.fillMaxSize().padding(26.dp).semantics { contentDescription = "저장된 GPS 경로, 북쪽이 위" }) {
            for (i in 1..4) drawCircle(Color(0xFF202C34), radius = size.minDimension * i / 8, style = Stroke(1f))
            val points = state.track.points
            val all = points + listOfNotNull(state.position)
            if (all.isNotEmpty()) {
                val lat0 = all.map { it.latitude }.average()
                val lon0 = all.first().longitude
                fun x(p: TrackPoint) = ((p.longitude - lon0 + 540) % 360 - 180) * cos(Math.toRadians(lat0)) * 111320
                fun y(p: TrackPoint) = -(p.latitude - lat0) * 111320
                val minX = all.minOf(::x); val maxX = all.maxOf(::x); val minY = all.minOf(::y); val maxY = all.maxOf(::y)
                val scale = min(size.width / max(maxX - minX, 50.0), size.height / max(maxY - minY, 50.0))
                fun offset(p: TrackPoint) = Offset((center.x + (x(p) - (minX + maxX) / 2) * scale).toFloat(), (center.y + (y(p) - (minY + maxY) / 2) * scale).toFloat())
                val path = Path()
                points.forEachIndexed { i, p -> val at = offset(p); if (i == 0 || p.segment != points[i - 1].segment) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y) }
                drawPath(path, Color(0xFF83919C), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                state.target?.let { drawCircle(Color(0xFFFFC572), 7.dp.toPx(), offset(it)) }
                points.firstOrNull()?.let { drawCircle(Color.White, 5.dp.toPx(), offset(it)) }
                // Sparse automatic checkpoints remain visible, but disconnected gaps have no line.
                points.forEachIndexed { index, point ->
                    if (index == 0 || point.segment != points[index - 1].segment) drawCircle(Color(0xFF83919C), 3.dp.toPx(), offset(point))
                }
                state.position?.let {
                    val at = offset(it)
                    drawCircle(blue.copy(alpha = .15f), 22.dp.toPx(), at)
                    drawCircle(blue, 10.dp.toPx(), at); drawCircle(Color.White, 4.dp.toPx(), at)
                }
            }
        }
        Text("N ↑", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        Text("GPS 경로", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
        if (state.track.points.isEmpty()) Text("첫 발걸음부터 기록해요", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
    }
}
