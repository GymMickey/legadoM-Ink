---
name: audit-session-context
description: 审计窗口的审查记录、决策、UI 打磨阶段进展
metadata:
  type: project
---

# 审计窗口上下文

**双窗口模式：** 代码窗口写代码 → 审计窗口 review → 出修复提示词。

## 已做的决策

1. AI 功能不存在（GPT 编的），已跳过
2. 阅读目标卡片已砍（用户姐们同意），仪表盘 4 卡片
3. **TTS 策略调整**：功能禁用 + 隐藏入口，不删数据库结构（HttpTTS entity/table 保留）
4. BookType 常量、Book.isAudio/isVideo 扩展属性保留（数据库兼容）
5. help/TTS.kt 是 RSS 独立 TTS，必须保留
6. channelIdReadAloud 被 CheckSourceService 共用，保留常量但删 readAloud 渠道创建代码

## 已完成的审查（Phase 1-7）

| Phase | 结论 | 关键发现 |
|-------|------|---------|
| 1 | ✅ 通过 | 补充 ReadRecord 缺 coverUrl、ReadBook 耦合 BaseReadAloudService |
| 2 | ✅ 通过 | 4 项修复验证通过 |
| 3 | ✅ 通过 | E-Ink 颜色适配 + 依赖图完整 |
| 4 | ✅ 通过 | 零残留 |
| 5 | ✅ 修复后通过 | 4 项 must-fix (danmaku/isVideo/flowVideo/strings) |
| 6 | ✅ 修复后通过 | Step1: 核心删除; Step2: 3 必修 + 9 清理全部完成 |
| 7 | ✅ 完成 | 编译 PASS, 13 项功能入口全在, 0 残留引用, DB 兼容 OK (v100) |

## Phase 7 后：UI 打磨阶段（2026-07-04）

### 已确认生效的 UI 修改

- 首页中文本地化（累计阅读/阅读时长/WebDAV 备份等）
- 卡片背景统一白色 `pageCardElevatedContainerColor()`
- 统计双卡居中对齐（图标+文字+数值）
- WebDAV 卡显示最新备份时间 `LocalConfig.lastBackup`
- 阅读时长改小时制（decimal hours）
- 最近书籍横滑列表只显示封面（隐藏书名/作者文字）
- 阅读菜单底栏 TTS 按钮删除，3 按钮均匀分布（spacer 1-2-2-1）
- 封面加载修复（添加 `origin` 参数 → sourceOriginOption 解密 + placeholder/error fallback）
- 卡片描边：E-Ink 黑色 outline / 亮色主题用强调色 `primary.copy(alpha=0.5f)` / 暗色无描边

### 已出提示词但未确认执行的修改

1. **HomepageBookCover shouldDrawName** — `coverUrl == null` → `galleryDefaultCover == null`，对齐 CoverImageView 行为
2. **BackupSelectorConfig 清理** — 删 videoConfig/httpTTS 条目 + 所有 iconEmoji 设 null + getGroupIcon 返回 null
3. **版本号改制** — `3.26-beta X`（X 从 build_number.txt 自增）+ APK 文件名 "阅读"
4. **Debug 应用名** — `app_name_debug` 从 "阅读D" 改为 "阅读"

**Why:** 项目完整记录，下次会话可直接对照哪些提示词还需确认。
**How to apply:** 新会话先问用户哪些提示词已执行、结果如何。
