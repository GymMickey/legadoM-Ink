# Phase 3: 待删除功能依赖分析

> 日期：2026-07-03 | 状态：只读分析完成

---

## 一、有声书（AudioPlay）

### 需要删除的文件（17 个，全独占）

**Kotlin 源文件（6）：**
- `app/.../model/AudioPlay.kt` — 单例播放状态模型
- `app/.../service/AudioPlayService.kt` — 前台服务（ExoPlayer 音频）
- `app/.../ui/book/audio/AudioPlayActivity.kt` — 全屏播放器 UI
- `app/.../ui/book/audio/AudioPlayViewModel.kt` — ViewModel
- `app/.../ui/book/audio/SliderPopup.kt` — 定时器/速度弹窗
- `app/.../ui/book/audio/config/AudioSkipCredits.kt` — 片头片尾跳过配置

**Layout/菜单 XML（5）：**
- `activity_audio_play.xml` + `activity_audio_play.xml` (landscape)
- `dialog_audio_skip_credits.xml`
- `popup_seek_bar.xml`（仅由 SliderPopup 使用）
- `menu/audio_play.xml`

### 需要修改的文件（19 个）

| # | 文件 | 修改内容 | 风险 |
|---|------|---------|------|
| 1 | `AndroidManifest.xml` | 删除 AudioPlayActivity + AudioPlayService 注册 | 低 |
| 2 | `EventBus.kt` | 删除 8 个 AUDIO_* 常量 | 低 |
| 3 | `NotificationId.kt` | 删除 `AudioPlayService = 102` | 低 |
| 4 | `PreferKey.kt` | 删除 `audioPlayWakeLock` | 低 |
| 5 | `ReadConstants.kt` | 删除 MIN/MAX_PLAY_SPEED | 低 |
| 6 | `AppConfig.kt` | 删除 `audioPlayUseWakeLock` | 低 |
| 7 | `BackupConfig.kt` | 从排除列表中删除 `audioPlayWakeLock` | 低 |
| 8 | `BackupInfoHelper.kt` | 删除 AudioPlay 引用 | 低 |
| 9 | `ContextExtensions.kt` | 删除 `book.isAudio → AudioPlayActivity` 分支 | 中 |
| 10 | `FragmentExtensions.kt` | 同上 | 中 |
| 11 | `BookInfoActivity.kt` | 删除 `book.isAudio` 阅读启动分支 | 中 |
| 12 | `BookInfoViewModel.kt` | 删除 `AudioPlay.book` 同步逻辑 | 低 |
| 13 | `SourceLoginViewModel.kt` | 删除 `BookType.audio →` 分支 | 低 |
| 14 | `RssJsExtensions.kt` | 删除 `BookType.audio →` 分支 | 低 |
| 15 | `SourceHelp.kt` | 删除 `AudioPlay.bookSource` 分支 | 低 |
| 16 | `MediaButtonReceiver.kt` | 删除 AudioPlay 分支（保留 ReadAloud/TTS 分支） | 中 |
| 17 | `ModuleStatus.kt` | 删除 `AudioPlayService.isRun` 状态项 | 低 |
| 18 | `BookSource.kt` | 检查并删除 AudioPlay import | 低 |
| 19 | `CacheActivity.kt` | 删除 `!it.isAudio` 过滤器 | 低 |

### 共享文件（保留）
- **MediaHelp.kt** — 同时被 AudioPlay + VideoPlay + ReadAloud 使用，Phase 6 最终评估
- **ExoPlayerHelper.kt** — 同时被 AudioPlayService 使用
- **BookType.kt** — `audio = 0b100000` 标志位保留
- **IntentAction.kt** — 所有媒体动作共享
- **Book.kt** `BookConfig` 中的 audio 字段 — 数据库兼容保留

---

## 二、视频（VideoPlay）

### 需要删除的文件（33 个，全独占）

**Kotlin 源文件（18）：**

UI 层 (`ui/video/` 目录 6 个)：
- `VideoPlayerActivity.kt`, `VideoPlayerViewModel.kt`, `ChapterAdapter.kt`
- `QuickJumpButtons.kt`, `config/SettingsDialog.kt`, `config/VideoSettingsContent.kt`

