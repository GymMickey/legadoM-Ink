---
name: git-push-workflow
description: Git push 前必须先更新 updateLog.md，禁止跳过
metadata:
  type: feedback
---

## Git Push 工作流

每次 push 前必须按顺序执行以下步骤：

### 步骤 1：更新更新日志

编辑 `app/src/main/assets/web/help/md/updateLog.md`，在文件顶部添加本次变更：

- 新建 `**YYYY/MM/DD**` 日期标题
- 用简短一句话描述每条改动，格式：`- <改动描述>`
- **只写本次 push 的变更内容，不要追加往期历史**

### 步骤 2：打 Release 包

```bash
./gradlew assembleRelease
```

必须编译通过且无新增 warning。release 包路径：`app/build/outputs/apk/appMax/release/`

### 步骤 3：提交

```bash
git add -A
git commit -m "描述本次改动"
```

### 步骤 4：推送

```bash
git push
```

### 约束

- 禁止跳过步骤 1（更新日志）和步骤 2（release 包）直接 push
- 更新日志只写本次变更，不复制往期内容
- 更新日志保持中文，每行一条改动
- 如果只是修 typo 或微调可以合并为一条

**Why:** 此前多次 push 后更新日志滞后且掺杂历史记录，用户看不到清晰的最新变更。必须先打 release 包验证编译通过。
**How to apply:** 每次准备 push 时，严格按 1→2→3→4 顺序执行。
