# 调试专属日志当前路由表

更新时间：2026-06-17

本文记录当前代码里哪些日志会进入“调试专属日志”分流，哪些不会。

## 结论

“调试专属日志”不是一个固定日志清单，而是按 `DebugCategory` 配置的路由规则：

```kotlin
AppConfig.debugLogOnlyEnabled &&
category in AppConfig.debugLogOnlyCategories
```

满足上面条件时，日志不再进入普通日志弹窗 `AppLogDialog`，但仍会进入调试日志页 `DebugLogScreen`。

未满足条件时，日志保持原行为：进入普通日志弹窗，同时也进入调试日志页。

## 用户可配置的分类

入口：调试日志页 -> 更多菜单 -> 调试专属日志

| 分类 | `DebugCategory` | 勾选后是否 debug-only | 影响范围 |
| --- | --- | --- | --- |
| 应用 | `APP` | 是 | `AppLog.put()`、`putNotSave()`、`putDebug()` 默认分类 |
| 书源 | `SOURCE` | 是 | `AppLog.putSource()` 默认分类 |
| 网络 | `NETWORK` | 是 | 直接进入调试事件中心的网络请求日志 |
| 规则 | `RULE` | 是 | `Debug.log()` 默认规则解析日志 |
| RSS | `RSS` | 是 | RSS 解析调试日志 |
| Toast | `TOAST` | 是 | `ToastUtils.recordToast()` 记录的 Toast 日志 |
| 校验 | `CHECK` | 是 | 预留/校验类调试日志分类 |
| 崩溃 | `CRASH` | 是 | 预留/崩溃类调试日志分类 |

`ALL` 只用于 UI 筛选，不会被保存为 debug-only 分类。

## API 路由表

| 日志入口 | 默认分类 | 普通日志弹窗 | 调试日志页 | 是否受 debug-only 配置影响 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `AppLog.put()` | `APP` | 默认进入 | 始终进入 | 是 | 常规应用日志入口 |
| `AppLog.putSource()` | `SOURCE` | 默认进入 `sourceLogs` | 始终进入 | 是 | 书源更新/操作类日志入口 |
| `AppLog.putNotSave()` | `APP` | 默认进入 | 始终进入 | 是 | 不写 `LogUtils.d()`，但普通日志弹窗默认可见 |
| `AppLog.putDebug()` | `APP` | `recordLog=true` 时通过 `put()` 进入 | 始终进入，级别为 `DEBUG` | 是 | 调试级日志入口 |
| `Debug.log()` | `RULE` 或 `RSS` | 不进入 `AppLogDialog` | 进入 | 天然 debug-only | 规则/RSS 解析调试日志 |
| `UrlRecordInterceptor` -> `DebugEventCenter.emit()` | `NETWORK` | 不进入 `AppLogDialog` | 进入 | 天然 debug-only | 网络请求日志 |
| `ToastUtils.recordToast()` -> `DebugEventCenter.emit()` | `TOAST` | 不进入 `AppLogDialog` | 进入 | 天然 debug-only | Toast 记录 |
| `TextDialog` / `HelpSearchDialog` 直接 emit | `APP` | 不进入 `AppLogDialog` | 进入 | 天然 debug-only | 直接发调试事件，不走 `AppLog` 列表 |
| `FlowLogRecorder` | 流程日志模型 | 不进入 `AppLogDialog` | 进入流程日志页签 | 不走 `DebugCategory` 分流 | 独立流程日志系统 |
| `RssExecutionRecorder` | RSS 执行状态模型 | 不进入 `AppLogDialog` | 进入 RSS 执行状态页签 | 不走 `DebugCategory` 分流 | 独立 RSS 执行记录系统 |

## 哪些不是 debug-only

| 入口 | 是否进入普通日志弹窗 | 是否进入调试日志页 | 说明 |
| --- | --- | --- | --- |
| `DebugLog.d/i/w/e()` | 否 | 否 | 只在 debug 构建写 Android Logcat |
| `LogUtils.d()` 直接调用 | 否 | 否 | 走项目文件/系统日志工具，不进入调试事件中心 |
| 普通 `android.util.Log.*` 直接调用 | 否 | 否 | 只写系统 Logcat |

## 当前默认状态

默认配置：

| 配置项 | 默认值 | 效果 |
| --- | --- | --- |
| `AppConfig.debugLogOnlyEnabled` | `true` | 分流功能默认开启 |
| `AppConfig.debugLogOnlyCategories` | 空集合 | 没有任何分类默认被分流 |

因此，升级后默认行为不变。只有用户在“调试专属日志”弹窗里勾选分类后，该分类才会变成 debug-only。

