---
name: git-push-workflow
description: Git push 前必须先更新 updateLog.md，禁止跳过
metadata:
  type: feedback
---

## Git Push 工作流

每次 push 前必须执行以下步骤：

### 步骤 1：更新更新日志

编辑 `app/src/main/assets/web/help/md/updateLog.md`，在 `**2026/07/18**（未发布）` 区块中按日期记录本次变更：

- 用简短一句话描述每条改动
- 格式：`- <改动描述>`
- 如果当天还没建日期标题，先建 `**YYYY/MM/DD**（未发布）` 标题

### 步骤 2：提交

```bash
git add -A
git commit -m "描述本次改动"
```

### 步骤 3：推送

```bash
git push
```

### 约束

- 禁止在步骤 1 完成前执行 push
- 更新日志保持中文，每行一条改动
- 如果只是修 typo 或微调可以合并为一条

**Why:** 此前多次 push 后更新日志滞后，用户打开 App"关于→更新日志"看不到最新功能。先写日志再 push 确保日志始终同步。
**How to apply:** 每次准备 push 时，先确认 updateLog.md 已更新，再执行 git push。
