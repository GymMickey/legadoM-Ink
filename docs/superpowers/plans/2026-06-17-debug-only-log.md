# 调试专属日志（Debug-Only Log）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 允许用户按 `DebugCategory` 粒度配置"调试专属"模式——被标记的 category 日志只进调试日志界面（`DebugLogScreen`），不再进普通日志界面（`AppLogDialog`）。

**架构：** 在 `AppLog.put()` / `putSource()` 内新增 `category` 可选参数 + 路由分流判断；`AppConfig` 维护总开关和被标记 category 集合；调试界面新增 `DebugCategoryVisibilityDialog` 弹窗用于运行时配置。向后兼容，默认零行为变化。

**技术栈：** Kotlin、AndroidX Preference (SharedPreferences)、Jetpack Compose (Material3)、Coroutines。

---

## 范围

本次实现覆盖设计规格 [debug-only-log-design.md](file:///g:/Project/legado_Plus/legado_Plus/docs/superpowers/specs/2026-06-17-debug-only-log-design.md) 的 §1-§9。§10 后续扩展不在本次范围。

---

## 文件结构

| 文件 | 角色 | 改动类型 |
|------|------|---------|
| `app/src/main/java/io/legado/app/constant/PreferKey.kt` | 偏好设置键名常量 | 编辑（+2 const） |
| `app/src/main/java/io/legado/app/help/config/AppConfig.kt` | 全局配置单例 | 编辑（+2 var 属性） |
| `app/src/main/java/io/legado/app/constant/AppLog.kt` | 日志输出核心 | 编辑（`put()` / `putSource()` 签名扩展） |
| `app/src/main/java/io/legado/app/ui/debuglog/components/DebugCategoryVisibilityDialog.kt` | 分类可见性设置弹窗 | **新建** |
| `app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt` | 调试日志主界面 | 编辑（菜单加入口 + 注册弹窗） |
| `app/src/main/assets/web/help/md/updateLog.md` | 变更日志 | 编辑（+1 行） |
| `app/src/test/java/io/legado/app/help/config/AppConfigTest.kt` | 单元测试 | **新建** |

---

## 任务 1：添加 `PreferKey` 常量

**文件：**
- 修改：`app/src/main/java/io/legado/app/constant/PreferKey.kt:48-52`（在 `debugLogFloatingBall` 系列 key 附近）

- [ ] **步骤 1：添加两个 const val**

在 `PreferKey.kt` 中 `debugLogFloatingBall` 常量之后插入：

```kotlin
const val debugLogOnlyEnabled = "debugLogOnlyEnabled" // 调试专属模式总开关
const val debugLogOnlyCategories = "debugLogOnlyCategories" // 标记为"只进调试界面"的 DebugCategory 集合，逗号分隔
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/constant/PreferKey.kt
git commit -m "feat(debug-log): add PreferKey constants for debug-only log mode"
```

---

## 任务 2：编写 `AppConfig.debugLogOnlyCategories` 的失败测试

**文件：**
- 测试：`app/src/test/java/io/legado/app/help/config/AppConfigTest.kt`（新建）

- [ ] **步骤 1：检查测试目录是否存在**

运行（PowerShell）：
```bash
Test-Path app/src/test/java/io/legado/app/help/config
```

预期：目录可能不存在，需创建。

- [ ] **步骤 2：创建测试文件**

新建 `app/src/test/java/io/legado/app/help/config/AppConfigTest.kt`：

```kotlin
package io.legado.app.help.config

import io.legado.app.model.debug.DebugCategory
import io.legado.app.utils.putPrefString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.init.appCtx

/**
 * AppConfig.debugLogOnlyCategories 持久化与边界条件测试
 */
class AppConfigTest {

    @After
    fun cleanup() {
        appCtx.removePref("debugLogOnlyCategories")
        appCtx.removePref("debugLogOnlyEnabled")
    }

    @Test
    fun `debugLogOnlyCategories 序列化与反序列化往返一致`() {
        val original = setOf(
            DebugCategory.SOURCE,
            DebugCategory.RSS,
            DebugCategory.APP
        )
        AppConfig.debugLogOnlyCategories = original
        val read = AppConfig.debugLogOnlyCategories
        assertEquals(original, read)
    }

    @Test
    fun `debugLogOnlyCategories 写入 ALL 时读取应过滤掉`() {
        AppConfig.debugLogOnlyCategories = setOf(DebugCategory.ALL, DebugCategory.SOURCE)
        val read = AppConfig.debugLogOnlyCategories
        assertTrue("ALL 应当被过滤", DebugCategory.ALL !in read)
        assertTrue("SOURCE 应当保留", DebugCategory.SOURCE in read)
    }

    @Test
    fun `debugLogOnlyCategories 空集合读写应为空`() {
        AppConfig.debugLogOnlyCategories = emptySet()
        assertEquals(emptySet<DebugCategory>(), AppConfig.debugLogOnlyCategories)
    }

    @Test
    fun `debugLogOnlyCategories 无效 enum name 应被忽略`() {
        appCtx.putPrefString("debugLogOnlyCategories", "SOURCE,FOO,RSS")
        val read = AppConfig.debugLogOnlyCategories
        assertEquals(setOf(DebugCategory.SOURCE, DebugCategory.RSS), read)
    }
}
```

> **注意**：需确认 `appCtx.removePref(key: String)` 扩展函数在 `io.legado.app.utils` 包下存在。后续任务 3 实施前先确认。

- [ ] **步骤 3：运行测试验证失败**

运行：
```bash
./gradlew :app:testAppMaxDebugUnitTest --tests "io.legado.app.help.config.AppConfigTest"
```

预期：FAIL（`AppConfig.debugLogOnlyCategories` 尚未定义）。

- [ ] **步骤 4：Commit（仅测试）**

```bash
git add app/src/test/java/io/legado/app/help/config/AppConfigTest.kt
git commit -m "test(debug-log): add AppConfig.debugLogOnlyCategories tests (red)"
```

---

## 任务 3：实现 `AppConfig.debugLogOnlyEnabled` 和 `debugLogOnlyCategories`

**文件：**
- 修改：`app/src/main/java/io/legado/app/help/config/AppConfig.kt`

- [ ] **步骤 1：添加 import**

在 `AppConfig.kt` 顶部 import 区添加：

```kotlin
import io.legado.app.model.debug.DebugCategory
```

- [ ] **步骤 2：添加两个 var 属性**

在 `AppConfig.kt` 中 `var recordLog = ...` 之后插入：

```kotlin
// ==================== 调试专属日志模式 ====================
/** 调试专属模式总开关，控制按 category 路由分流功能是否启用 */
var debugLogOnlyEnabled: Boolean
    get() = appCtx.getPrefBoolean(PreferKey.debugLogOnlyEnabled, true)
    set(value) { appCtx.putPrefBoolean(PreferKey.debugLogOnlyEnabled, value) }

/** 被标记为"只进调试界面"的 DebugCategory 集合（持久化为逗号分隔字符串） */
var debugLogOnlyCategories: Set<DebugCategory>
    get() {
        val raw = appCtx.getPrefString(PreferKey.debugLogOnlyCategories) ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { name -> runCatching { DebugCategory.valueOf(name) }.getOrNull() }
            .filter { it != DebugCategory.ALL }
            .toSet()
    }
    set(value) {
        val names = value.filter { it != DebugCategory.ALL }.joinToString(",") { it.name }
        appCtx.putPrefString(PreferKey.debugLogOnlyCategories, names)
    }
```

- [ ] **步骤 3：确认 `removePref` 扩展存在**

在 `app/src/main/java/io/legado/app/utils` 下搜索：

```bash
grep -r "fun.*removePref" app/src/main/java/io/legado/app/utils/
```

如果不存在，添加到 `PreferenceExt.kt`（或类似文件）：

```kotlin
fun Context.removePref(key: String) {
    appCtx.getSharedPreferences(defaultSharedPreferencesName, 0).edit().remove(key).apply()
}
```

> **替代方案**：测试中改用 `appCtx.putPrefString(key, "")` 模拟清理。

- [ ] **步骤 4：运行任务 2 的测试，验证通过**

运行：
```bash
./gradlew :app:testAppMaxDebugUnitTest --tests "io.legado.app.help.config.AppConfigTest"
```

预期：4 个测试全部 PASS。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/io/legado/app/help/config/AppConfig.kt
git commit -m "feat(debug-log): add AppConfig debugLogOnlyEnabled and debugLogOnlyCategories"
```

---

## 任务 4：改造 `AppLog.put()` —— 新增 category 参数与路由分流

**文件：**
- 修改：`app/src/main/java/io/legado/app/constant/AppLog.kt:25-57`

- [ ] **步骤 1：在 `put()` 签名中新增 `category` 可选参数**

替换原 `put()` 签名为：

```kotlin
@Synchronized
fun put(
    message: String?,
    throwable: Throwable? = null,
    toast: Boolean = false,
    dialogName: String? = null,
    category: DebugCategory = DebugCategory.APP
) {
    message ?: return
    if (toast) {
        appCtx.toastOnUi(message)
    }

    // 路由分流：被标记的 category 在启用调试专属模式时跳过普通界面
    val isDebugOnly = AppConfig.debugLogOnlyEnabled && category in AppConfig.debugLogOnlyCategories

    if (!isDebugOnly) {
        if (mLogs.size > 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    DebugLogScope.launch {
        DebugEventCenter.emit(
            DebugEvent(
                level = if (throwable != null) DebugLevel.ERROR else DebugLevel.INFO,
                category = category,
                message = message,
                detail = throwable?.stackTraceToString(),
                throwable = throwable,
                dialogName = dialogName
            )
        )
    }
}
```

- [ ] **步骤 2：验证 `AppLog.kt` 顶部 import 已包含 `DebugCategory`**

打开 `AppLog.kt` 第 1-15 行，确认已有：

```kotlin
import io.legado.app.model.debug.DebugCategory
```

（已存在，无需新增）

- [ ] **步骤 3：构建验证（确保编译通过）**

运行：
```bash
./gradlew :app:compileAppMaxDebugKotlin
```

预期：BUILD SUCCESSFUL。无 WARNING 关于未使用参数。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/io/legado/app/constant/AppLog.kt
git commit -m "feat(debug-log): route put() through category-based debug-only filter"
```

---

## 任务 5：改造 `AppLog.putSource()` —— 同样新增 category 参数

**文件：**
- 修改：`app/src/main/java/io/legado/app/constant/AppLog.kt:59-89`

- [ ] **步骤 1：在 `putSource()` 签名中新增 `category` 可选参数**

替换原 `putSource()` 签名为：

```kotlin
@Synchronized
fun putSource(
    message: String?,
    throwable: Throwable? = null,
    subCategory: SourceSubCategory = SourceSubCategory.UPDATE,
    dialogName: String? = null,
    category: DebugCategory = DebugCategory.SOURCE
) {
    message ?: return

    val isDebugOnly = AppConfig.debugLogOnlyEnabled && category in AppConfig.debugLogOnlyCategories

    if (!isDebugOnly) {
        if (mSourceLogs.size > 200) {
            mSourceLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("SourceLog", message)
        } else {
            LogUtils.d("SourceLog", "$message\n${throwable.stackTraceToString()}")
        }
        mSourceLogs.add(0, Triple(System.currentTimeMillis(), message, throwable))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    DebugLogScope.launch {
        DebugEventCenter.emit(
            DebugEvent(
                level = if (throwable != null) DebugLevel.ERROR else DebugLevel.INFO,
                category = category,
                subCategory = subCategory,
                message = message,
                detail = throwable?.stackTraceToString(),
                throwable = throwable,
                dialogName = dialogName
            )
        )
    }
}
```

- [ ] **步骤 2：构建验证**

运行：
```bash
./gradlew :app:compileAppMaxDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/constant/AppLog.kt
git commit -m "feat(debug-log): route putSource() through category-based debug-only filter"
```

---

## 任务 6：创建 `DebugCategoryVisibilityDialog` 组件

**文件：**
- 新建：`app/src/main/java/io/legado/app/ui/debuglog/components/DebugCategoryVisibilityDialog.kt`

- [ ] **步骤 1：阅读参考组件 `DebugCategoryTabs.kt` 的样式**

阅读 `app/src/main/java/io/legado/app/ui/debuglog/components/DebugCategoryTabs.kt`，了解项目内 Compose 弹窗的现有风格（颜色、间距、字号）。如果存在可复用的 `AppConfirmDialog` 或 `BaseDialog`，优先使用。

- [ ] **步骤 2：新建 `DebugCategoryVisibilityDialog.kt`**

```kotlin
package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.debug.DebugCategory

/**
 * 分类可见性设置弹窗
 *
 * 让用户配置哪些 DebugCategory 的日志"只进调试界面"。
 * - 顶部：总开关 debugLogOnlyEnabled
 * - 中部：每个 category 一行 Switch（APP / NETWORK / RULE / SOURCE / RSS / TOAST / CHECK / CRASH）
 * - 底部：关闭按钮
 */
@Composable
fun DebugCategoryVisibilityDialog(
    onDismiss: () -> Unit
) {
    var enabled by remember { mutableStateOf(AppConfig.debugLogOnlyEnabled) }
    var selected by remember { mutableStateOf(AppConfig.debugLogOnlyCategories) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类可见性") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 总开关
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用「调试专属」模式",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "勾选后，对应分类的日志将只在调试界面显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            AppConfig.debugLogOnlyEnabled = it
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 各 category 开关
                DebugCategory.entries
                    .filter { it != DebugCategory.ALL }
                    .forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${category.displayName} (${category.name})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = category in selected,
                                enabled = enabled,
                                onCheckedChange = { checked ->
                                    val newSet = if (checked) {
                                        selected + category
                                    } else {
                                        selected - category
                                    }
                                    selected = newSet
                                    AppConfig.debugLogOnlyCategories = newSet
                                }
                            )
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
```

- [ ] **步骤 3：构建验证**

运行：
```bash
./gradlew :app:compileAppMaxDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/components/DebugCategoryVisibilityDialog.kt
git commit -m "feat(debug-log): add DebugCategoryVisibilityDialog for per-category config"
```

---

## 任务 7：在 `DebugLogScreen` 接入入口

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt`

- [ ] **步骤 1：添加 `Visibility` 图标 import**

在 imports 区域（约第 17-29 行）添加：

```kotlin
import androidx.compose.material.icons.filled.Visibility
```

- [ ] **步骤 2：添加新状态变量**

在 `DebugLogScreen` composable 函数体中，`showExecutionStatus` 之后（约第 141 行）添加：

```kotlin
var showCategoryVisibilityDialog by remember { mutableStateOf(false) }
```

- [ ] **步骤 3：在菜单"精准管理"和"其他设置"之间插入新项**

在 `DebugLogScreen.kt` 第 249-275 行（"精准管理" DropdownMenuItem 之后，"其他设置" DropdownMenuItem 之前）插入：

```kotlin
DropdownMenuItem(
    text = { Text("分类可见性") },
    onClick = {
        showOverflowMenu = false
        showCategoryVisibilityDialog = true
    },
    leadingIcon = {
        Icon(Icons.Default.Visibility, contentDescription = null)
    },
    colors = menuItemColors
)
```

- [ ] **步骤 4：在 Scaffold 末尾、`Box` 外注册弹窗**

在 `DebugLogScreen.kt` 末尾的 `Scaffold` 闭包内（`Scaffold { paddingValues -> ... }` 的最外层 `Column` 之前，或者 `Scaffold` 闭包之后但在 `DebugLogScreen` 函数体结束前），添加：

```kotlin
if (showCategoryVisibilityDialog) {
    DebugCategoryVisibilityDialog(
        onDismiss = { showCategoryVisibilityDialog = false }
    )
}
```

> 放置位置原则：放在 `Scaffold` 内容 lambda 内的最外层（与 `Column` 同级），这样弹窗会覆盖整个屏幕。

- [ ] **步骤 5：构建验证**

运行：
```bash
./gradlew :app:compileAppMaxDebugKotlin
```

预期：BUILD SUCCESSFUL。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt
git commit -m "feat(debug-log): add category visibility menu entry in DebugLogScreen"
```

---

## 任务 8：更新变更日志

**文件：**
- 修改：`app/src/main/assets/web/help/md/updateLog.md:19`

- [ ] **步骤 1：定位 2026/06/17 章节并添加新行**

打开 `app/src/main/assets/web/help/md/updateLog.md`，在 `**2026/06/17**` 章节下找到合适的插入位置（建议在最新功能行附近），添加：

```markdown
- 新增"分类可见性"功能：可在调试界面设置指定分类的日志只显示在调试界面，不进入普通日志界面
```

> 注意：保留原有的 2026/06/17 章节标题和已有内容，只新增一行。

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/assets/web/help/md/updateLog.md
git commit -m "docs: add changelog for debug-only log feature"
```

---

## 任务 9：手动验证

**文件：** 无（验证步骤）

- [ ] **步骤 1：编译并安装 debug 版本**

```bash
./gradlew :app:installAppMaxDebug
```

预期：BUILD SUCCESSFUL，安装成功。

- [ ] **步骤 2：场景 1 — 默认状态**

1. 启动应用
2. 进入"关于"页面，打开"应用日志"对话框
3. 触发任意操作（如刷新书源）
4. **预期**：普通界面有日志，调试界面也有日志

- [ ] **步骤 3：场景 2 — 勾选 `APP` 分类**

1. 通过调试球或菜单打开"调试日志"界面
2. 右上角"更多" → "分类可见性"
3. 打开"启用「调试专属」模式"开关
4. 勾选"应用 (APP)" 行
5. 点击"关闭"
6. 触发任意会调用 `AppLog.put(...)` 的操作（如打开书）
7. **预期**：普通界面**不出现**新日志，调试界面**出现**新日志

- [ ] **步骤 4：场景 3 — 关闭总开关**

1. 重新打开"分类可见性"弹窗
2. 关闭"启用「调试专属」模式"开关
3. 触发任意操作
4. **预期**：普通界面**出现**新日志（回退到原行为），即使 category 仍被勾选

- [ ] **步骤 5：场景 4 — 升级前行为**

1. 卸载应用 → 重新安装（不清数据时跳过此步）
2. 不进入"分类可见性"弹窗
3. 触发任意操作
4. **预期**：行为与升级前完全一致（所有日志同时进两个界面）

- [ ] **步骤 6：运行完整单元测试套件**

```bash
./gradlew :app:testAppMaxDebugUnitTest
```

预期：所有测试 PASS，无回归。

---

## 任务 10：Lint 检查

- [ ] **步骤 1：运行 Android Lint**

```bash
./gradlew :app:lintAppMaxDebug
```

预期：无新增 ERROR 级别 lint 警告。

- [ ] **步骤 2：如有告警，修复并 commit**

---

## 自检报告

### 1. 规格覆盖度

| 规格章节 | 对应任务 |
|---------|---------|
| §1 背景与目标 | （无实现任务，属于描述） |
| §2 数据结构 | 任务 1, 2, 3 |
| §3.1 `put()` 路由分流 | 任务 4 |
| §3.2 路由分流规则表 | 任务 4（隐含在 `isDebugOnly` 判断） |
| §3.3 `putNotSave` / `putDebug` 不动 | （无任务，按方案 C） |
| §4.1 入口位置 | 任务 7 |
| §4.2 `DebugCategoryVisibilityDialog` | 任务 6 |
| §4.3 图标 | 任务 6（使用 `Icons.Default.Visibility`） |
| §5 文件清单 | 任务 1-8 一一对应 |
| §6.1 向后兼容 | 任务 4（`category` 默认值） |
| §6.2 渐进迁移 | （无任务，按方案 C） |
| §7.1 单元测试 | 任务 2 |
| §7.2 手动验证 | 任务 9 |
| §8 风险 | 任务 6（弹窗顶部警示文案） |
| §9 实施顺序 | 任务 1→2→3→4→5→6→7→8→9→10 |
| §10 后续扩展 | （不在范围） |

无遗漏。

### 2. 占位符扫描

- ✅ 无 "TBD" / "TODO" / "待定"
- ✅ 无 "类似任务 N" 复制粘贴
- ✅ 每个代码步骤都有完整代码块
- ✅ 所有命令都是具体可执行

### 3. 类型一致性

- `AppConfig.debugLogOnlyEnabled: Boolean` — 任务 3 定义，任务 4, 6 使用 ✓
- `AppConfig.debugLogOnlyCategories: Set<DebugCategory>` — 任务 3 定义，任务 4, 6 使用 ✓
- `AppLog.put(..., category: DebugCategory = DebugCategory.APP)` — 任务 4 定义 ✓
- `AppLog.putSource(..., category: DebugCategory = DebugCategory.SOURCE)` — 任务 5 定义 ✓
- `DebugCategoryVisibilityDialog(onDismiss: () -> Unit)` — 任务 6 定义，任务 7 调用 ✓
- `Icons.Default.Visibility` — 任务 7 import，任务 6 使用 ✓

无类型不一致。
