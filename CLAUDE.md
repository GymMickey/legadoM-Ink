# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

阅读Max (legado_Plus) — an Android e-book reader app forked from Legado. Supports custom book sources with user-defined rules (Jsoup selectors + Rhino JS), RSS subscriptions, local TXT/EPUB reading, and an embedded HTTP/WebSocket server for remote control.

## Build Commands

Uses Gradle wrapper (`gradlew.bat` on Windows). JDK 17 required.

```bash
# Debug build (default flavor: appMax)
./gradlew assembleDebug

# Release build (ProGuard + resource shrinking enabled)
./gradlew assembleRelease

# Specific flavor builds
./gradlew assembleAppMaxDebug       # appMax (io.legado.app.yuedu, coexistence)
./gradlew assembleAppLegacyRelease  # appLegacy (io.legado.app, same as original)
./gradlew assembleAppSDebug         # appS (io.legado.app.yuedu.a)

# Install to device
./gradlew installDebug
./gradlew installAppMaxDebug

# Tests
./gradlew test                      # Unit tests
./gradlew connectedAndroidTest      # Instrumented tests

# Lint
./gradlew lint

# Download Cronet native libs (required before first build)
./gradlew app:downloadCronet
```

### Web Frontend (modules/web)

The embedded HTTP server's frontend is a Vue 3 + Vite app in `modules/web/`. It builds to `app/src/main/assets/web/vue/`.

```bash
cd modules/web
pnpm install        # requires Node >= 20, pnpm >= 9
pnpm dev            # local dev server with HMR
pnpm build          # production build + syncs to assets/web/vue/
pnpm lint:fix       # eslint auto-fix
pnpm format         # prettier
```

## Architecture

MVVM pattern with AndroidViewModel + ViewBinding + Coroutines.

### Base Classes (`io.legado.app.base`)

- `BaseActivity<VB>` — all Activities extend this. Manages theming, system bars, view binding. Override `observeLiveBus()` for event subscriptions (auto-cleaned on destroy).
- `VMBaseActivity<VB, VM>` — adds abstract `viewModel` property.
- `BaseViewModel` — extends `AndroidViewModel`. Key method: `execute { }` returns a `Coroutine<T>` with chainable `.onSuccess`, `.onError`, `.onFinally`. Default context is `Dispatchers.IO`, callbacks on `Dispatchers.Main`.

### Key Patterns

