package io.legado.app.ui.main.homepage.modules

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.ui.widget.components.card.GlassCard

@Composable
private fun rememberShimmerBrush(): Brush {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val offset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.surface
    val colors = remember(baseColor, highlightColor) {
        listOf(baseColor, highlightColor, baseColor)
    }
    return Brush.linearGradient(
        colors = colors,
        start = Offset(offset * 300f, 0f),
        end = Offset(offset * 300f + 300f, 0f),
    )
}

@Composable
private fun SkeletonBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(rememberShimmerBrush())
    )
}

@Composable
fun HomepageModuleSkeleton(
    type: HomepageModuleType,
    modifier: Modifier = Modifier,
) {
    when (type) {
        HomepageModuleType.Banner -> {
            LazyRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(6) {
                    SkeletonBox(
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(5f / 7f),
                        cornerRadius = 12.dp,
                    )
                }
            }
        }
        HomepageModuleType.Card -> {
            LazyRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(5) {
                    Column(modifier = Modifier.width(120.dp)) {
                        SkeletonBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(5f / 7f),
                            cornerRadius = 16.dp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SkeletonBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                        )
                    }
                }
            }
        }
        HomepageModuleType.Grid, HomepageModuleType.InfiniteGrid -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        repeat(3) {
                            Column(modifier = Modifier.weight(1f)) {
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(5f / 7f),
                                    cornerRadius = 4.dp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        HomepageModuleType.Ranking -> {
            GlassCard(modifier = modifier, cornerRadius = 16.dp) {
                Column(modifier = Modifier.padding(12.dp)) {
                    repeat(5) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            SkeletonBox(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(14.dp))
                            SkeletonBox(
                                modifier = Modifier
                                    .width(52.dp)
                                    .aspectRatio(5f / 7f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.7f)
                                        .height(14.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.4f)
                                        .height(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        HomepageModuleType.GridRanking -> {
            GlassCard(modifier = modifier, cornerRadius = 20.dp) {
                Column(modifier = Modifier.padding(12.dp)) {
                    repeat(4) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            SkeletonBox(
                                modifier = Modifier
                                    .width(48.dp)
                                    .aspectRatio(5f / 7f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SkeletonBox(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(14.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .height(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        HomepageModuleType.Waterfall -> {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(2) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SkeletonBox(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                cornerRadius = 16.dp,
                            )
                            Column(modifier = Modifier.padding(8.dp)) {
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(14.dp),
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                SkeletonBox(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .height(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        HomepageModuleType.ButtonGroup -> {}
        HomepageModuleType.Unknown -> {}
    }
}
