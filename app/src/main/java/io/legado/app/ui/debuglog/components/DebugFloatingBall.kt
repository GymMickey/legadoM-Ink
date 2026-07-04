package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugFloatingBall(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    unreadCount: Int = 0
) {
    if (!isVisible) return

    val ballSize = 56.dp
    val endMargin = 16.dp
    val bottomMargin = 100.dp
    val initialInset = 8.dp
    val snapThreshold = 84.dp
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var initialized by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = LocalContext.current

    val ballSizePx = with(density) { ballSize.toPx() }
    val endMarginPx = with(density) { endMargin.toPx() }
    val bottomMarginPx = with(density) { bottomMargin.toPx() }
    val initialInsetPx = with(density) { initialInset.toPx() }
    val snapThresholdPx = with(density) { snapThreshold.toPx() }
    val halfBallSizePx = ballSizePx / 2f

    var currentUnread by remember { mutableIntStateOf(unreadCount) }

    LaunchedEffect(unreadCount) {
        currentUnread = unreadCount
    }

    fun savePosition(pos: Offset) {
        context.putPrefInt(PreferKey.debugFloatingBallPosX, pos.x.roundToInt())
        context.putPrefInt(PreferKey.debugFloatingBallPosY, pos.y.roundToInt())
    }

    fun snapHalfIntoHorizontalEdge(currentOffset: Offset): Offset {
        val maxX = (containerSize.width - ballSizePx).coerceAtLeast(0f)
        val maxY = (containerSize.height - ballSizePx).coerceAtLeast(0f)
        val clampedY = currentOffset.y.coerceIn(0f, maxY)
        val targetX = when {
            currentOffset.x <= snapThresholdPx -> -halfBallSizePx
            maxX - currentOffset.x <= snapThresholdPx -> maxX + halfBallSizePx
            else -> currentOffset.x.coerceIn(0f, maxX)
        }
        return Offset(targetX, clampedY)
    }

    LaunchedEffect(containerSize) {
        if (initialized || containerSize.width <= 0 || containerSize.height <= 0) return@LaunchedEffect
        val savedX = context.getPrefInt(PreferKey.debugFloatingBallPosX, -1)
        val savedY = context.getPrefInt(PreferKey.debugFloatingBallPosY, -1)
        if (savedX >= 0 && savedY >= 0) {
            offset = Offset(savedX.toFloat(), savedY.toFloat())
        } else {
            val maxX = (containerSize.width - ballSizePx).coerceAtLeast(0f)
            val maxY = (containerSize.height - ballSizePx).coerceAtLeast(0f)
            offset = Offset(
                x = (maxX - endMarginPx - initialInsetPx).coerceAtLeast(0f),
                y = (maxY - bottomMarginPx - initialInsetPx).coerceAtLeast(0f)
            )
        }
        initialized = true
    }

    val startColor = lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, 0.28f)
    val endColor = lerp(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary, 0.22f)
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val glowColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        if (initialized) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(ballSize)
                    .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = startColor, spotColor = endColor)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colors = listOf(startColor, endColor)))
                    .border(1.5.dp, ringColor, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { },
                            onDragCancel = {
                                offset = snapHalfIntoHorizontalEdge(offset)
                                savePosition(offset)
                            },
                            onDragEnd = {
                                offset = snapHalfIntoHorizontalEdge(offset)
                                savePosition(offset)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxX = (containerSize.width - ballSizePx).coerceAtLeast(0f)
                                val maxY = (containerSize.height - ballSizePx).coerceAtLeast(0f)
                                offset = Offset(
                                    x = (offset.x + dragAmount.x).coerceIn(0f, maxX),
                                    y = (offset.y + dragAmount.y).coerceIn(0f, maxY)
                                )
                            }
                        )
                    }
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = glowColor
                ) {}

                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "调试日志",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )

                if (currentUnread > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 4.dp)
                    ) {
                        Text(
                            text = currentUnread.coerceAtMost(99).toString(),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmartDebugFloatingBall(
    currentRoute: String?,
    blackListRoutes: Set<String> = setOf("/splash", "/webview", "/reader"),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shouldShow = currentRoute !in blackListRoutes

    if (shouldShow) {
        DebugFloatingBall(
            onClick = onClick,
            modifier = modifier
        )
    }
}
