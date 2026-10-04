package com.example.timeline.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.example.timeline.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

private val Blue = Color(0xFF2997FF)
private val Panel = Color(0xFF111416)
private val Border = Color(0xFF303338)
private enum class Symbol { PLAY, PAUSE, STOP, RETURN, CENTER, ARROW, LIST }

@Composable
fun WatchScreen(state: TrackerState, command: (String) -> Unit, select: (Track) -> Unit, dismiss: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    var northUp by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { time = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
    BackHandler(details || confirmStop || state.message != null) { details = false; confirmStop = false; dismiss() }
    MaterialTheme(colors = Colors(primary = Blue, background = Color.Black, surface = Panel)) {
        when {
            state.message != null -> WatchList("Timeline") {
                item { Text(state.message.orEmpty(), textAlign = TextAlign.Center, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                item { Chip(onClick = dismiss, label = { Text("확인") }) }
            }
            confirmStop -> WatchList("기록 종료") {
                item { Text("기록을 저장하고 종료할까요?", fontSize = 12.sp, textAlign = TextAlign.Center) }
                item { Chip(onClick = { command(TrackingService.STOP); confirmStop = false }, label = { Text("저장하고 종료") }) }
                item { Chip(onClick = { confirmStop = false }, label = { Text("계속 기록") }) }
            }
            details -> WatchList("나의 기록") {
                item { Text("${state.track.points.size}개 GPS 지점", fontSize = 12.sp, color = Color.Gray) }
                item { Text(when {
                    state.position == null -> if (state.phoneConnected) "폰 연결됨 · 위치 확인 중" else "워치 GPS 확인 중"
                    state.locationSource == LocationSource.PHONE -> "위치: 폰 GPS"
                    state.phoneConnected -> "위치: 워치 GPS · 폰 값 대기"
                    else -> "위치: 워치 GPS"
                }, fontSize = 11.sp, color = Blue, textAlign = TextAlign.Center) }
                item { Text(state.position?.let { "GPS 추정 오차 약 ${it.accuracy.roundToInt()} m" } ?: "GPS 위치 확인 중", fontSize = 11.sp, color = Color.Gray) }
                item { Text(when (state.motion) {
                    MotionState.STILL -> "${if (state.locationSource == LocationSource.PHONE) "폰" else "워치"} 센서: 정지 감지"
                    MotionState.MOVING -> "${if (state.locationSource == LocationSource.PHONE) "폰" else "워치"} 센서: 움직임 감지"
                    MotionState.UNKNOWN -> "정지 보정 대기 / 센서 미지원"
                }, fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center) }
                item { Chip(onClick = { details = false }, label = { Text("경로 화면") }) }
                item { Chip(onClick = { command(if (state.mode == Mode.RETURNING) TrackingService.STOP else TrackingService.RETURN); details = false }, enabled = state.track.points.size >= 2, label = { Text(if (state.mode == Mode.RETURNING) "안내 종료" else "되돌아가기") }) }
                if (state.mode == Mode.RECORDING || state.mode == Mode.PAUSED) item { Chip(onClick = { confirmStop = true }, label = { Text("기록 종료") }) }
                item { Text("저장된 경로", fontSize = 12.sp) }
                if (state.history.isEmpty()) item { Text("아직 기록이 없어요", color = Color.Gray, fontSize = 12.sp) }
                state.history.forEach { track -> item(key = track.id) {
                    Chip(onClick = { select(track); details = false }, enabled = state.mode == Mode.IDLE,
                        label = { Text(SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(track.started))) },
                        secondaryLabel = { Text("${km(track.distance)} km · ${track.elapsedSeconds / 60}분") })
                } }
                item { Text("GPS 지점 기준 안내\n일시정지 구간의 길은 기록되지 않습니다.", fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center) }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clip(CircleShape)) {
                val side = minOf(maxWidth, maxHeight)
                val factor = side.value / 192f
                fun at(x: Float, y: Float, width: Float, height: Float) = Modifier.offset(side * x, side * y).size(side * width, side * height)
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    var drag = 0f
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (abs(drag) > 35.dp.toPx()) details = true }) { _, amount -> drag += amount }
                }) {
                    RouteDial(state, northUp, Modifier.fillMaxSize())
                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)), fontSize = (10 * factor).sp,
                        color = Color(0xFFAAAAAA), textAlign = TextAlign.Center, modifier = at(.35f, .035f, .3f, .065f).clickable { details = true }.semantics { contentDescription = "시간, 기록 목록 열기" })
                    GuideCard(state, factor, at(.14f, .115f, .72f, .18f).clickable { details = true })
                    RoundControl(Symbol.RETURN, if (state.mode == Mode.RETURNING) "안내 종료" else "되돌아가기", state.track.points.size >= 2,
                        at(.76f, .56f, .17f, .17f)) { command(if (state.mode == Mode.RETURNING) TrackingService.STOP else TrackingService.RETURN) }
                    Row(at(.23f, .705f, .54f, .10f).clickable { details = true }, horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat("거리", km(state.track.distance), "km", factor, Modifier.weight(1f))
                        Stat("시간", "%02d:%02d".format(state.track.elapsedSeconds / 60, state.track.elapsedSeconds % 60), "분:초", factor, Modifier.weight(1f))
                        val pace = if (state.track.distance > 0) (state.track.elapsedSeconds / (state.track.distance / 1000)).roundToInt() else 0
                        Stat("페이스", if (pace > 0) "${pace / 60}′%02d″".format(pace % 60) else "—", "/km", factor, Modifier.weight(1f))
                    }
                    RoundControl(Symbol.STOP, if (state.mode == Mode.RETURNING) "안내 종료" else "기록 종료", state.mode != Mode.IDLE, at(.23f, .80f, .16f, .16f)) {
                        if (state.mode == Mode.RETURNING) command(TrackingService.STOP) else confirmStop = true
                    }
                    val mainSymbol = if (state.mode == Mode.RECORDING) Symbol.PAUSE else Symbol.PLAY
                    val mainLabel = when (state.mode) { Mode.RECORDING -> "일시정지"; Mode.PAUSED -> "재개"; else -> "기록 시작" }
                    RoundControl(mainSymbol, mainLabel, state.mode != Mode.RETURNING, at(.405f, .805f, .19f, .19f), accent = true) {
                        command(if (state.mode == Mode.IDLE) TrackingService.START else TrackingService.PAUSE)
                    }
                    RoundControl(Symbol.CENTER, if (northUp) "나침반 화면으로 전환" else "전체 경로 화면으로 전환", true, at(.61f, .80f, .16f, .16f)) { northUp = !northUp }
                }
            }
        }
    }
}
@Composable
private fun WatchList(title: String, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = state) }) {
        ScalingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 32.dp)) {
            item { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            content()
        }
    }
}
@Composable
private fun GuideCard(state: TrackerState, factor: Float, modifier: Modifier) {
    val active = state.mode == Mode.RETURNING
    val label = if (active) directionLabel(state.targetAngle) else when {
        state.awaitingFix -> "GPS 확인 중"
        state.mode == Mode.RECORDING -> if (state.locationSource == LocationSource.PHONE && state.position != null) "폰 GPS 기록 중" else "경로 기록 중"
        state.mode == Mode.PAUSED -> "기록 일시정지"
        else -> "Timeline"
    }
    Row(modifier.background(Panel, RoundedCornerShape(22.dp)).border(.6.dp, Border, RoundedCornerShape(22.dp)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        DrawSymbol(if (active && state.targetAngle != null) Symbol.ARROW else Symbol.LIST, Modifier.size((18 * factor).dp), state.targetAngle?.toFloat() ?: 0f)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(label, fontSize = (9 * factor).sp, lineHeight = (11 * factor).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (active) state.targetDistance?.let { "${it.roundToInt()} m" } ?: "GPS 대기" else when {
                state.mode == Mode.IDLE -> "기록 시작"
                state.heldStill -> "정지 보정"
                state.position != null -> "${if (state.locationSource == LocationSource.PHONE) "폰" else "GPS"} ~${state.position?.accuracy?.roundToInt()} m"
                else -> "GPS 대기"
            }, fontSize = (14 * factor).sp, lineHeight = (16 * factor).sp, fontWeight = FontWeight.Bold, color = if (active) Blue else Color.White, maxLines = 1)
        }
        if (active) Column(Modifier.width((29 * factor).dp).border(width = .5.dp, color = Border).padding(start = 4.dp)) {
            Text("다음 지점", fontSize = (7 * factor).sp, lineHeight = (9 * factor).sp, color = Color.Gray)
            val next = nextDirection(state)?.replace("으로 가세요", "")?.replace("정면", "직진")?.replace("뒤로 돌아가세요", "돌아서")?.replace("기록 공백", "공백") ?: "대기"
            Text(next, fontSize = (8 * factor).sp, lineHeight = (9 * factor).sp, maxLines = 1)
            val index = state.targetIndex
            if (index != null && index > 0 && state.track.points[index].segment == state.track.points[index - 1].segment) {
                Text("${distance(state.track.points[index], state.track.points[index - 1]).roundToInt()} m", fontSize = (8 * factor).sp, lineHeight = (9 * factor).sp, color = Color.Gray, maxLines = 1)
            }
        }
    }
}
@Composable
private fun Stat(label: String, value: String, unit: String, factor: Float, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$label $unit", fontSize = (6 * factor).sp, lineHeight = (7 * factor).sp, color = Color.Gray, maxLines = 1)
        Text(value, fontSize = (10 * factor).sp, lineHeight = (11 * factor).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
private fun km(value: Double) = String.format(Locale.KOREA, "%.2f", value / 1000)
@Composable
private fun RoundControl(symbol: Symbol, label: String, enabled: Boolean, modifier: Modifier, accent: Boolean = false, action: () -> Unit) {
    Box(modifier.clip(CircleShape).background(if (accent) Color(0xFF071B2A) else Panel).border(if (accent) 1.2.dp else .6.dp, if (accent) Blue else Border, CircleShape)
        .clickable(enabled = enabled, onClick = action).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        DrawSymbol(symbol, Modifier.fillMaxSize().padding(8.dp), color = if (enabled) Color.White else Color(0xFF444444))
    }
}
@Composable
private fun DrawSymbol(symbol: Symbol, modifier: Modifier, angle: Float = 0f, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = size.minDimension * .13f
        fun line(ax: Float, ay: Float, bx: Float, by: Float) = drawLine(color, Offset(w * ax, h * ay), Offset(w * bx, h * by), stroke, StrokeCap.Round)
        when (symbol) {
            Symbol.STOP -> drawRoundRect(color, Offset(w * .15f, h * .15f), Size(w * .7f, h * .7f), androidx.compose.ui.geometry.CornerRadius(stroke))
            Symbol.PAUSE -> { line(.3f, .15f, .3f, .85f); line(.7f, .15f, .7f, .85f) }
            Symbol.PLAY -> drawPath(Path().apply { moveTo(w * .25f, h * .1f); lineTo(w * .9f, h * .5f); lineTo(w * .25f, h * .9f); close() }, color)
            Symbol.CENTER -> { drawCircle(color, size.minDimension * .3f, style = Stroke(stroke * .7f)); line(.5f, 0f, .5f, .2f); line(.5f, .8f, .5f, 1f); line(0f, .5f, .2f, .5f); line(.8f, .5f, 1f, .5f) }
            Symbol.RETURN -> { drawArc(color, -40f, 280f, false, Offset(w * .13f, h * .13f), Size(w * .74f, h * .74f), style = Stroke(stroke, cap = StrokeCap.Round)); line(.65f, .1f, .9f, .2f); line(.9f, .2f, .83f, .43f) }
            Symbol.ARROW -> rotate(angle) { line(.5f, .95f, .5f, .1f); line(.5f, .1f, .15f, .4f); line(.5f, .1f, .85f, .4f) }
            Symbol.LIST -> { line(.2f, .25f, .8f, .25f); line(.2f, .5f, .8f, .5f); line(.2f, .75f, .8f, .75f) }
        }
    }
}

@Composable
private fun RouteDial(state: TrackerState, northUp: Boolean, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = if (northUp) "북쪽 기준 전체 GPS 경로" else "현재 위치 중심 나침반 경로" }) {
        val origin = Offset(size.width / 2, size.height * .48f)
        val radius = size.minDimension * .215f
        val heading = if (northUp) 0.0 else state.heading ?: 0.0
        for (i in 1..4) drawCircle(Color(0xFF1E2024), radius * i / 4, origin, style = Stroke(.6.dp.toPx()))
        drawCircle(Color(0xFF6F7378), radius, origin, style = Stroke(.6.dp.toPx()))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER }
        listOf("N", "E", "S", "W").forEachIndexed { i, letter ->
            val a = Math.toRadians(i * 90.0 - heading - 90)
            val labelRadius = if (i % 2 == 0) radius - 7.dp.toPx() else radius + 5.dp.toPx()
            val at = Offset(origin.x + cos(a).toFloat() * labelRadius, origin.y + sin(a).toFloat() * labelRadius)
            drawContext.canvas.nativeCanvas.drawText(letter, at.x, at.y + paint.textSize / 3, paint)
        }
        val points = state.track.points
        val all = points + listOfNotNull(state.position)
        if (all.isEmpty()) return@Canvas
        val reference = state.position ?: points.last()
        val latitude = reference.latitude
        fun x(p: TrackPoint) = ((p.longitude - reference.longitude + 540) % 360 - 180) * cos(Math.toRadians(latitude)) * 111320
        fun y(p: TrackPoint) = -(p.latitude - latitude) * 111320
        val midX = if (northUp) (all.minOf(::x) + all.maxOf(::x)) / 2 else 0.0
        val midY = if (northUp) (all.minOf(::y) + all.maxOf(::y)) / 2 else 0.0
        val extent = max(30.0, all.maxOf { hypot(x(it) - midX, y(it) - midY) })
        val scale = radius * .88 / extent
        val a = Math.toRadians(-heading)
        fun at(p: TrackPoint): Offset {
            val px = x(p) - midX; val py = y(p) - midY
            return Offset((origin.x + (px * cos(a) - py * sin(a)) * scale).toFloat(), (origin.y + (px * sin(a) + py * cos(a)) * scale).toFloat())
        }
        val path = Path(); val remaining = Path()
        points.forEachIndexed { index, p ->
            val pos = at(p)
            if (index == 0 || p.segment != points[index - 1].segment) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
            if (state.mode == Mode.RETURNING && index <= (state.targetIndex ?: -1)) {
                if (index == 0 || p.segment != points[index - 1].segment) remaining.moveTo(pos.x, pos.y) else remaining.lineTo(pos.x, pos.y)
            }
        }
        drawPath(path, Color(0xFF777C83), style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
        drawPath(remaining, Blue, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
        points.firstOrNull()?.let { drawCircle(Color.White, 2.5.dp.toPx(), at(it)) }
        state.target?.let { drawCircle(Color(0xFFFFC572), 3.dp.toPx(), at(it)) }
        val user = state.position?.let(::at) ?: at(points.last())
        drawCircle(Blue.copy(alpha = .15f), 11.dp.toPx(), user)
        drawCircle(Blue, 6.dp.toPx(), user); drawCircle(Color.White, 2.8.dp.toPx(), user)
        if (state.heading != null && state.position != null) rotate(if (northUp) (state.heading ?: 0.0).toFloat() else 0f, user) {
            val arrow = Path().apply { moveTo(user.x, user.y - 15.dp.toPx()); lineTo(user.x - 4.dp.toPx(), user.y - 5.dp.toPx()); lineTo(user.x, user.y - 7.dp.toPx()); lineTo(user.x + 4.dp.toPx(), user.y - 5.dp.toPx()); close() }
            drawPath(arrow, Color.White)
        }
    }
}
