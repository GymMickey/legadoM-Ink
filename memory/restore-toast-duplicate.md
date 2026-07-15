---
name: restore-toast-duplicate
description: Restore.restore() 内部已弹成功 Toast，调用方不应重复弹出
metadata:
  type: project
---

# Restore 恢复成功后重复 Toast 陷阱

`Restore.restore()`（私有方法，[Restore.kt:595](../app/src/main/java/io/legado/app/help/storage/Restore.kt#L595)）末尾第 921 行**已经**调用 `appCtx.toastOnUi(R.string.restore_success)` 弹出成功提示，然后第 929 行通过 `withContext(Main) { ThemeConfig.applyDayNight(appCtx) }` 触发 `EventBus.RECREATE` → `MainActivity.recreate()` 重建 Activity。

因此任何调用链（如 `HomepageFragment.onRestoreConfirm` → `AppWebDav.restoreWebDav` → `Restore.restoreLocked` → `Restore.restore()`）的**调用方不应再弹成功 Toast**，否则用户看到两个"恢复成功"。

## 完整调用链

```
HomepageFragment.onRestoreConfirm (GlobalScope)
  ├── withContext(Dispatchers.IO) {
  │     AppWebDav.restoreWebDav(name)
  │       ├── downloadAndUnzipBackup(name)
  │       └── Restore.restoreLocked(path)
  │             └── Restore.restore(path)   ← 第 921 行弹 Toast，第 929 行触发 RECREATE
  │   }
  └── 回到 Dispatchers.Main  ← 不应再弹成功 Toast（已由内部弹出）
```

## 相关历史

Commit `f230724` 将协程从 `viewLifecycleOwner.lifecycleScope` 改为 `GlobalScope(Dispatchers.Main)` 以解决 RECREATE 取消协程导致误报恢复失败的问题。修复前 lifecycleScope 被 RECREATE 取消，调用方的第二次 Toast 不会执行；修复后 GlobalScope 存活，重复 Toast 暴露出来。

**Why:** `Restore.restore()` 内部负责弹成功/失败 Toast 并触发 RECREATE，调用方加 Toast 会造成重复。
**How to apply:** 调用 `AppWebDav.restoreWebDav()` 或 `Restore.restoreLocked()` 时，调用方只在 catch 块处理错误即可，不要弹成功 Toast。
