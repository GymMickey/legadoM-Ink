---
name: review-workflow
description: Two-window workflow for code implementation and audit
metadata:
  type: project
---

# 代码实现 + 审核工作流

两个 VSCode 窗口并行工作：

| 窗口 | 模型 | 职责 |
|------|------|------|
| 代码窗口 | DeepSeek / Sonnet（便宜） | 分析、修改代码、编译验证 |
| 审计窗口 | Opus（本窗口） | 读取改动、审查、反馈修复意见 |

## 工作流程

1. 代码窗口完成一个 Phase，**保存所有文件**
2. 审计窗口打开改动文件，读代码并验证依赖
3. 审计窗口输出审查意见（🛑必须修复 / 🟡建议修改 / ℹ️仅供参考）
4. 代码窗口根据意见修复，编译通过后进入下一个 Phase

## 注意事项

- 代码窗口改动必须**保存到磁盘**，审计窗口才能读取
- CLAUDE.md 中的计划两边都能看到
- 切换 API key 不需要重启 VSCode，未保存文件热退出后保留

**Why:** 用户希望用便宜模型写代码、Opus 把关质量。
**How to apply:** 每个 Phase 按此流程操作。
