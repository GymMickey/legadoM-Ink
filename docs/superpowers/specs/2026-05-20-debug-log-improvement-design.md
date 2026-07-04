# 调试日志改进设计

## 问题概述

1. **流程日志不实时** — 书源流程（FLOW）子分类中，新日志不会自动出现，必须手动点击刷新按钮才能看到
2. **详情内容被截断** — 日志详情弹窗中，字段值被 `maxLines` 和 `take(N)` 硬编码截断，无法查看完整内容

---

## 问题一：流程日志实时刷新

### 根因分析

数据流：`addLog()` → `scheduleUpdate()` → 500ms debounce → `_logs.emit()` → ViewModel → UI

阻断点：

1. **500ms debounce** (`FlowLogRecorder.kt:634-642`) — `scheduleUpdate()` 不立即 emit，而是等待 500ms 后才通知订阅者
2. **GlobalScope 生命周期不匹配** (`FlowLogRecorder.kt:636`) — debounce 协程运行在 GlobalScope，不受 ViewModel 生命周期约束。当屏幕关闭后 debounce 触发 emit，没有订阅者接收，数据丢失
3. **LaunchedEffect(Unit) 只执行一次** (`DebugLogScreen.kt:108-111`) — 仅在首次组合时调用 `refreshFlowLogs()`，后续重新进入不会触发

### 修复方案

**方案 A（推荐）：缩短 debounce + 修复订阅补偿**

- 将 `UPDATE_DEBOUNCE_MS` 从 500ms 降至 100ms
- 在 `subscribeToFlowLogs()` 订阅建立时，立即调用 `refreshFlowLogs()` 作为补偿，确保不丢失数据
- `LaunchedEffect(Unit)` 改为在切换到 FLOW 子分类时也触发一次刷新

**方案 B：移除 debounce，改用 conflated channel**

- 用 `Channel<List<FlowLogItem>>(Channel.CONFLATED)` 替代 debounce 机制
- 每次 `addLog` 立即通过 channel 发送最新快照
- 更复杂但更精确

**选择方案 A**，因为改动最小，只需修改 2 个文件 3 处。

### 具体改动

#### FlowLogRecorder.kt
- `UPDATE_DEBOUNCE_MS`: `500L` → `100L`

#### DebugLogViewModel.kt
- `subscribeToFlowLogs()` 末尾立即调用 `refreshFlowLogs()` 作为初始值补偿

#### DebugLogScreen.kt
- `LaunchedEffect(Unit)` 改为 `LaunchedEffect(selectedSubCategory)`，当 `selectedSubCategory == SourceSubCategory.FLOW` 时刷新流程日志

---

## 问题二：详情内容可展开/折叠

### 根因分析

两处截断：

1. **`DetailRow` 的 `maxLines` 限制**：
   - `DebugLogDetailDialog.kt:432`: `maxLines = 3`
   - `FlowLogDetailDialog.kt:675`: `maxLines = 5`

2. **FlowLogDetailDialog 中的硬编码 `take(N)` 截断**：
   - 规则内容: `take(100)` (line 419)
   - 输入: `take(50)` (line 427)
   - 输出: `take(50)` (line 434)
   - JS 环境变量: `take(100)` (line 618)
   - 变量值: `take(100)` (line 851)
   - 变量原值: `take(50)` (line 859)
   - 字段结果: `take(50)` (line 1003)
   - 字段规则: `take(30)` (line 1011)

### 修复方案：点击展开/折叠

**修改 `DetailRow` 组件**（两个弹窗各自的实现都需修改）：

```
默认状态: maxLines = 3, 显示省略号
   ↓ 点击
展开状态: maxLines = Int.MAX_VALUE, 完整显示
```

- 使用 `remember { mutableStateOf(false) }` 跟踪展开状态
- `TextOverflow.Ellipsis` 仅在折叠状态下生效
- 展开后支持 `horizontalScroll` 避免长文本换行混乱
- 短文本（无需截断）不显示省略号，也不需要展开交互

**修改 FlowLogDetailDialog 中的硬编码截断**：

- 移除所有 `take(N)` + `"..."` 逻辑
- 替换为与 `DetailRow` 一致的可展开/折叠展示
- 对于 `RuleExecutionNodeView` 和 `VariableOperationItem` 等子组件，同样应用展开/折叠模式

### 具体改动

#### DebugLogDetailDialog.kt

`DetailRow` 函数（line 408-434）：

```kotlin
@Composable
private fun DetailRow(label: String, value: String, searchQuery: String) {
    var expanded by remember { mutableStateOf(false) }
    val isTruncated = value.length > 80  // 简单启发式判断

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isTruncated) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = highlightText(label, searchQuery),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = highlightText(value, searchQuery),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .then(if (expanded) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            maxLines = if (expanded) Int.MAX_VALUE else 3
        )
    }
}
```

#### FlowLogDetailDialog.kt

1. 同样修改 `DetailRow`（line 651-678），将 `maxLines = 5` 改为展开/折叠逻辑

2. `RuleExecutionNodeView`（line 378-477）：
   - `node.ruleContent.take(100)` → 展开/折叠
   - `input.take(50)` → 展开/折叠
   - `output.take(50)` → 展开/折叠

3. `EnvVarRow`（line 603-623）：
   - `value.take(100)` → 展开/折叠

4. `VariableOperationItem`（line 814-867）：
   - `value.take(100)` → 展开/折叠
   - `oldValue.take(50)` → 展开/折叠

5. `FieldFillRecordView`（line 973-1019）：
   - `getResultPreview(50)` → 展开/折叠
   - `getRulePreview(30)` → 展开/折叠

---

## 文件变更清单

| 文件 | 改动内容 |
|------|---------|
| `FlowLogRecorder.kt` | debounce 500ms → 100ms |
| `DebugLogViewModel.kt` | subscribeToFlowLogs() 增加初始刷新 |
| `DebugLogScreen.kt` | LaunchedEffect 改为监听子分类切换 |
| `DebugLogDetailDialog.kt` | DetailRow 可展开/折叠 |
| `FlowLogDetailDialog.kt` | DetailRow + 子组件可展开/折叠，移除 take(N) |

## 验证方式

1. 安装 appMaxDebug 到设备
2. 开启调试日志悬浮球
3. 触发书源搜索操作
4. 进入 书源 → 流程 子分类，确认新日志自动出现（无需手动刷新）
5. 点击任意日志查看详情，确认长文本显示省略号
6. 点击该行，确认展开显示完整内容
7. 再次点击，确认折叠回缩
