package com.example.timeline

import android.content.SharedPreferences
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal enum class PhoneTab(val label: String) { HOME("홈"), EXERCISE("운동"), OPTIONS("설정") }
private val accent = Color(0xFF237DE8)
private val ink = Color(0xFF17242E)
private val muted = Color(0xFF697783)
private val pageBackground = Color(0xFFF2F4F8)

@Composable
internal fun PhoneShell(tracker: TrackerState, automatic: AutomaticTimelineState, preferences: SharedPreferences,
                        openHome: Boolean, command: (String) -> Unit, dismissMessage: () -> Unit, workout: @Composable () -> Unit) {
    var tabName by rememberSaveable { mutableStateOf(if (openHome) PhoneTab.HOME.name else PhoneTab.OPTIONS.name) }
    var tinted by rememberSaveable { mutableStateOf(preferences.getBoolean("tinted", false)) }
    val tab = PhoneTab.valueOf(tabName)
    fun navigate(value: PhoneTab) { tabName = value.name }
    val dark = tab == PhoneTab.EXERCISE
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent, background = Color.Black, surface = Color(0xFF111416))
        else lightColorScheme(primary = accent, background = pageBackground, surface = Color.White, onSurface = ink)) {
        Surface(color = if (dark) Color.Black else pageBackground, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        PhoneTab.OPTIONS -> PhoneOptions(automatic, tinted, { value ->
                            tinted = value; preferences.edit().putBoolean("tinted", value).apply()
                        }, command, ::navigate)
                        PhoneTab.HOME -> DailyTimelineScreen(automatic, command, { navigate(PhoneTab.EXERCISE) })
                        PhoneTab.EXERCISE -> workout()
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    GlassNavigation(tab, tinted, dark, ::navigate)
                }
            }
        }
        // Tracking messages otherwise only appear on the workout screen.
        if (!dark) tracker.message?.let { message ->
            AlertDialog(onDismissRequest = dismissMessage, title = { Text("Timeline") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = dismissMessage) { Text("확인") } })
        }
    }
}

@Composable
private fun PhoneOptions(automatic: AutomaticTimelineState, tinted: Boolean, choose: (Boolean) -> Unit,
                         command: (String) -> Unit, navigate: (PhoneTab) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Timeline", color = ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("화면과 기록", color = muted, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF9FE7D5), Color(0xFF8BDDDC), Color(0xFF74B3EC), Color(0xFF2350C1)))), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val path = Path().apply {
                    moveTo(-20f, size.height * .85f)
                    cubicTo(size.width * .1f, -size.height * .15f, size.width * .5f, size.height * 1.25f, size.width * 1.1f, size.height * .12f)
                }
                drawPath(path, Color.White.copy(alpha = .28f), style = Stroke(85.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(Color(0xFF173997).copy(alpha = .18f), size.width * .3f, Offset(size.width * .98f, -size.height * .1f))
            }
            GlassNavigation(PhoneTab.OPTIONS, tinted, false, navigate, preview = true)
        }
        WhiteCard(padding = 0) {
            AppearanceChoice("Clear", "맑고 가벼운 유리", !tinted) { choose(false) }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = pageBackground)
            AppearanceChoice("Tinted", "색상과 대비를 더 선명하게", tinted) { choose(true) }
        }
        Text("유리 메뉴의 투명도와 강조 색을 선택하세요. 홈 아이콘을 누르면 실제 기록한 타임라인이 열립니다.", color = muted, fontSize = 13.sp)
        WhiteCard {
            AutomaticRecordToggle(automatic, command)
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = pageBackground)
            Row { Text("기본 업데이트", color = ink, modifier = Modifier.weight(1f)); Text("30분", color = accent) }
            Row(Modifier.padding(top = 12.dp)) { Text("이동 감지 시", color = ink, modifier = Modifier.weight(1f)); Text("10초", color = accent) }
            Text("움직임이 2분간 감지되지 않으면 기본 주기로 돌아갑니다.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        }
        Text("자동 기록을 켜면 알림을 표시하며 위치를 수집합니다. 절전·GPS 상태에 따라 실제 간격은 달라질 수 있습니다. 앱 강제 종료나 재부팅 뒤에는 직접 다시 켜 주세요.", color = muted, fontSize = 12.sp)
        automatic.status?.let { Text(it, color = accent, fontSize = 13.sp) }
        automatic.storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    }
}

