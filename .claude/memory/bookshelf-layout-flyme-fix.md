---
name: bookshelf-layout-flyme-fix
description: 书架布局弹窗 Flyme 12.6 闪现修复——9 方案全记录，当前方案H（View 叠加层）
metadata:
  type: project
---

## 书架布局弹窗 Flyme 12.6 闪现修复

### 根因（logcat 诊断确认）

Flyme 12.6 溢出菜单关闭动画结束后，Framework 向 Dialog 的 `ListenersHandler` 发送 `DISMISS` 消息（堆栈 `Dialog$ListenersHandler.handleMessage(Dialog.java:1496)`），这不是代码调用 `dismiss()`/`cancel()`/touch outside 触发的，而是 **Flyme 窗口管理器对 Dialog 执行了生命周期清理**。

```
关键日志：
21:45:40.097 D/BookshelfDialog: === DIALOG SHOWN ===
21:45:40.165 D/BookshelfDialog: === DIALOG DISMISSED ===   ← 仅 68ms

dismiss 调用栈：
android.app.Dialog$ListenersHandler.handleMessage(Dialog.java:1496)
android.os.Handler.dispatchMessage
android.os.Looper.loopOnce
```

### 已尝试方案

| # | 方案 | 原理 | 结果 |
|---|------|------|------|
| 0 | `Handler.postDelayed(150ms)` | 延迟弹窗等菜单关闭 | ❌ 旧代码已存在 |
| A | `BookshelfLayoutDialog` (DialogFragment + AlertDialog.Builder) | DialogFragment 生命周期由 FragmentManager 管理 | ❌ 仍走 AlertDialog |
| B | `showAsAction="always"` 提到工具栏 | 绕开溢出菜单 PopupWindow | ❌ 工具栏点击也闪现 |
| C | `android.app.Dialog` 替代 `alert()` (AlertDialog) | 绕过 AppCompat/Flyme AlertDialog | ❌ Dialog 类型无关 |
| D | `Handler.post` | 延迟一帧等窗口焦点释放 | ❌ 一帧不够 |
| E | `setCanceledOnTouchOutside(false)` | 阻止 touch outside | ❌ dismiss 不是 touch 触发的 |
| F | 覆写 `dismiss()` + `allowDismiss` | 拦截 Framework dismiss | ❌ Flyme 操作 Window，绕过 dismiss() |
| G | `postDelayed(300ms)` + 普通 Dialog | 等菜单动画结束 | ❌ DISMISS 消息异步到达，延迟无效 |
| H | View 叠加层（`rootView.addView(overlay)`） | 无独立 Window | ⏳ 待测试 |

### 当前代码状态

方案H 已应用到 `BaseBookshelfFragment.kt` 的 `configBookshelf()` 方法：
- 直接 `rootView.addView(overlay)` 添加到 Activity 的 content view
- 半透明蒙层 + 圆角卡片容器
- 取消/确定按钮手动管理关闭

### 关键认知

8 个方案覆盖了对话框类型、触发方式、延迟策略、关闭拦截、窗口架构所有维度。全部失败说明这是 Flyme 12.6 Framework 层 Bug，应用层无法修复（除非不用独立 Window）。