GSY 桥接层 (`help/gsyVideo/` 目录 10 个)：
- `VideoPlayer.kt`, `FloatingPlayer.kt`, `Exo2MediaPlayer.kt`
- `ExoPlayerManager.kt`, `ExoVideoManager.kt`, `BiliDanmukuParser.kt`
- `DanmakuAdapter.kt`, `ChoiceEpisodeDialog.kt`, `ChoiceSpeedDialog.kt`, `SwitchVideoAdapter.kt`

核心（2 个）：
- `model/VideoPlay.kt` — 单例播放状态模型
- `service/VideoPlayService.kt` — 前台浮窗服务

**Layout XML（12 个）：**
- `activity_video_player.xml`, `floating_video_player.xml`
- `video_layout_controller.xml`, `video_layout_controller_full.xml`, `video_layout_floating.xml`, `video_player_control.xml`
- `dialog_video_settings.xml`, `item_video_chapter.xml`, `item_video_chapter_volume.xml`
- `switch_episode_video_dialog.xml`, `switch_speed_video_dialog.xml`, `switch_video_dialog_item.xml`

**Drawable（3 个）：**
- `bg_video_chapter_item.xml`, `card_video_background.xml`, `floating_rounded_background.xml`

### 需要修改的文件（26 个）

| # | 文件 | 修改内容 | 风险 |
|---|------|---------|------|
| 1 | `AndroidManifest.xml` | 删除 VideoPlayerActivity + VideoPlayService | 低 |
| 2 | `EventBus.kt` | 删除 3 个 VIDEO_* 常量 | 低 |
| 3 | `NotificationId.kt` | 删除 `VideoPlayService = 108` | 低 |
| 4 | `SourceHelp.kt` | 删除 `VideoPlay.source` 分支 + `openVideoPlayer()` | 中 |
| 5 | `JsExtensions.kt` | 删除 `openVideoPlayer()` JS 桥接方法 | 高 |
| 6 | `SourceLoginViewModel.kt` | 删除 `BookType.video →` 分支 | 低 |
| 7 | `RssJsExtensions.kt` | 删除 `BookType.video →` 分支 | 低 |
| 8 | `ReadRssActivity.kt` | 删除 `VideoPlay.mutePlay` 视频静音逻辑 | 中 |
| 9 | `ReadRss.kt` | 删除 `type == 2 → VideoPlayerActivity` 分支 | 中 |
| 10 | `BookInfoActivity.kt` | 删除 `book.isVideo →` 分支 | 中 |
| 11 | `ContextExtensions.kt` | 删除 `book.isVideo →` 分支 | 中 |
| 12 | `FragmentExtensions.kt` | 删除 `book.isVideo →` 分支 | 中 |
| 13 | `BookExtensions.kt` | 删除 `Book.isVideo` 扩展属性 | 高 |
| 14 | `BookDao.kt` | 删除 `flowVideo()` + 查询条件中移除 video 过滤 | 中 |
| 15 | `BookGroupDao.kt` | 移除 video 组条件 | 中 |
| 16 | `BookSourceExtensions.kt` | 删除 `BookSourceType.video →` 映射 | 低 |
| 17 | `BookInfoEditActivity.kt` | 删除 spinner 中 `BookType.video` 选项 | 低 |
| 18 | `Backup.kt` | 删除 VIDEO_PREF_NAME 备份逻辑 | 低 |
| 19 | `Restore.kt` | 删除 VIDEO_PREF_NAME 恢复逻辑 | 低 |
| 20 | `BackupController.kt` | 删除 VIDEO_PREF_NAME Web 备份 | 低 |
| 21 | `BookChapterExtensions.kt` | 删除 `getDanmaku()` | 低 |
| 22 | `BookChapter.kt` | 删除 `putDanmaku()` | 低 |
| 23 | `RuleBigDataHelp.kt` | 删除 `getDanmakuFile()` | 低 |
| 24 | `app/build.gradle` | 删除 GSYVideoPlayer + danmaku 依赖 | 低 |
| 25 | `gradle/libs.versions.toml` | 删除 gsyvideoplayer, danmaku 版本 | 低 |
| 26 | `values/strings.xml` | 删除 video_* 专属字符串 | 低 |

### 共享文件（保留）

| 文件 | 共享用途 |
|------|---------|
| `ExoPlayerHelper.kt` | AudioPlayService 也用 — **保留** |
| `InputStreamDataSource.kt` | Phase 6 随 TTS 删除 — **保留** |
| `MediaHelp.kt` | Audio + Video 共用 — **保留** |
| `BookType.kt` | `video` 常量保留（数据库兼容性） |

