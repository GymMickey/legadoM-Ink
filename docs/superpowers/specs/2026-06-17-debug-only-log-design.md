# 调试专属日志（Debug-Only Log）设计

**日期**：2026-06-17
**状态**：设计中

---

## 1. 背景与目标

### 1.1 现状

项目里存在两套日志界面：

| 界面 | 类 | 数据源 | 用途 |
|------|------|--------|------|
| **普通日志界面** | `AppLogDialog`（"关于"页） | `AppLog.mLogs` / `AppLog.mSourceLogs` | 简单的运行时日志查看，结构为 `Triple<Long, String, Throwable?>` |
| **调试日志界面** | `DebugLogScreen`（带分类/级别/搜索/导出/调试球） | `DebugEventCenter._events` | 富文本调试界面，支持 8 种 `DebugCategory` 分类、级别筛选、流程日志等 |

### 1.2 痛点

`AppLog.put()` / `putSource()` 内部**同时**写两份数据：

1. 写 `mLogs` / `mSourceLogs`（→ 普通日志界面）
2. emit `DebugEventCenter`（→ 调试日志界面）

导致调试球相关的 DEBUG 级别日志、RULE / RSS 流程日志等"啰嗦细节"也会污染普通日志界面，干扰用户对应用运行状态的核心感知。

### 1.3 目标

允许用户**按 `DebugCategory` 粒度**配置"调试专属"模式：

- 被标记的 category 的日志**只**进调试日志界面，不再进普通日志界面
- 由一个**独立总开关**控制整个功能是否启用
- 提供调试界面内的**设置面板**用于运行时配置
- 现有调用点**零改动**（渐进式迁移，方案 C）

### 1.4 非目标

- 不修改 `AppLog.putDebug()` 的现有行为（`recordLog` 开关语义保持原样）
- 不改变 `DebugEventCenter` 的事件结构
- 不重写 `AppLogDialog` 或 `DebugLogScreen` 的 UI 框架

---

## 2. 数据结构

### 2.1 `PreferKey` 新增

文件：`app/src/main/java/io/legado/app/constant/PreferKey.kt`

```kotlin
/** "调试专属"模式总开关，控制按 category 路由分流功能是否启用 */
const val debugLogOnlyEnabled = "debugLogOnlyEnabled"

/** 被标记为"只进调试界面"的 DebugCategory 集合，存为逗号分隔的 enum.name 字符串 */
const val debugLogOnlyCategories = "debugLogOnlyCategories"
```

### 2.2 `AppConfig` 新增属性

文件：`app/src/main/java/io/legado/app/help/config/AppConfig.kt`