- **Coroutine helper**: `BaseViewModel.execute()` wraps `Coroutine.async()`. Use this instead of raw `viewModelScope.launch`.
- **Event bus**: `LiveEventBus` for cross-component events. Subscribe via `observeEvent<T>(key) { ... }` in `observeLiveBus()`.
- **Database**: Room (`AppDatabase` v96), singleton at `appDb`. DAOs in `data/`, entities in `data/entities/`. Uses KSP (not kapt).
- **Book source rules**: Rhino JS engine (`:modules:rhino` module) evaluates user-defined rules. The `analyzeRule` package in `model/` handles rule parsing.
- **Singletons in model/**: `ReadBook`, `CacheBook`, `AudioPlay` manage global reading state.

### Modules

The project has three library modules in `modules/`:

- `modules/book` — fork of epublib (EPUB parsing), package `me.ag2s.epublib`
- `modules/rhino` — fork of Mozilla Rhino JS engine, package `com.script`. Evaluates user-defined book source rules at runtime.
- `modules/web` — Vue 3 frontend for the embedded HTTP/WebSocket server (see above)

### Source Layout

`app/src/main/java/io/legado/app/`:
- `ui/` — Activities/Fragments grouped by feature (book/, rss/, source/, config/, debuglog/)
- `model/` — domain logic (WebBook for HTTP fetching, analyzeRule for rule engine)
- `data/` — Room DB, DAOs, repositories
- `help/` — helpers (config, http client, coroutine utilities, source management)
- `utils/` — Kotlin extensions (~100+ files)
- `web/` — embedded NanoHTTPD server + WebSocket endpoints

### Compose Usage

Jetpack Compose (Material3, BOM 2025.04.01) is used for newer UI surfaces (e.g. debug log panel). Traditional View system (ViewBinding + XML layouts) is used for most existing screens. Both coexist — ComposeViews can be overlaid on View-based Activities.

## Version Catalog

All dependency versions are in `gradle/libs.versions.toml`. In `build.gradle.kts` or `build.gradle`, reference them as `libs.xxx`. Major versions: OkHttp 5.3.2, Room 2.7.1, Coroutines 1.10.2, Compose BOM 2025.04.01.

## Build Variants

Three product flavors in dimension "app":
- `appLegacy` — same package name as original Legado (`io.legado.app`)
- `appMax` — coexistence package (`io.legado.app.yuedu`), the primary development target
- `appS` — another coexistence package (`io.legado.app.yuedu.a`)

Release builds: minifyEnabled + shrinkResources + ProGuard (`app/proguard-rules.pro`, `app/cronet-proguard-rules.pro`). Debug builds: no minification.

## CI/CD

GitHub Actions in `.github/workflows/`:
- `test.yml` — builds all 3 release flavors on push to main; auto-creates GitHub/Gitee releases with changelog from `updateLog.md`
- `web.yml` — builds the Vue frontend on changes to `modules/web/` and commits the output to `app/src/main/assets/web/vue/`
- `cronet.yml` — updates Cronet native libraries

## Conventions

- Annotation processing uses KSP, not kapt.
- `NonTransitiveRClass` is enabled — reference only directly used resources.
- Room schema exports to `$projectDir/schemas` for migration verification.
- Disabled build features: aidl, buildconfig, renderscript, resvalues, shaders.
- Architecture documentation in `Structure/` directory (Chinese) covers app startup flow, database schema, reading flow, event bus, and module dependencies.

## E-Ink 精简版任务计划

### 项目定位

在保持与原版 Legado Max 完全兼容的前提下，精简为 E-Ink 阅读器专用版本。

### 最高原则（不可违反）

1. 与原版数据库兼容（Room schema 不变）
2. Backup / Restore / WebDAV 完全兼容
3. 书源、RSS、订阅、下载完全兼容
4. 保持官方 UI 设计语言
5. 不新增业务逻辑/Repository/统计逻辑 — 只复用已有能力

### 实现原则

- 优先级：复用已有逻辑 > 复用已有数据流 > 复用已有 UI 组件 > 复用已有导航 > 最后才新增代码
- 每次修改：分析 → 修改 → 编译 → 修复编译错误 → 停止。不连续改多个功能
- 删除功能时，若公共组件被其他功能共享（如 `MediaHelp.kt`），必须保留

### 阶段计划

#### Phase 1：首页分析（只读，不改代码）

分析首页架构、数据流、导航、ViewModel、生命周期、公共组件、修改风险。

#### Phase 2：首页改造为阅读仪表盘

将 HomepageFragment 改造为固定布局，自上而下 **4 个卡片**（阅读目标已砍掉，避免新增存储和自定义 View 的 bug 风险）：

1. **最近阅读卡片** — 显示最后一本书的封面、书名/作者、章节进度百分比
   - 数据源：`ReadBook`（当前阅读状态） + `appDb.bookDao`（Book 表关联获取封面、总章节数）
   - ⚠️ `ReadRecord` entity 不含 `coverUrl`/`bookUrl`/`totalChapterNum`，需通过 `bookName + bookAuthor` 跨表查 `Book`

2. **统计双卡** — 累计阅读本数 | 阅读总时长
   - 数据源：`ReadRecordDao.getTotalReadTime()` (Flow) + `ReadRecordDao.count`

3. **最近书籍横滑列表** — 书封横向 RecyclerView
   - 数据源：`ReadRecordDao.getAllReadRecordsSortedByLastRead()` (Flow)
   - ⚠️ 同样需要 Book 表关联获取封面

4. **WebDAV 备份卡** — 状态 + 备份/恢复/设置按钮
   - 数据源：`AppWebDav` + 已有 Backup 逻辑（直接调用现成方法）

配色：需适配墨水屏（E-Ink）模式，见 Phase 3 的 E-Ink 适配任务。底部 5 tab 不变。

**关键风险备忘**：
- `ReadBook.kt:34` 导入了 `BaseReadAloudService`，Phase 6 删除 TTS 时需处理此耦合
- `MainActivity.kt:135` 引用了 `BaseReadAloudService.pause`，Phase 6 一并处理

#### Phase 3：分析待删除功能 + 首页 E-Ink 颜色适配

**3A：依赖图分析（只读，不改代码）**

分析有声书（AudioPlay）、视频（VideoPlay）、TTS（ReadAloud）的完整依赖图。

**3B：HomepageScreen E-Ink 颜色适配（改代码）**

当前 `HomepageScreen.kt` 使用 4 个硬编码彩色背景（暖粉/奶油/薰衣草/薄荷），墨水屏上会显示为不均匀灰色色块。

**修改方案：**
1. 删除 `CardWarmPink`/`CardCream`/`CardLavender`/`CardMint` 4 个硬编码颜色常量
2. 卡片背景改用 `CommonPageColors.kt` 已有函数：
   - `pageCardContainerColor()` — 普通卡片背景（E-Ink 下自动变白/高对比度）
   - `pageCardElevatedContainerColor()` — 提升卡片背景（E-Ink 下微灰区分层次）
3. 当 `AppConfig.isEInkMode` 为 true 时：
   - Card 的 `elevation` 设为 0dp（墨水屏无法渲染阴影）
   - Card 添加 1dp 描边（`border`），颜色用 `MaterialTheme.colorScheme.outline`，与已有 `bg_eink_border_dialog.xml` 风格统一
   - 禁用所有动画（`CircularProgressIndicator` 改为静态文字提示）
4. 文字颜色统一用 `pageSecondaryTextColor()` / `MaterialTheme.colorScheme.onSurface`
5. 所有模式（普通/暗色/E-Ink）都应正常显示，不能只为 E-Ink 优化而破坏彩色屏体验

**关键参考文件：**
- `ui/theme/CommonPageColors.kt` — 已有的 Compose 颜色函数，内置亮/暗模式自适应
- `ui/theme/LegadoTheme.kt` — Compose Material3 主题桥接，从 ThemeStore 读取颜色
- `help/config/ThemeConfig.kt:475-483` — E-Ink 模式下设 WHITE/BLACK
- `help/config/AppConfig.kt:54` — `isEInkMode` 判断
- `base/BaseDialogFragment.kt` — E-Ink 对话框样式参考（无阴影、有描边）

#### Phase 4：删除有声书（AudioPlay）

- 删除：`AudioPlayActivity`, `AudioPlayViewModel`, `AudioPlayService`, `AudioPlay` model, `AudioSkipCredits`, `SliderPopup`
- 删除相关 layout/menu 资源
- 保留：`MediaHelp.kt`（TTS 阶段删完后再评估）, `ExoPlayerHelper`（视频可能仍用）
- 清理所有引用 → 编译 → 修复 → 停止

#### Phase 5：删除视频（VideoPlay）

- 删除：`VideoPlayerActivity`, `VideoPlayerViewModel`, `VideoPlayService`, `VideoPlay` model, `ChapterAdapter`, 浮窗播放相关
- 删除 GSYVideoPlayer 相关 helper (`Exo2MediaPlayer`, `ExoPlayerManager`, `ExoVideoManager`, `FloatingPlayer`)
- 删除 layout/menu 资源
- 评估是否可以移除 GSYVideoPlayer 依赖（`gsyvideoplayer-java`, `gsyvideoplayer-exo2`）
- 清理引用 → 编译 → 修复 → 停止

#### Phase 6：禁用 TTS（ReadAloud）— 功能禁用 + 隐藏入口，不动数据库

**策略：** 删除 TTS 运行逻辑和 UI 入口，但保留所有数据库结构不变（Room schema 兼容）。

**已完成（第一步）：**
- ✅ 删除：`BaseReadAloudService`, `TTSReadAloudService`, `HttpReadAloudService`, `ReadAloud` model
- ✅ 删除：`ReadAloudDialog`, `ReadAloudConfigDialog`, `SpeakEngineDialog`, `HttpTtsEditDialog`, `TtsDebugActivity`, `ReadAloudActivity`
- ✅ 删除：`ReadAloudMiniBarController`, `InputStreamDataSource`, `ImportHttpTtsDialog/ViewModel`, `SpeakEngineContentSearchDialog/ViewModel`
- ✅ 删除：对应 layout/menu 资源
- ✅ 解耦：`ReadBook.kt` (BaseReadAloudService), `MainActivity.kt` (返回键), `BaseActivity.kt` (MiniBar), `MediaButtonReceiver.kt`

**⚠️ 不可删除（数据库兼容）：**
- `HttpTTS.kt` entity — 必须保留在 `AppDatabase` entities 列表中，删除会导致 Room schema 变化和迁移失败
- `Book.ttsEngine` / `BookConfig.ttsEngine` — 数据库字段，保留
- `httpTTS.json` 默认数据 — 已删除（无影响，只是初始化数据）

**待修复（第二步）：**
- 🛑 `AndroidManifest.xml`: 删除 4 个已删类声明 (ReadAloudActivity, TtsDebugActivity, TTSReadAloudService, HttpReadAloudService)
- 🛑 `ShortCuts.kt`: 删除 `buildReadAloudShortCutInfo()` 及其调用
- 🛑 `App.kt`: 删除 readAloud 通知渠道创建代码（保留 `channelIdReadAloud` 常量，CheckSourceService 仍用）
- 🟡 清理死代码：EventBus TTS 常量、PreferKey TTS 键、AppConfig TTS 属性、ReadMenu 朗读按钮、readAloud span 高亮代码

**保留：**
- `help/TTS.kt` — RSS 阅读独立 TTS，必须保留
- `MediaHelp.kt` — Phase 4/5 已删 Audio/Video，评估是否仍有使用者
- `channelIdReadAloud` — CheckSourceService 共用此通知渠道

#### Phase 7：最终验证

确认以下功能正常：阅读、书架、最近阅读、阅读统计、RSS、书源、订阅、下载、Backup、Restore、WebDAV、首页仪表盘、设置、导航。编译通过 → 停止。

### 关键文件索引

| 组件 | 路径 |
|------|------|
| 首页 Activity | `app/.../ui/main/MainActivity.kt` |
| 首页 Fragment | `app/.../ui/main/homepage/HomepageFragment.kt` |
| 有声书 UI | `app/.../ui/book/audio/` |
| 有声书 Service | `app/.../service/AudioPlayService.kt` |
| 有声书 Model | `app/.../model/AudioPlay.kt` |
| 视频 UI | `app/.../ui/video/` |
| 视频 Service | `app/.../service/VideoPlayService.kt` |
| 视频 Model | `app/.../model/VideoPlay.kt` |
| GSY Helper | `app/.../help/gsyVideo/` |
| TTS Services | `app/.../service/BaseReadAloudService.kt`, `TTSReadAloudService.kt`, `HttpReadAloudService.kt` |
| TTS Model | `app/.../model/ReadAloud.kt` |
| TTS UI | `app/.../ui/book/read/config/ReadAloudDialog.kt` 等 |
| 阅读记录 DAO | `app/.../data/dao/ReadRecordDao.kt` |
| 共享媒体工具 | `app/.../help/MediaHelp.kt` |
| ExoPlayer 工具 | `app/.../help/exoplayer/` |

<!-- superpowers-zh:begin (do not edit between these markers) -->
# Superpowers-ZH 中文增强版

本项目已安装 superpowers-zh 技能框架（20 个 skills）。

## 核心规则

1. **收到任务时，先检查是否有匹配的 skill** — 哪怕只有 1% 的可能性也要检查
2. **设计先于编码** — 收到功能需求时，先用 brainstorming skill 做需求分析
3. **测试先于实现** — 写代码前先写测试（TDD）
4. **验证先于完成** — 声称完成前必须运行验证命令

## 可用 Skills

Skills 位于 `.claude/skills/` 目录，每个 skill 有独立的 `SKILL.md` 文件。

- **brainstorming**: 在任何创造性工作之前必须使用此技能——创建功能、构建组件、添加功能或修改行为。在实现之前先探索用户意图、需求和设计。
- **chinese-code-review**: 中文 review 沟通参考——话术模板、分级标注（必须修复/建议修改/仅供参考）、国内团队常见反模式应对。仅在用户显式 /chinese-code-review 时调用，不要根据上下文自动触发。
- **chinese-commit-conventions**: 中文 commit 与 changelog 配置参考——Conventional Commits 中文适配、commitlint/husky/commitizen 中文模板、conventional-changelog 中文配置。仅在用户显式 /chinese-commit-conventions 时调用，不要根据上下文自动触发。
- **chinese-documentation**: 中文文档排版参考——中英文空格、全半角标点、术语保留、链接格式、中文文案排版指北约定。仅在用户显式 /chinese-documentation 时调用，不要根据上下文自动触发。
- **chinese-git-workflow**: 国内 Git 平台配置参考——Gitee、Coding.net、极狐 GitLab、CNB 的 SSH/HTTPS/凭据/CI 接入差异与镜像同步配置。仅在用户显式 /chinese-git-workflow 时调用，不要根据上下文自动触发。
- **dispatching-parallel-agents**: 当面对 2 个以上可以独立进行、无共享状态或顺序依赖的任务时使用
- **executing-plans**: 当你有一份书面实现计划需要在单独的会话中执行，并设有审查检查点时使用
- **finishing-a-development-branch**: 当实现完成、所有测试通过、需要决定如何集成工作时使用——通过提供合并、PR 或清理等结构化选项来引导开发工作的收尾
- **mcp-builder**: MCP 服务器构建方法论 — 系统化构建生产级 MCP 工具，让 AI 助手连接外部能力
- **receiving-code-review**: 收到代码审查反馈后、实施建议之前使用，尤其当反馈不明确或技术上有疑问时——需要技术严谨性和验证，而非敷衍附和或盲目执行
- **requesting-code-review**: 完成任务、实现重要功能或合并前使用，用于验证工作成果是否符合要求
- **subagent-driven-development**: 当在当前会话中执行包含独立任务的实现计划时使用
- **systematic-debugging**: 遇到任何 bug、测试失败或异常行为时使用，在提出修复方案之前执行
- **test-driven-development**: 在实现任何功能或修复 bug 时使用，在编写实现代码之前
- **using-git-worktrees**: 当需要开始与当前工作区隔离的功能开发或执行实现计划之前使用——创建具有智能目录选择和安全验证的隔离 git 工作树
- **using-superpowers**: 在开始任何对话时使用——确立如何查找和使用技能，要求在任何响应（包括澄清性问题）之前调用 Skill 工具
- **verification-before-completion**: 在宣称工作完成、已修复或测试通过之前使用，在提交或创建 PR 之前——必须运行验证命令并确认输出后才能声称成功；始终用证据支撑断言
- **workflow-runner**: 在 Claude Code / OpenClaw / Cursor 中直接运行 agency-orchestrator YAML 工作流——无需 API key，使用当前会话的 LLM 作为执行引擎。当用户提供 .yaml 工作流文件或要求多角色协作完成任务时触发。
- **writing-plans**: 当你有规格说明或需求用于多步骤任务时使用，在动手写代码之前
- **writing-skills**: 当创建新技能、编辑现有技能或在部署前验证技能是否有效时使用

## 如何使用

当任务匹配某个 skill 时，使用 `Skill` 工具加载对应 skill 并严格遵循其流程。绝不要用 Read 工具读取 SKILL.md 文件。

如果你认为哪怕只有 1% 的可能性某个 skill 适用于你正在做的事情，你必须调用该 skill 检查。
<!-- superpowers-zh:end -->
