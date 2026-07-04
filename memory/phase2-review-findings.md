---
name: phase2-review-findings
description: Phase 2 code review results — what must be fixed before proceeding
metadata:
  type: project
---

# Phase 2 代码审查结果

审查日期：2026-07-03 | 改动文件：HomepageScreen.kt, HomepageViewModel.kt, HomepageContract.kt, HomepageFragment.kt

## 🛑 必须修复（2 项）

### 1. totalBooksRead 用错数据源
- **文件：** `HomepageViewModel.kt:73`
- **问题：** `totalBooksRead = bookCount` 统计的是书架书本数（`shelfBooks.size`），不是"累计阅读本数"
- **修复：** 用 `ReadRecordDao.count`（注意它是 Int 非 Flow，需要 `flow { emit(...) }` 包装后参与 combine）

### 2. ViewModel init 死代码
- **文件：** `HomepageViewModel.kt:84-87`
- **问题：** `_isBackingUp.collect` 在 init 中没做任何事，因为 `_isBackingUp` 已经在 combine 中参与
- **修复：** 删除整个 init 块

## 🟡 建议修改（2 项）

3. 硬编码中文字符串应改为 `strings.xml` + `stringResource()`
4. `HomepageScreen.kt` 多余 import：`ExperimentalGlideComposeApi`

## ℹ️ 仅供参考

5. `HomepageContract.kt` 保留了旧模块系统类型（Phase 3+ 清理）
6. `lastReadBook` 用 `durChapterTime` 不够精确，Phase 7 改进

**Why:** 记录 Phase 2 的具体审查结论，传给代码窗口修复。
**How to apply:** 代码窗口修完后需重新编译验证。