### Gradle 依赖评估

| 依赖 | 可删除？ |
|------|---------|
| `gsyVideoPlayer-java` | ✅ 是 |
| `gsyVideoPlayer-exo2` | ✅ 是 |
| `danmakuFlameMaster` | ✅ 是 |
| `media3-exoplayer` | ❌ AudioPlayService 仍使用 |
| `media3-datasource-okhttp` | ❌ AudioPlayService 仍使用 |

---

## 三、TTS（ReadAloud）

### 需要删除的文件（24 个，全独占）

**核心文件（7）：**
- `service/BaseReadAloudService.kt` — 抽象基类（TTS/HTTP 服务的父类）
- `service/TTSReadAloudService.kt` — 系统 TTS 引擎服务
- `service/HttpReadAloudService.kt` — HTTP 在线 TTS 服务
- `model/ReadAloud.kt` — 朗读单例协调器
- `data/entities/HttpTTS.kt` — `httpTTS` 表 Room entity
- `data/dao/HttpTTSDao.kt` — HttpTTS DAO
- `assets/defaultData/httpTTS.json` — 默认 TTS 引擎配置

**UI 文件（13）：**
- `ui/book/read/config/ReadAloudDialog.kt`
- `ui/book/read/config/ReadAloudConfigDialog.kt`
- `ui/book/read/config/SpeakEngineDialog.kt` + `SpeakEngineViewModel.kt`
- `ui/book/read/config/HttpTtsEditDialog.kt` + `HttpTtsEditViewModel.kt`
- `ui/book/read/config/TtsDebugActivity.kt` + `TtsDebugModel.kt`
- `ui/book/read/config/ReadAloudActivity.kt`
- `ui/book/read/config/SpeakEngineContentSearchDialog.kt` + `SpeakEngineContentSearchViewModel.kt`
- `ui/association/ImportHttpTtsDialog.kt` + `ImportHttpTtsViewModel.kt`

**Widget（1）：**
- `ui/widget/ReadAloudMiniBarController.kt`

**Layout/XML/Menu（4+3）：**
- `dialog_read_aloud.xml`, `activity_read_aloud.xml`, `pref_config_aloud.xml`, `view_read_aloud_mini_bar.xml`
- `menu/speak_engine.xml`, `menu/speak_engine_edit.xml`, `menu/tts_debug.xml`

**ExoPlayer 桥接（1）：**
- `help/exoplayer/InputStreamDataSource.kt` — 仅 HttpReadAloudService 使用

### 需要修改的文件（52 个）

#### ⚠️ 高风险修改

| # | 文件 | 具体修改 |
|---|------|---------|
| M1 | **`ReadBook.kt:34`** | 删除 `import BaseReadAloudService` + `import ReadAloud`；删除 `readAloud()` 方法 |
| M2 | **`MainActivity.kt:35,135`** | 删除 `import BaseReadAloudService`；删除 `if (BaseReadAloudService.pause) finish() else moveTaskToBack(true)` → 简化为 `moveTaskToBack(true)` |
| M3 | **`BaseActivity.kt`** | 删除 ReadAloudMiniBar 全部代码（~27 行 import/声明/生命周期）+ `implements ReadAloudMiniBarHost` |
| M4 | **`ComposeActivitySupport.kt`** | 删除 ReadAloudMiniBar ~60 行 |
| M5 | **`ReadBookActivity.kt`** | 删除 TTS 入口按钮/回调/~150 行代码 |
| M6 | **`ReadMenu.kt`** | 删除朗读菜单按钮区域（`ivReadAloud`, `llReadAloud` 等） |
| M7 | **`ReadView.kt`** | 删除 `aloudStartSelect()`, `getReadAloudPos()`, TTS 点击动作 |
| M8 | **`MediaButtonReceiver.kt`** | 删除 ReadAloud 分支（保留 AudioPlay 分支，Phase 4 时再评估） |

#### 🟡 中风险修改

