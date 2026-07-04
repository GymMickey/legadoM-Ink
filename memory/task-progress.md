---
name: task-progress
description: E-Ink 精简 7 Phase 全部完成 + UI 打磨阶段待确认项
metadata:
  type: project
---

# 进度跟踪

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | 首页架构分析 | ✅ 完成 |
| 2 | 首页改造为仪表盘 | ✅ 完成 |
| 3 | 依赖分析 + E-Ink 适配 | ✅ 完成 |
| 4 | 删除有声书 | ✅ 完成 |
| 5 | 删除视频 | ✅ 完成 |
| 6 | 禁用 TTS | ✅ 完成 |
| 7 | 最终验证 | ✅ 完成 |

## UI 打磨阶段（Phase 7 后）

### 待确认提示词（已给代码窗口，未收到执行结果）

1. **HomepageBookCover shouldDrawName** — 封面有了但缺书名作者叠字
   - 文件: `HomepageBookCover.kt`
   - 修改: `shouldDrawName` 条件从 `coverUrl == null` 改为 `galleryDefaultCover == null`
   - 原因: 对齐 CoverImageView 行为（先画文字→加载成功后封面覆盖文字）

2. **BackupSelectorConfig 清理** — 删除已砍功能的备份条目
   - 文件: `BackupSelectorConfig.kt`, `Backup.kt`, `Restore.kt`, `BackupInfoHelper.kt`, `BackupController.kt`
   - 修改: 删 videoConfig/httpTTS 条目 + emoji 图标全部置 null

3. **版本号改制** — 当前版本号是一长串时间戳数字
   - 文件: `app/build.gradle`, 新建 `build_number.txt`
   - 修改: versionName 改为 `3.26-beta X`，X 从 build_number.txt 自增

4. **Debug 应用名** — 安装后桌面显示"阅读D"
   - 文件: `app/src/main/res/values/strings.xml`
   - 修改: `app_name_debug` 从 "阅读D" 改为 "阅读"

### 安全约束（不可违反）

- Room schema 不变（HttpTTS entity 保留）
- Backup / Restore / WebDAV 完全兼容
- Book.ttsEngine / BookConfig.ttsEngine 保留
- help/TTS.kt (RSS 独立 TTS) 保留
- channelIdReadAloud (CheckSourceService 共用) 保留

**Why:** 跟踪 UI 打磨阶段未确认的修改。
**How to apply:** 新会话开始时问用户哪些已执行。
