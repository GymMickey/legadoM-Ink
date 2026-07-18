---
name: known-bugs
description: 已知 bug 列表，含复现环境和当前处理状态
metadata:
  type: project
---

# 已知 Bug

## 书架布局弹窗首次点击闪现 ❌ 未修复

- **复现设备**：魅族 Flyme 12.6.0.0A
- **现象**：书架页右上角三点菜单 → 书架布局，弹窗弹出后 68~180ms 自动消失。第二次点击正常。
- **根因**：Flyme 12.6 溢出菜单关闭动画结束后，Framework 向 Dialog 的 `ListenersHandler` 发送 `DISMISS` 消息（堆栈 `Dialog$ListenersHandler.handleMessage`），这不是代码调用 `dismiss()`/`cancel()`/touch outside 触发的，而是 **Flyme 窗口管理器在菜单关闭后对 Activity 的 Dialog 执行了生命周期清理**。

### 核心日志证据

```
21:45:40.097 D/BookshelfDialog: === DIALOG SHOWN ===
21:45:40.165 D/BookshelfDialog: === DIALOG DISMISSED ===   ← 68ms 后

dismiss 调用栈：
android.app.Dialog$ListenersHandler.handleMessage(Dialog.java:1496)  ← DISMISS 消息
android.os.Handler.dispatchMessage
android.os.Looper.loopOnce
```

### 已尝试方案（全失败）

| # | 方案 | 原理 | 结果 |
|---|------|------|------|
| 0 | `Handler.postDelayed(150ms)` | 延迟弹窗等菜单关闭 | ❌ 旧代码已存在 |
| A | `BookshelfLayoutDialog` (DialogFragment + AlertDialog.Builder) | DialogFragment 生命周期由 FragmentManager 管理 | ❌ 仍走 AlertDialog，Flyme 照杀 |
| B | `showAsAction="always"` 提到工具栏 | 绕开溢出菜单 PopupWindow | ❌ 工具栏点击也闪现 |
| C | `android.app.Dialog` 替代 `alert()` (AlertDialog) | 绕过 AppCompat/Flyme AlertDialog 实现 | ❌ Dialog 类型无关 |
| D | `Handler.post { dialog.show() }` | 延迟一帧等窗口焦点释放 | ❌ 一帧不够 |
| E | `setCanceledOnTouchOutside(false)` | 阻止 touch outside 关闭 | ❌ dismiss 不是 touch 触发的 |
| F | 覆写 `dialog.dismiss()` + `allowDismiss` 标志位 | 拦截 Framework 的 dismiss 调用 | ❌ Flyme 直接操作 Window，绕过 dismiss() |
| G | `postDelayed(300ms)` + 普通 Dialog | 等 300ms 菜单动画结束 | ❌ 延迟无效，Framework DISMISS 消息在 show 后异步到达 |
| H | View 叠加层 (`rootView.addView(overlay)`) 替代任何 Dialog | 无独立 Window，Flyme 无法干涉 | ❌ 原因不明，View 也闪现 |
| I | `onDismissListener` 中自动 `dialog.show()` reshow | 接受 dismiss，利用"第二次正常"自动复活 | ❌ 崩溃：`Already resumed, but proposed with update true`（Android 16 Dialog 内部状态不允许 dismiss 后 reshow） |

### 关键认知

9 个方案覆盖了：
- 对话框类型（AlertDialog / plain Dialog / DialogFragment）
- 触发方式（溢出菜单 / 工具栏按钮）
- 延迟策略（1帧 / 150ms / 300ms）
- 关闭拦截（touch outside / cancelable / dismiss 覆写）
- 窗口架构（独立 Window / View 叠加层）
- Reshow 策略（dismiss 后自动 reshow → 崩溃）

方案H（View 叠加层）也失败，说明问题不仅是 Dialog Window 被 dismiss。方案I（reshow）崩溃说明 Android 16 Dialog 状态机不允许 dismiss 后直接 show。

### 方案I 崩溃堆栈

```
java.lang.IllegalStateException: Already resumed, but proposed with update true
	at gs.k.C (obfuscated)
	at gs.k.resumeWith
	at is.p.invoke
	at ql.d.onClick  ← dismiss listener
	at d7.r.handleMessage ← Handler DISMISS 消息
```

### 下一步方案

方案J：新建 BookshelfConfigActivity，完全绕开 Fragment 内的 Dialog/View 叠加。独立 Activity 有自己的 Window 和生命周期，Flyme 的菜单关闭不影响另一个 Activity。

### 当前代码状态（方案H）

`BaseBookshelfFragment.kt` 的 `configBookshelf()` 方法已改为 View 叠加层实现：
- 直接 `rootView.addView(overlay)` 添加到 Activity 的 content view
- 半透明蒙层 + 圆角卡片容器
- 取消/确定按钮手动管理关闭

### 变更历史

- 方案 A：新建 `BookshelfLayoutDialog.kt`，`BaseBookshelfFragment.kt` 改为 `showDialogFragment()` → 已回退
- 方案 B：`main_bookshelf.xml` `showAsAction` 改为 `always` → 已回退
- 方案 C：`configBookshelf()` 用 `android.app.Dialog` 替代 `alert()` → 已被方案H覆盖
- 方案 D：`Handler.post` 包 `dialog.show()` → 已被方案H覆盖
- 方案 E：加 `setCanceledOnTouchOutside(false)` → 已被方案H覆盖
- 方案 F：匿名子类覆写 `dismiss()` + `allowDismiss` → 已被方案H覆盖
- 方案 G：`postDelayed(300ms)` → 已被方案H覆盖
- 方案 H：View 叠加层替代 Dialog → ❌ 也失败
- 方案 I：dismiss 后自动 reshow → ❌ 崩溃 `Already resumed`

**Why:** Flyme 12.6 + Android 16 系统级 Bug，10 个方案均失败。下一步尝试独立 Activity（方案J）。
**How to apply:** 详见 [[bookshelf-layout-flyme-fix]]。