@Composable
private fun AppearanceChoice(label: String, subtitle: String, selected: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = action).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, fontSize = 16.sp, color = ink); Text(subtitle, fontSize = 12.sp, color = muted) }
        if (selected) Text("✓", color = accent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

@Composable
private fun AutomaticRecordToggle(state: AutomaticTimelineState, command: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("자동 타임라인", color = ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(if (state.enabled) if (state.moving) "이동 중 · 10초 주기" else "기본 30분 · 이동 감지 대기" else "직접 켜면 일별로 기록합니다", color = muted, fontSize = 12.sp)
        }
        Switch(state.enabled, { command(if (it) AutomaticTimelineService.START else AutomaticTimelineService.STOP) },
            modifier = Modifier.semantics { contentDescription = "자동 타임라인 기록" })
    }
    if (state.enabled) Text(state.detection, color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DailyTimelineScreen(state: AutomaticTimelineState, command: (String) -> Unit, workout: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(AutomaticTimelineRepository.dayId()) }
    var showDays by remember { mutableStateOf(false) }
    val day = state.days.firstOrNull { it.id == selectedId }
    val points = day?.points.orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("나의 타임라인", color = ink, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                    Text("작은 발걸음도, 하루의 흐름도.", color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = { showDays = true }) { Text("날짜 선택") }
            }
            Text(selectedId, color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        item { WhiteCard { AutomaticRecordToggle(state, command)
            if (state.enabled && !state.moving) Text(if (state.acquiring) "GPS 위치 확인 중…" else "다음 확인 · ${state.nextCheck?.let { dateTime(it, "HH:mm") } ?: "—"}", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        } }
        item {
            RouteCanvas(TrackerState(track = day ?: Track(points = emptyList()), position = points.lastOrNull()), Modifier.fillMaxWidth().height(235.dp))
            Text("${points.size}개 기록 · 선은 연속으로 확인한 이동 구간만 표시합니다.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        state.status?.let { message -> item { Text(message, color = accent, fontSize = 13.sp) } }
        state.storageError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        item {
            WhiteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("운동 · 되돌아가기", color = ink, fontWeight = FontWeight.Medium); Text("빠른 연속 기록과 이전 길 안내", color = muted, fontSize = 12.sp) }
                    TextButton(onClick = workout) { Text("열기 →") }
                }
                Text("30분 간격 기록만으로는 사이에 지나온 길을 복원할 수 없어요. 복귀 안내는 운동 경로로 기록하세요.", color = muted, fontSize = 12.sp)
            }
        }
        item { Text("지나온 순간", color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        if (points.isEmpty()) item { WhiteCard { Text("아직 기록이 없어요.\n자동 타임라인을 켜고 정확한 위치를 허용해 주세요.", color = muted, lineHeight = 24.sp) } }
        items(points.asReversed(), key = { it.timestamp }) { point ->
            WhiteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(accent, RoundedCornerShape(50)))
                    Text(dateTime(point.timestamp, "HH:mm:ss"), color = muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp).width(85.dp))
                    Column {
                        Text(if (point == points.last()) "최근 기록 위치" else "기록 위치", color = ink, fontSize = 15.sp)
                        Text(String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude), color = muted, fontSize = 12.sp)
                        Text("추정 오차 약 ${point.accuracy.toInt()} m", color = muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (showDays) AlertDialog(onDismissRequest = { showDays = false }, title = { Text("타임라인 날짜") }, text = {
        val days = (listOf(AutomaticTimelineRepository.dayId()) + state.days.map { it.id }).distinct().sortedDescending()
        LazyColumn(Modifier.heightIn(max = 360.dp)) { items(days) { id ->
            TextButton(onClick = { selectedId = id; showDays = false }, modifier = Modifier.fillMaxWidth()) { Text(id + if (id == selectedId) "  ✓" else "") }
        } }
    }, confirmButton = { TextButton(onClick = { showDays = false }) { Text("닫기") } })
}

@Composable
private fun WhiteCard(padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(padding.dp), content = content)
}

@Composable
private fun GlassNavigation(selected: PhoneTab, tinted: Boolean, dark: Boolean, navigate: (PhoneTab) -> Unit, preview: Boolean = false) {
    val shape = RoundedCornerShape(50)
    val background = if (dark) Color(0xFF263446).copy(alpha = if (tinted) .94f else .65f)
        else if (tinted) Color(0xFFDCEFFC).copy(alpha = .95f) else Color.White.copy(alpha = .58f)
    Row(Modifier.clip(shape).background(background).border(1.dp, Color.White.copy(alpha = .65f), shape).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhoneTab.entries.forEach { tab ->
            val active = tab == selected
            val foreground = if (active) accent else if (dark) Color.White else ink
            Column(Modifier.width(if (preview) 62.dp else 72.dp).clip(RoundedCornerShape(40)).background(if (active) accent.copy(alpha = .11f) else Color.Transparent)
                .semantics(mergeDescendants = true) { contentDescription = if (tab == PhoneTab.EXERCISE) "운동 및 되돌아가기" else tab.label }
                .selectable(selected = active, role = Role.Tab, onClick = { navigate(tab) }).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                NavigationGlyph(tab, foreground, Modifier.size(24.dp))
                if (!preview) Text(tab.label, fontSize = 10.sp, color = foreground, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun NavigationGlyph(tab: PhoneTab, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            PhoneTab.HOME -> {
                val path = Path().apply { moveTo(w * .1f, h * .45f); lineTo(w * .5f, h * .1f); lineTo(w * .9f, h * .45f)
                    moveTo(w * .22f, h * .36f); lineTo(w * .22f, h * .88f); lineTo(w * .42f, h * .88f); lineTo(w * .42f, h * .63f)
                    lineTo(w * .6f, h * .63f); lineTo(w * .6f, h * .88f); lineTo(w * .78f, h * .88f); lineTo(w * .78f, h * .36f) }
                drawPath(path, color, style = stroke)
            }
            PhoneTab.EXERCISE -> {
                val path = Path().apply { moveTo(w * .22f, h * .86f); cubicTo(w * .95f, h * .67f, -w * .1f, h * .32f, w * .76f, h * .14f) }
                drawPath(path, color, style = stroke); drawCircle(color, 2.dp.toPx(), Offset(w * .22f, h * .86f)); drawCircle(color, 2.dp.toPx(), Offset(w * .76f, h * .14f))
            }
            PhoneTab.OPTIONS -> {
                for (index in 0..2) { val y = h * (.22f + index * .28f); val x = w * if (index == 1) .65f else .35f
                    drawLine(color, Offset(w * .12f, y), Offset(w * .88f, y), 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(x, y))
                }
            }
        }
    }
}