| # | 文件 | 修改 |
|---|------|------|
| M9 | `AppDatabase.kt` | 删除 `HttpTTSDao`, `HttpTTS` entity, `httpTTSDao` 属性；删除 onOpen 清理逻辑 |
| M10 | `AutoReadDialog.kt` | 删除 `upTtsSpeechRate()` 调用 |
| M11 | `ReadBookViewModel.kt` | 删除 `ReadAloud.stop()` + 进度检查 |
| M12 | `Book.kt` | 删除 `setTtsEngine()/getTtsEngine()` + `BookConfig.ttsEngine` |
| M13 | `ContentTextView.kt` | 删除 `getReadAloudPos()` |
| M14 | `PageView.kt` | 删除 `getReadAloudPos()` 委托 |
| M15 | `TextChapter.kt` | 删除 `getNeedReadAloud()` |
| M16 | `SourceLoginJsExtensions.kt` | 删除 HttpTTS 登录集成 |
| M17 | `SourceLoginViewModel.kt` | 删除 HttpTTS 相关逻辑 |

#### 🟢 低风险修改

| # | 文件 | 修改 |
|---|------|------|
| M18 | `EventBus.kt` | 删除 5 个 TTS 常量 |
| M19 | `PreferKey.kt` | 删除 ~10 个 TTS 配置键 |
| M20 | `NotificationId.kt` | 删除 `ReadAloudService = 101` |
| M21 | `AppConst.kt` | 删除 `channelIdReadAloud` |
| M22 | `AppPattern.kt` | 删除 `notReadAloudRegex` |
| M23 | `IntentAction.kt` | 删除 TTS 专用动作 |
| M24 | `AppConfig.kt` | 删除 TTS 配置属性 |
| M25 | `LocalConfig.kt` | 删除 `needUpHttpTTS` |
| M26 | `DefaultData.kt` | 删除 HttpTTS 默认数据方法 |
| M27-M37 | `Backup/Restore/Storage*` | 删除 HttpTTS 备份/恢复/存储逻辑 (~11 个文件) |
| M38 | `App.kt` | 删除 `readAloudChannel` 通知渠道 |
| M39 | `ShortCuts.kt` | 删除 `buildReadAloudShortCutInfo` |
| M40 | `CrashHandler.kt` | 删除 `ReadAloud.stop()` |
| M41-M52 | 其他 | 文本布局、配置等 |

### 共享文件（必须保留）

| 文件 | 原因 |
|------|------|
| **MediaHelp.kt** | AudioPlayService + VideoPlayService 共用音频焦点 |
| **ExoPlayerHelper.kt** | AudioPlayService 使用（视频删除后仍保留） |
| **help/TTS.kt** | RSS 阅读的独立 TTS 实现，不同系统 |

### 📍 关键耦合点详解

**1. ReadBook.kt:34 → BaseReadAloudService**
```kotlin
import io.legado.app.service.BaseReadAloudService
// 在 readAloud() 方法中调用 ReadAloud.play()
```
这是阅读界面的 TTS 入口。需删除 `readAloud()` 方法（约 7 行）。

**2. MainActivity.kt:135 → BaseReadAloudService.pause**
```kotlin
if (BaseReadAloudService.pause) {
    finish()
} else {
    moveTaskToBack(true)
}
```
删除后简化为 `moveTaskToBack(true)`。E-Ink 设备无需区分朗读状态。

**3. ReadAloudMiniBar**
`BaseActivity` 和 `ComposeActivitySupport` 各实现了 mini bar 宿主接口，需同步删除。`showReadAloudMiniBar()` 方法需要从 BaseActivity 中移除。

---

## 四、推荐删除顺序

```
Phase 4: 删除有声书（AudioPlay）
  └── 17 个独占文件删除 + 19 个共享文件修改
  └── 风险最低，耦合最少，先练手

Phase 5: 删除视频（VideoPlay）
  └── 33 个独占文件删除 + 26 个共享文件修改
  └── 需移除 Gradle 依赖，涉及 BookDao 查询修改

Phase 6: 删除 TTS（ReadAloud）
  └── 24 个独占文件删除 + 52 个共享文件修改
  └── 影响面最大：ReadBook + BaseActivity + ReadBookActivity + ReadView
  └── 需创建数据库迁移删除 httpTTS 表
```

### 统计数据

| 功能 | 独占文件 | 修改文件 | 高风险修改 | 中风险修改 |
|------|---------|---------|-----------|-----------|
| AudioPlay | 17 | 19 | 0 | 4 |
| VideoPlay | 33 | 26 | 1 | 6 |
| TTS | 24 | 52 | 8 | 9 |
| **合计** | **74** | **97** | **9** | **19** |
