# DebugFloatingBallManager 初步审阅建议

审阅范围：

- `app/src/main/java/io/legado/app/ui/debuglog/DebugFloatingBallManager.kt`
- `app/src/main/java/io/legado/app/ui/debuglog/DebugLogPanelDialog.kt`
- `app/src/main/java/io/legado/app/ui/debuglog/components/DebugFloatingBall.kt`
- 关联接入点：`BaseActivity`、`OtherConfigFragment`、`DebugEventCenter`、`DebugLogViewModel`

## 结论

当前实现可以作为 MVP 验证入口，但还不适合直接长期启用。最大的问题不是 UI 样式，而是悬浮球通过 `postDelayed + 静态 Activity/View 引用 + WRAP_CONTENT ComposeView` 管理生命周期，容易在 Activity 切换、快速开关、点开面板、旋转屏幕时出现重复挂载、残留 View、Activity 泄漏或悬浮球消失。

建议优先修生命周期和挂载模型，再做未读数、位置持久化、过滤页面等增强功能。

## 高优先级问题

### 1. `show()` 的延迟挂载存在重复 View 和泄漏风险

位置：`DebugFloatingBallManager.kt:40-76`

`show()` 创建 `ComposeView` 后 100ms 才 `addView`，但 `isShowing` 直到真正 add 后才置为 `true`。如果这 100ms 内再次调用 `show()`，会创建多个 `ComposeView`，多个延迟任务都可能执行 `rootView.addView(...)`。因为 `floatingBallView` 会被后一次覆盖，前一次成功挂载的 View 后续可能无法被 `hide()` 移除。

建议：

- 增加一个 `showToken` 或 `attachGeneration`，延迟任务执行前校验 token 是否仍然有效。
- `show()` 开头如果已有 `floatingBallView != null` 或 `isAttaching == true`，直接返回。
- `rootView.addView` 前检查 `composeView.parent == null`。
- 失败或取消时统一清理状态。

### 2. `hide()` 和延迟 `show()` 之间有竞态

位置：`DebugFloatingBallManager.kt:57-75`、`DebugFloatingBallManager.kt:83-95`、`DebugFloatingBallManager.kt:103-107`

`onActivityPaused()` 调用 `hide()`，但没有把 `currentActivity` 置空。典型场景：

1. `onResume()` 调用 `show()`，注册 100ms 后挂载。
2. 100ms 前 Activity 进入 `onPause()`，执行 `hide()`。
3. 延迟任务到点后仍满足 `currentActivity == activity && AppConfig.debugLogFloatingBall`。
4. 悬浮球被加到已经暂停的 Activity 上。

建议：

- `onActivityPaused(activity)` 中如果是当前 Activity，应取消 pending show，并将 `currentActivity = null` 或至少让 token 失效。
- 延迟任务中增加 Activity 状态判断，如 `!activity.isFinishing`、`!activity.isDestroyed`，更稳的是结合 `Lifecycle.State.RESUMED` 判断。
- 能不用延迟就尽量不用延迟；如果只是等待 decorView ready，可以在 `decorView.post { ... }` 中做一次挂载，并配合 token。

### 3. ComposeView 的 composition 释放策略不适合临时 add/remove

位置：`DebugFloatingBallManager.kt:116-124`、`DebugLogPanelDialog.kt:71-81`

当前使用 `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`。这意味着 View 从父容器移除时，composition 可能不会立刻释放，而是等 Activity 生命周期销毁。悬浮球和面板都是临时 add/remove 的 overlay，更适合在 detach 时释放。

建议：

- 悬浮球和面板都优先改为 `ViewCompositionStrategy.DisposeOnDetachedFromWindow`。
- 如果面板希望复用 Activity 级 ViewModel，也要明确这是有意行为；否则 dismiss 后相关 Compose collection 可能继续保留到 Activity 销毁。

### 4. 拖拽坐标和 View 尺寸模型不匹配

位置：`DebugFloatingBallManager.kt:59-66`、`DebugFloatingBall.kt:50-92`

Manager 把 `ComposeView` 以 `WRAP_CONTENT` 放在右下角；组件内部又用 `Modifier.offset` 按屏幕宽高拖拽。这样移动的是 Compose 内容，不是 Android View 本身。风险是：

