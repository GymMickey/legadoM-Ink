package io.legado.app.ui.module

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageTopBarContainerColor

/**
 * 模块状态主界面
 * @param viewModel ViewModel实例
 * @param onBackClick 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleStatusScreen(
    viewModel: ModuleStatusViewModel = viewModel(),
    onBackClick: () -> Unit
) {
    // 收集状态流，自动更新UI
    val modules by viewModel.modules.collectAsState()
    val topBarColor = pageTopBarContainerColor()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarColor,
                    scrolledContainerColor = topBarColor,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSecondary,
                    titleContentColor = MaterialTheme.colorScheme.onSecondary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSecondary
                ),
                title = {
                    Text(
                        text = "模块运行状态",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 刷新按钮
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        // 模块列表
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(modules, key = { it.name }) { module ->
                ModuleStatusCard(module)
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/**
 * 模块状态卡片
 * @param module 模块状态项
 */
@Composable
private fun ModuleStatusCard(module: ModuleStatusItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pageCardContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示点
            StatusDot(module.status)
            Spacer(modifier = Modifier.width(12.dp))
            // 模块名称
            Text(
                text = module.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            // 状态文本
            Text(
                text = module.status.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = module.status.color()
            )
        }
    }
}

/**
 * 状态指示点（圆形色块）
 * @param status 运行状态
 */
@Composable
private fun StatusDot(status: ModuleRunStatus) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(status.color(), CircleShape)
    )
}

/**
 * 获取状态对应的中文标签
 */
private fun ModuleRunStatus.label(): String {
    return when (this) {
        ModuleRunStatus.RUNNING -> "运行中"
        ModuleRunStatus.IDLE -> "空闲"
        ModuleRunStatus.ERROR -> "异常"
        ModuleRunStatus.DISABLED -> "未启用"
    }
}

/**
 * 获取状态对应的颜色
 */
@Composable
private fun ModuleRunStatus.color(): Color {
    return when (this) {
        ModuleRunStatus.RUNNING -> Color(0xFF2E7D32) // 绿色
        ModuleRunStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant // 灰色
        ModuleRunStatus.ERROR -> MaterialTheme.colorScheme.error // 红色
        ModuleRunStatus.DISABLED -> MaterialTheme.colorScheme.outline // 轮廓色
    }
}
