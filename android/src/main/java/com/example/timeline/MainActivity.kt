import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.atan2

// 데이터 모델
data class RoutePoint(val latitude: Double, val longitude: Double)

// 예시 경로
val examplePath = listOf(
    RoutePoint(37.7749, -122.4194),
    RoutePoint(34.0522, -118.2437),
    RoutePoint(36.1699, -115.1398)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CompassRouteScreen() }
    }
}

@Composable
fun CompassRouteScreen() {
    var reverse by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(300.dp)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Circle base
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(
                color = Color.Gray.copy(alpha = 0.3f),
                radius = size.minDimension / 2
            )
        }
        // Path line
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val path = drawScope.path {
                moveTo(center.x, center.y)
                examplePath.drop(1).forEach { pt ->
                    val offset = convertToOffset(pt, size, reverse)
                    lineTo(offset.x, offset.y)
                }
            }
            drawPath(
                path = path,
                color = if (reverse) Color.Red else Color.Green,
                style = Stroke(width = 6f, lineCap = Stroke.LineCap.Round)
            )
        }
        // Compass arrow (simplified)
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val length = size.minDimension / 3
            val end = center + Offset(0f, -length)
            drawLine(
                color = Color.Blue,
                strokeWidth = 4f,
                start = center,
                end = end
            )
        }
        // Center dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color.White, shape = CircleShape)
                .align(Alignment.Center)
        )
        // Reverse button
        Button(
            onClick = { reverse = !reverse },
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.BottomCenter)
                .offset(y = 30.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
        }
    }
}

private fun convertToOffset(pt: RoutePoint, size: Size, reverse: Boolean): Offset {
    val x = ((pt.longitude + 180) / 360 * size.width).toFloat()
    val y = ((pt.latitude + 90) / 180 * size.height).toFloat()
    return if (reverse) Offset(size.width - x, size.height - y) else Offset(x, y)
}
