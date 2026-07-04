---
name: feedback-tts-strategy
description: TTS 功能只禁用隐藏入口，不删数据库结构（用户+GPT+姐们共识）
metadata:
  type: feedback
---

TTS/ReadAloud 功能采取"禁用 + 隐藏入口"策略，不删除数据库结构。

**Why:** 删除 HttpTTS entity 会导致 Room schema 变化、迁移失败、备份恢复不兼容。GPT 指出这与"数据库兼容"最高原则冲突。用户姐们也同意先禁用再观察。

**How to apply:** 
- HttpTTS entity/table/schema 永远保留在 AppDatabase entities 列表
- Book.ttsEngine / BookConfig.ttsEngine 等数据库字段保留
- 可以删除 UI 入口、服务、配置界面、运行逻辑
- 不要在 CLAUDE.md Phase 6 中写"删除 HttpTTS entity"