- 悬浮球绘制位置可能离开 `ComposeView` 的原始触摸区域，拖远后触摸命中不稳定。
- `screenWidthPx - ballSizePx` 是按整屏计算，但外层 View 本身已经在右下角，偏移后可能画到不符合预期的位置。
- 后续做吸边、位置保存、避让系统栏都会更复杂。

建议二选一：

- 更推荐：外层 `ComposeView` 使用 `MATCH_PARENT` 作为透明 overlay，悬浮球在 Compose 内 `Box(Modifier.fillMaxSize())` 里用 `align`/`offset` 管理位置。
- 或者：保留小 View，但拖拽时更新 `FrameLayout.LayoutParams` 的 `leftMargin/topMargin`，不要在 Compose 内按全屏坐标 offset。

### 5. 点击打开面板后，关闭面板不会恢复悬浮球

位置：`DebugFloatingBallManager.kt:132-140`、`DebugLogPanelDialog.kt:51-69`

点击悬浮球时先 `hide()`，再打开 `DebugLogPanelDialog`。但 `DebugLogPanelDialog.dismiss()` 只移除面板，没有通知 `DebugFloatingBallManager` 重新显示。用户关闭面板后，悬浮球会一直消失，直到 Activity 重新 resume 或重新切换配置。

建议：

- `DebugLogPanelDialog.show(activity, onDismiss = { DebugFloatingBallManager.show(activity) })`。
- 或在 Manager 中提供 `openPanel(activity)`，由 Manager 统一处理 hide、show、dismiss 回调。
- 打开面板的 200ms 延迟也应带 Activity 状态校验，避免 Activity 已销毁后继续 `show(activity)`。

## 中优先级问题

### 6. `pendingShow` 没有实际参与逻辑

位置：`DebugFloatingBallManager.kt:28-37`

`pendingShow` 只被赋值，没有被读取。`onActivityResumed()` 也不看它，所以当前变量不能防止竞态，也不能表达“配置已开启但暂时没有 Activity”的状态。

建议：

- 如果只依赖 `AppConfig.debugLogFloatingBall`，删除 `pendingShow`。
- 如果想表达挂起显示，应在 `onActivityResumed()` 中读取，并在 `hide/cancel` 时明确重置。

### 7. `onActivityResumed()` 有重复判断

位置：`DebugFloatingBallManager.kt:97-100`

当前条件是：

```kotlin
AppConfig.debugLogFloatingBall && !isShowing && !isShowing
```

第二个 `!isShowing` 是重复条件。建议顺手修掉，并把判断扩展成 `!isShowing && !isAttaching`。

### 8. 静态持有 Activity/View，需要更严格清理

位置：`DebugFloatingBallManager.kt:25-27`、`DebugLogPanelDialog.kt:22-24`

两个 object 都持有 `Activity` 和 `View`。只要延迟任务、composition 或状态没释放干净，就可能延长 Activity 生命周期。

建议：

- 所有 `catch` 分支都要恢复 `currentActivity/floatingBallView/isShowing/isAttaching`。
- `onActivityDestroyed()` 要同时取消 pending show、移除 View、清空 Activity。
- `DebugLogPanelDialog` 最好也提供 `onActivityDestroyed(activity)`，避免面板 View 残留。

### 9. `DebugFloatingBall` 的拖拽和点击可能互相干扰

位置：`DebugFloatingBall.kt:71-95`

`pointerInput(detectDragGestures)` 和 `clickable` 串在同一个 Modifier 上。实际交互中需要确认拖动释放是否会误触发点击，或者点击是否受拖拽手势消费影响。

建议：

- 记录 `isDragging` 或累计拖动距离，超过 touch slop 后不触发点击。
- 或用一个统一的 `pointerInput` 同时处理 tap/drag，避免两个手势修饰符抢事件。

### 10. 未接入未读数/错误数，Badge 参数目前只是占位

位置：`DebugFloatingBall.kt:44-62`、`DebugFloatingBallManager.kt:132-141`

`DebugFloatingBall` 支持 `unreadCount`，但 Manager 调用时没有传入，当前 badge 永远不显示。

建议：

- 从 `DebugEventCenter.eventFlow` 或一个轻量 `StateFlow<Int>` 维护未读/错误数量。
- 面板打开时清零未读数。
- 优先显示错误/警告数量，比单纯总日志数量更有价值。

