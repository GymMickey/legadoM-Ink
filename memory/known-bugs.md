---
name: known-bugs
description: 已知 bug 列表，含复现环境和当前处理状态
metadata:
  type: project
---

# 已知 Bug

## 书架布局弹窗首次点击闪现

- **状态**：未修复，留待后续处理
- **现象**：每次关闭再打开 App，在书架页右上角三点菜单 → 书架布局，弹窗闪现后消失，第二次点击正常
- **复现设备**：魅族 Flyme 12.6.0.0A
- **不复现**：Android Studio 模拟器（标准 AOSP）、部分手机设备
- **排查过程**：
  1. 尝试 `Handler.postDelayed(150ms)` 延迟弹窗 → 无效
  2. 加诊断日志 → 在模拟器和用户测试机上均不复现
  3. 判断为 Flyme 系统级别的 WindowManager/PopupWindow 行为差异
- **补救方向**（若以后需修复）：
  - 加大延迟或改用 `DialogFragment` 替代 `AlertDialog`
  - 将 `menu_bookshelf_layout` 从溢出菜单移到工具栏（`showAsAction="always"`），绕过 PopupWindow
- **相关文件**：[BaseBookshelfFragment.kt:104](../app/src/main/java/io/legado/app/ui/main/bookshelf/BaseBookshelfFragment.kt#L104)

**Why:** 特定设备/系统版本 bug，模拟器不复现，留待以后有真机时处理。
**How to apply:** 若有 Flyme 设备可用于调试，参考补救方向尝试修复。