```kotlin
/** 调试专属模式总开关，默认 true */
var debugLogOnlyEnabled: Boolean
    get() = appCtx.getPrefBoolean(PreferKey.debugLogOnlyEnabled, true)
    set(value) { appCtx.putPrefBoolean(PreferKey.debugLogOnlyEnabled, value) }

/** 被标记为"只进调试界面"的 DebugCategory 集合 */
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

**默认值**：升级用户首次读取时返回 `true`（启用）和 `emptySet()`（无 category 被标记），与升级前行为完全一致。

---

## 3. 核心逻辑改造

### 3.1 `AppLog.put()` 签名扩展 + 路由分流

文件：`app/src/main/java/io/legado/app/constant/AppLog.kt`

#### 改动 1：`put()` 新增 `category` 可选参数

```kotlin
@Synchronized
fun put(
    message: String?,
    throwable: Throwable? = null,
    toast: Boolean = false,
    dialogName: String? = null,
    category: DebugCategory = DebugCategory.APP  // ← 新增，默认 APP 保持向后兼容
) {
    message ?: return
    if (toast) {
        appCtx.toastOnUi(message)
    }

    // 新增：路由分流判断
    val isDebugOnly = AppConfig.debugLogOnlyEnabled && category in AppConfig.debugLogOnlyCategories

    if (!isDebugOnly) {
        // 原有逻辑：写 mLogs（→ 普通日志界面）
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
    // else: 跳过 mLogs 和 LogUtils.d，仅 emit 到 DebugEventCenter

    // 始终 emit 到 DebugEventCenter（调试界面永远能看）
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

#### 改动 2：`putSource()` 同样改造

`putSource()` 的 `category` 在 `DebugEvent` 里已经是 `SOURCE`，但写 `mSourceLogs` 也要走相同分流逻辑：

```kotlin
@Synchronized
fun putSource(
    message: String?,
    throwable: Throwable? = null,
    subCategory: SourceSubCategory = SourceSubCategory.UPDATE,
    dialogName: String? = null,
    category: DebugCategory = DebugCategory.SOURCE  // ← 新增
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

### 3.2 路由分流规则总表

| `debugLogOnlyEnabled` | `category` ∈ `debugLogOnlyCategories` | 行为 |
|:---:|:---:|------|
| `false` | — | 写 mLogs + emit（升级前行为） |
| `true` | `false` | 写 mLogs + emit（升级前行为） |
| `true` | `true` | **跳过** mLogs，仅 emit |

**关键不变量**：`DebugEventCenter` 永远收到所有日志（只要 `DebugLogScope` 正常 emit）。普通日志界面才被"过滤"。

### 3.3 `putNotSave()` / `putDebug()` 不动

按用户选择（方案 C），**不**迁移这两个方法的调用点，保持现有语义：

- `putNotSave()`：写 `mLogs`，同时 emit 到 `DebugEventCenter`（category 固定为 `APP`）。本设计**不**为它新增 `category` 参数
- `putDebug()`：当 `AppConfig.recordLog=true` 时调 `put()`，**始终** emit `DebugLevel.DEBUG` 事件。本设计**不**改动

> 这意味着用户即使勾选 `APP` 分类的"调试专属"，`putNotSave` / `putDebug` 仍会写 `mLogs`（因为它们内部走的是 `put()` 的 `category=APP` 默认路径或 `recordLog` 控制的路径）。如果未来要让这两条路径也参与分流，需要额外加 `category` 参数或独立 API（见 §10）。

---

## 4. UI 改造

### 4.1 入口位置

文件：`app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt`

在 `TopAppBar` 的 `actions` 溢出菜单（`DropdownMenu`）中，**"精准管理"项之后、"其他设置"项之前**新增一项：

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

在 `DebugLogScreen` 顶层新增状态：

```kotlin
var showCategoryVisibilityDialog by remember { mutableStateOf(false) }
```

并在 `Scaffold` 内、`Box` 外层注册弹窗：

```kotlin
if (showCategoryVisibilityDialog) {
    DebugCategoryVisibilityDialog(
        onDismiss = { showCategoryVisibilityDialog = false }
    )
}
```

### 4.2 新增组件：分类可见性设置弹窗

**新文件**：`app/src/main/java/io/legado/app/ui/debuglog/components/DebugCategoryVisibilityDialog.kt`

布局（`AlertDialog` + `Column` 滚动）：

```
┌─ 分类可见性 ─────────────────────┐
│ [●] 启用"调试专属"模式          │  ← 总开关
│                                  │
│ 勾选后，对应分类的日志将只在     │
│ 调试界面显示。                  │
│ ───────────────────────────────│
│ [○] 应用 (APP)                  │  ← 每个 category 一行 Switch
│ [○] 网络 (NETWORK)              │
│ [○] 规则 (RULE)                 │
│ [○] 书源 (SOURCE)               │
│ [○] 订阅 (RSS)                  │
│ [○] Toast                       │
│ [○] 校验 (CHECK)                │
│ [○] 崩溃 (CRASH)                │
│                                  │
│            [关闭]                │
└──────────────────────────────────┘
```

行为细节：

- 总开关：双向绑定 `AppConfig.debugLogOnlyEnabled`
- 每行 Switch：
  - 状态 = `category in AppConfig.debugLogOnlyCategories`
  - 点击切换：add/remove `category` 到 `AppConfig.debugLogOnlyCategories`
- 过滤 `DebugCategory.ALL`（仅用于 UI 筛选，不作为实际事件分类）
- 点击"关闭"或外部 dismiss → 直接 `onDismiss()`（已实时保存）

### 4.3 图标

使用 Material Icons 默认的 `Icons.Default.Visibility`（眼睛图标），不需要新资源。

---

## 5. 关键文件改动清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `app/src/main/java/io/legado/app/constant/PreferKey.kt` | 编辑 | +2 个 const val |
| `app/src/main/java/io/legado/app/help/config/AppConfig.kt` | 编辑 | +2 个 var 属性 |
| `app/src/main/java/io/legado/app/constant/AppLog.kt` | 编辑 | `put()` / `putSource()` 签名扩展 + 路由分流 |
| `app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt` | 编辑 | 菜单加入口 + 注册弹窗 |
| `app/src/main/java/io/legado/app/ui/debuglog/components/DebugCategoryVisibilityDialog.kt` | **新建** | 设置弹窗 |
| `app/src/main/assets/web/help/md/updateLog.md` | 编辑 | 写一行更新说明 |

---

## 6. 兼容性 / 升级策略

### 6.1 向后兼容

- `AppLog.put()` 新增的 `category` 参数是**可选**且有默认值 `DebugCategory.APP`，所有现有调用点无需修改
- `AppConfig.debugLogOnlyCategories` 默认值为 `emptySet()`，升级用户无感知
- `AppConfig.debugLogOnlyEnabled` 默认值为 `true`，但因为 categories 为空，行为与升级前完全一致

### 6.2 渐进迁移

按用户选择（方案 C），**不**主动迁移现有 `AppLog.putDebug()` 调用点。新代码如需"调试专属"语义，**新作者**有两种选择：

- 选项 1（推荐）：在 `AppLog` 加 `putDebugOnly(message, throwable, dialogName)` 方法，跳过 mLogs 写入
- 选项 2：调用 `AppLog.put(message, throwable, category = DebugCategory.XXX)`，并在设置里勾选对应 category

> **说明**：本设计的核心路径是选项 2（按 category 路由分流）。选项 1 的 `putDebugOnly()` 不在本次实现范围内，保留作为后续可扩展点。

---

## 7. 测试要点

### 7.1 单元测试

文件：`app/src/test/java/io/legado/app/help/config/AppConfigTest.kt`（如不存在则新建）

- `debugLogOnlyCategories` 序列化/反序列化：写入 `Set<SOURCE, RSS>`，读取回来应一致
- 写入 `Set<ALL>` 时读取回来应**不包含** `ALL`
- 写入空集合时读取回来应为空
- 写入无效 enum name（如 `"FOO"`）时读取应**忽略**该值

### 7.2 手动验证清单

| 场景 | 预期 |
|------|------|
| 默认状态 | 普通界面有日志，调试界面有日志 |
| 勾选 `APP` 分类 + 触发 `AppLog.put("test")` | 普通界面**不出现** "test"，调试界面**出现** |
| 关闭 `debugLogOnlyEnabled` 总开关 + 再次触发 | 普通界面**出现** "test"（回退到原行为） |
| 调试球关闭状态 | `DebugEventCenter` 仍正常 emit（不受影响） |
| 升级前已安装用户首次启动 | 行为与升级前一致 |

---

## 8. 风险与权衡

| 风险 | 缓解措施 |
|------|---------|
| 用户误勾 `CRASH` 分类后，崩溃日志不进普通界面，**事故排查困难** | 弹窗顶部加警示文案；总开关位置显眼 |
| `AppLog.put()` 增加 category 参数后，部分调用点未传参导致 category 全是 `APP` | 后续按需迁移；本设计**不强求**迁移 |
| 偏好设置键名拼写错误导致读取失败 | 全部走 `PreferKey` 常量 |
| 写入 `Set<ALL>` 时被过滤 | getter 显式 `filter { it != ALL }` |

---

## 9. 实施顺序

1. `PreferKey.kt` 加 key
2. `AppConfig.kt` 加属性
3. `AppLog.kt` 改 `put()` / `putSource()` 签名 + 路由分流
4. `DebugCategoryVisibilityDialog.kt` 新建
5. `DebugLogScreen.kt` 接入入口
6. `updateLog.md` 加更新说明
7. 单元测试 + 手动验证

---

## 10. 后续可扩展（不在本次实现）

- 新增 `AppLog.putDebugOnly()` 显式 API，让代码静态分析就能识别"调试专属"意图
- `DebugCategoryVisibilityDialog` 支持按"子分类"（如 SOURCE 下的 UPDATE/RULE/FLOW）粒度配置
- 在 `DebugEventCenter` 增加 category 黑/白名单，作为二级过滤
- 在 `AppLogDialog` 顶部加一个"显示调试专属日志"开关，让普通界面也能临时查看