### 11. 缺少页面过滤策略

位置：`BaseActivity.kt:98-110`、`DebugFloatingBall.kt:125-139`

现在所有继承 `BaseActivity` 的页面都会尝试显示悬浮球。`SmartDebugFloatingBall` 里有黑名单思路，但没有被 Manager 使用。

建议先过滤：

- 阅读页、漫画阅读页、视频播放页、启动页、WebView 全屏页。
- Debug 日志面板所在 Activity 或相关页面。
- 也可以给 `BaseActivity` 增加可覆盖属性，如 `open val showDebugFloatingBall = true`。

### 12. 系统栏、横竖屏和多窗口适配不足

位置：`DebugFloatingBallManager.kt:63-65`、`DebugFloatingBall.kt:54-89`

初始位置用固定 `bottomMargin = 100dp`、`marginEnd = 16dp`，拖拽边界用 `configuration.screenWidthDp/screenHeightDp`。这没有考虑导航栏、状态栏、刘海、横屏、多窗口、IME。

建议：

- 用 `WindowInsets` 或 rootView 可用区域计算边界。
- 配置变化后重新 clamp 当前 offset。
- 初始位置不要用魔法值，至少抽成常量并注明原因。

## 代码质量和可维护性

### 13. 文本和注释存在编码损坏

位置：`DebugFloatingBall.kt:37-38`、`DebugFloatingBall.kt:98-101`，以及部分 DebugLog 相关文件

能看到类似 `璋冭瘯鏃ュ織` 的乱码。除了影响可读性，`contentDescription` 乱码还会影响无障碍体验。

建议：

- 统一确认源码编码为 UTF-8。
- 修复注释、Toast、contentDescription、页面标题。
- 后续中文展示文本尽量放到 `strings.xml`。

### 14. import 和 API 可以清理

位置：`DebugFloatingBallManager.kt:6`、`DebugFloatingBallManager.kt:13-15`、`DebugLogPanelDialog.kt:15`

`View`、`mutableStateOf`、`getValue`、`setValue`、`LocalConfiguration` 等存在未使用导入。建议清掉，避免后续判断状态时混淆。

### 15. 异常处理不应只 `printStackTrace`

位置：`DebugFloatingBallManager.kt:71-79`、`DebugLogPanelDialog.kt:46-47`

这是调试功能，失败时更应该进入项目现有日志体系，方便定位。

建议：

- 用 `AppLog.put(...)` 或项目内统一日志工具记录。
- 对 `IllegalStateException: view already has parent`、`BadTokenException`、Activity destroyed 等场景单独给出清晰信息。

## 推荐重构方向

### 阶段 1：先把生命周期修稳

- 删除或重做 `pendingShow`。
- 引入 `isAttaching` 和 `showToken`。
- `hide()` 同步让 token 失效，移除 View 后再最终清理。
- `onPause/onDestroy` 取消延迟挂载。
- ComposeView 改为 `DisposeOnDetachedFromWindow`。
- 面板 dismiss 后恢复悬浮球。

### 阶段 2：调整 overlay 模型

推荐把悬浮球改成全屏透明 Compose overlay：

```text
decorView
└── DebugOverlayComposeView MATCH_PARENT
    └── Box fillMaxSize
        └── DebugFloatingBall align/offset
```

这样拖拽坐标、点击命中、吸边、系统栏避让都会更自然。

### 阶段 3：补齐体验功能

- 未读/错误数 badge。
- 长按隐藏或进入设置。
- 位置保存。
- 页面黑名单。
- 横竖屏、多窗口、阅读页避让。
- 面板导出按钮真正接到 `DebugLogViewModel.exportFilteredLogs()` 或 `exportAllLogs()`。

## 建议的最小验收清单

- 快速开关 `debugLogFloatingBall` 20 次，不出现重复悬浮球。
- 连续切换多个 Activity，不出现旧页面残留悬浮球。
- 点击悬浮球打开面板，关闭面板后悬浮球能恢复。
- 旋转屏幕后不崩溃、不泄漏、不跑到屏幕外。
- 在阅读页/视频页等高优先级沉浸页面不会遮挡核心操作。
- 拖拽后点击命中正常，不出现拖动结束误打开面板。
- Android Studio Layout Inspector 中移除悬浮球后没有残留多余 ComposeView。
