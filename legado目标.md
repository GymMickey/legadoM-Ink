# Prompt 0（每次任务默认前缀）

这是 Legado M-Ink 项目。

## 项目目标

不是开发新的阅读软件，而是在保持与原版 Legado Max 完全兼容的前提下进行功能精简。

## 最高原则（不可违反）

1. 与原版数据库兼容
2. 与 Backup 完全兼容
3. 与 Restore 完全兼容
4. 与 WebDAV 完全兼容
5. 与书源、RSS、订阅、下载完全兼容
6. 保持官方 UI 设计语言

## 实现原则

对于任何需求，请先分析项目是否已经存在对应能力。

**如果已经存在**：必须直接复用。不得重新实现。不得新增业务逻辑。不得重复查询数据库。不得新增 Repository。不得新增统计逻辑。

**只有源码不存在该能力时**，才能新增实现，并说明原因。

**优先级**：① 复用已有业务逻辑 → ② 复用已有数据流 → ③ 复用已有 UI 组件 → ④ 复用已有导航 → ⑤ 最后才考虑新增代码

如果需要新增代码，请先说明为什么现有架构无法满足需求，再进行实现。任何情况下都不要为了快速完成任务而绕过现有架构。

## 工作流程

每次修改：分析 → 修改 → 编译 → 修复编译错误 → 停止。不要继续修改其它功能。

---

# Prompt 1：首页分析（只读）

请完整分析首页：入口、导航、数据来源、UI 组成、ViewModel、Repository、生命周期、公共组件、共享能力、作者设计意图、修改影响范围。

最后输出：首页架构图、数据流、修改风险。

不要修改代码。

---

# Prompt 2：首页改造为阅读仪表盘

根据上次分析，在保持官方 Legado Max UI 风格的前提下重新组织首页布局。

首页显示：① 最近阅读、② 阅读统计、③ 最近阅读书籍、④ WebDAV 快捷操作。

实现要求：确认已有数据来源 → 直接复用已有 Repository/ViewModel/业务逻辑。所有按钮必须直接调用已有功能（WebDAV 同步逻辑、阅读统计能力、阅读记录等）。不得重新实现、不得重复查询数据库、不得新增统计逻辑、不得新增 Repository。

完成后：编译 → 修复编译错误 → 停止。

---

# Prompt 3：分析待删除功能（只读）

请完整分析：AI、音乐播放、视频播放、TTS。

对每个功能找出：源码位置、菜单入口、设置入口、页面、ViewModel、Repository、Service、数据库、资源文件、依赖关系。同时判断公共组件被共享情况（播放器、Notification、Service、Settings、Reader）。

最后输出：每个功能的删除风险、推荐删除顺序。

不要修改代码。

---

# Prompt 4：删除 AI

请先确认 AI 使用了哪些已有能力。删除时不影响：阅读、书架、阅读统计、RSS、下载、WebDAV、Backup、Restore、数据库兼容。如果公共组件同时被其他功能使用，必须保留。

完成后：编译 → 修复编译错误 → 停止。

---

# Prompt 5：删除音乐播放

要求与 Prompt 4 相同。请先确认播放器是否属于共享能力——如果视频或其他功能仍在使用，不得删除共享播放器。只删除音乐功能。

完成后：编译 → 停止。

---

# Prompt 6：删除视频播放

请确认：视频是否复用了播放器、是否影响阅读、是否影响下载。

完成后：编译 → 停止。

---

# Prompt 7：删除 TTS

请确认：阅读器是否仍引用 TTS。如果阅读菜单存在 TTS 入口请一并移除。保持阅读功能正常。

完成后：编译 → 停止。

---

# Prompt 8：最终验证

请执行完整检查，确认以下功能正常：阅读、书架、最近阅读、阅读统计、RSS、书源、订阅、下载、Backup、Restore、WebDAV、首页、设置、导航。

如果发现因本次修改导致的问题请修复。完成后：编译 → 停止。

---

## 审查结论：Legado Max E-Ink 本地书籍管理开发计划

**审查日期：2026-07-15**

### 总体评价：可行，架构方向正确

计划的核心设计——AutoImportManager → ImportBook 统一入口——**完全正确**。`LocalBook` 已提供所有需要的导入能力，不需要重复实现。

### 已有组件（可直接复用，无需新建）

| 计划组件 | 已有实现 | 位置 |
|----------|---------|------|
| ImportBook 导入逻辑 | `LocalBook.importFile(Uri)` `LocalBook.importFiles(List<Uri>)` | LocalBook.kt:241-342 |
| 递归目录扫描 | `ImportBookViewModel.scanDoc()` — 16 并发递归 | ImportBookViewModel.kt:134-162 |
| 去重检测 | `LocalBook.isOnBookShelf(fileName)` | LocalBook.kt:469-473 |
| 默认书籍目录 | `AppConfig.defaultBookTreeUri` | 已在设置中配置 |
| 文件格式正则 | `bookFileRegex`: txt/epub/umd/pdf/mobi/azw3/azw `archiveFileRegex`: zip/rar/7z | AppPattern.kt:43-45 |
| HTTP 文件上传 | `BookController.addLocalBook()` — 已有，调 `LocalBook.saveBookFile()` + `importFile()` | BookController.kt:283-302 |
| 书架自动刷新 | `appDb.bookDao.flowAll()` 是 Room reactive Flow，insert 后自动通知 UI | 首页 ViewModel 已使用 |

### 需要调整的设计决策

#### 1. WiFi HTTP Server：不应新建第二个实例

计划 Phase 6 新建 `WiFiTransfer/HttpServer.kt`，但 HttpServer.kt **已经存在** 且已支持文件上传（`/addLocalBook` 端点）。

**风险：**
- 两个 NanoHTTPD 实例同时运行 → 端口冲突
- 两份 HTTP 服务器代码 → 维护负担
- 前端也分裂为两个（原有 `modules/web/` Vue 3 + 计划中的 `assets/wifi/` 静态 HTML）

**建议修改：**
- 将 WiFi 传书作为现有 HttpServer.kt 的扩展路由（新增 `/wifi-upload` POST 端点 + 静态资源路径 `wifi/`）
- 或者将 WiFi 前端页面集成进现有的 `modules/web/` Vue 3 项目

#### 2. cbz 格式：`bookFileRegex` 不支持，`archiveFileRegex` 不支持

`cbz` 本质是 zip 包，但不在任何已有正则中。Phase 2 如需支持 cbz，只需在 `archiveFileRegex` 中添加。

#### 3. PopupWindow vs Compose：建议用 Compose

计划 Phase 4 要求用 `PopupWindow`，但首页已经是全 Compose（HomepageScreen.kt），新增 Compose 入口可使用 `ModalBottomSheet` 或 `DropdownMenu` 保持一致性。

#### 4. 启动扫描需要防抖

Phase 2 在 App 启动时自动扫描，但 `scanDoc()` 对大目录（500+ 文件）耗时较长。建议加时间间隔检查（如上次扫描 5 分钟内不重复扫）。

### 各阶段可行性逐条验证

| Phase | 可行性 | 关键风险点 |
|-------|--------|-----------|
| Phase 0 | ✅ 可行 | `LocalBook.importFiles()` 已覆盖所有导入逻辑，Phase 0 分析后应明确不需要新建 ImportBook 调用层 |
| Phase 1 | ✅ 可行 | PreferenceFragment 模式成熟，`AppConfig.defaultBookTreeUri` 已有设置入口 |
| Phase 2 | ✅ 可行 | `ImportBookViewModel.scanDoc()` + `LocalBook.importFiles()` 可复用，AutoImportManager 作为薄封装层即可 |
| Phase 3 | ✅ 可行 | 只有菜单入\
口 + 调用 `AutoImportManager.scan()`，纯 UI |
| Phase 4 | ⚠️ 需调整 | PopupWindow → 建议用 Compose popup；WiFi Server 应复用已有 HttpServer.kt |
| Phase 5 | ⚠️ 需调整 | 静态 HTML/JS 没问题，但应与现有 `modules/web/` 统一或明确分界 |
| Phase 6 | ⚠️ 需调整 | 不应新建独立 HttpServer。应扩展现有 HttpServer.kt 新增 upload 路由 |
| Phase 7 | ✅ 可行 | 标准测试流程 |

### 关键意见

整个计划中 **最有价值的部分是 Phase 2 的 AutoImportManager 统一入口**。它解决了以后每加一个导书方式都需要重复写胶水代码的问题。

**唯一需要重新考虑的架构决策是 WiFi 传书的 HTTP 服务器设计**。已有 HttpServer.kt（NanoHTTPD）+ BookController.addLocalBook() 已经实现了"接收上传 → 保存文件 → 导入书架"全流程，新建第二个服务器是重复造轮子。建议 Phase 0 深入分析现有 HttpServer.kt 的路由和生命周期，确认能否复用。

---

## 早期修复与基础设施（2026-07-05 ~ 2026-07-16）

基于 Legado Max 分支后，在开始大规模精简前进行的基础修复。

### v3.26-beta51（2026-07-05）

| # | 修复 | 说明 |
|---|------|------|
| 1 | 首页累计阅读数目与阅读记录不一致 | `ReadRecordDao.count` 与设置页统计口径不同，统一数据源 |
| 2 | 首页与阅读记录页阅读时长算法统一 | 统一为 repository 层 `getTotalReadTime()` SQL 聚合 |
| 3 | 自动更新调用坏接口 + beta 版本比较逻辑 | 修复检查更新请求失败 + beta 版本号对比错误 |
| 4 | 首页统计实时刷新 | 修复统计值缓存不更新，改用 Flow 响应式刷新 |
| 5 | 检查更新 URL 修复 + 首页书籍去重 + 备份按钮文案 | 更新 API 地址、书架重复显示、恢复按钮文本修正 |
| 6 | 首页恢复功能闪退 | 修复恢复时取最新备份失败导致 crash |
| 7 | 首页恢复 RECREATE 时协程取消误报失败 | `viewModelScope` → `GlobalScope` 避免 Activity 重建时协程被取消 |
| 8 | 去掉签名密码硬编码默认值 | 未配置时直接报错而非静默使用空密码 |

### v3.26-beta66（2026-07-16）

| # | 改动 | 说明 |
|---|------|------|
| 1 | 首页继续阅读卡片点击直接进入阅读界面 | 替代原来点击进入书籍详情再点阅读的两步操作 |
| 2 | 修复新加入书架的未阅读本地书籍出现在首页 | 已读书籍按 `ReadRecord` 表交叉判断 `name + author` |
| 3 | 签名密码移至用户级 `GRADLE_USER_HOME` | 避免项目文件提交密码 |

### 项目更名（2026-07-18 ~ 2026-07-19）

| 改动 | 说明 |
|------|------|
| Launcher 名称统一为「阅读M-Ink」 | 修复中文 locale 下桌面图标名未更改的问题 |
| APK 文件名前缀统一为 LegadoM-Ink | `legadoM-Ink-v3.26-beta.XX.apk` |
| 项目根名称更新为 LegadoM-Ink | 目录 + Git remote |
| 关于页：阅读Max → 阅读M-Ink | 新增开发者 Mickey、chesm |
| 更新日志重写 | 删除项目继承历史，改为记录本项目功能变更 |

---

## 已完成的精简工作（2026-07-16 ~ 2026-07-18）

### E-Ink 墨水屏适配

| 修改 | 文件 | 效果 |
|------|------|------|
| 翻页动画跳过 | `PageDelegate.kt` | E-Ink 模式下 Scroller duration=0，无残影 |
| 快速滚动条动画跳过 | `FastScroller.kt` | 4 处动画（showBubble/hideBubble/showScrollbar/hideScrollbar）直接设 alpha，跳过 ViewPropertyAnimator |
| 列表项入场动画跳过 | `RecyclerAdapter.kt` | addAnimation() 入口 E-Ink 直接 return |

### 已删除功能

| 批次 | 内容 | 文件数 |
|------|------|--------|
| Phase 4 | 有声书（AudioPlay）全部删除 | Activity + Service + Model + 资源 |
| Phase 5 | 视频播放（VideoPlay）全部删除 | Activity + Service + Model + GSYVideoPlayer |
| Phase 6 | TTS 朗读死代码清理 | 29 文件，616 行删除 |
| — | 精准管理模块删除 | ~25 文件 + 1 XML + 1 menu + ~40 字符串键 |
| — | 书源校验（新界面 Compose）删除 | 3 文件 + 1 菜单项 + 1 Manifest 声明 |

### TTS 清理详情（Phase 6）

**已删除：**
- `BaseReadAloudService`、`TTSReadAloudService`、`HttpReadAloudService`、`ReadAloud` model
- `ReadAloudDialog`、`ReadAloudConfigDialog`、`SpeakEngineDialog`、`HttpTtsEditDialog`、`TtsDebugActivity`、`ReadAloudActivity`
- `ReadAloudMiniBarController`、`InputStreamDataSource`
- 朗读高亮 span 死代码：`TextLine.isReadAloud`、`TextPage.hasReadAloudSpan`、`upPageAloudSpan()`
- `ReadMenu` 中的 `showReadAloudDialog()` / `onClickReadAloud()` 接口方法
- `ReadBookActivity` 中的空壳实现
- `DefaultData.httpTTS` 死属性、`LocalConfig.needUpHttpTTS` 死属性
- 23 个 TTS 孤儿字符串（8 语言）、`dialog_http_tts_edit.xml`

**⚠️ 保留（数据库兼容）：**
- `HttpTTS.kt` entity — Room schema 不变
- `Book.ttsEngine` / `BookConfig.ttsEngine` — 数据库字段不动
- `help/TTS.kt` — RSS 独立 TTS 仍在使用
- `channelIdReadAloud` 常量 — CheckSourceService 共用此通知渠道

### AudioPlay/VideoPlay 清理详情

**孤儿资源已清理：**
- 8 语言 strings.xml：`audio_play`、`audio_play_t/s`、`audio_play_wake_lock` 等
- 8 语言 strings.xml：`video_play`、`open_other_video_player`
- AndroidManifest.xml：视频播放器注释
- service/README.md：已删服务引用

### Gradle 构建修复

- 签名密码从项目级 `gradle.properties` 移至用户级 `GRADLE_USER_HOME/gradle.properties`
- 修复 GRADLE_USER_HOME 旧密码覆盖新密码的问题
- 发布 v3.26-beta.66

### 其他修改

| 日期 | 内容 |
|------|------|
| 2026-07-18 | 修复 Flyme 12.6 书架布局弹窗闪现（AlertDialog → DialogFragment） |
| 2026-07-18 | 版本号 beta 计数：build_number.txt → Git 提交数（`git rev-list HEAD --count`） |
| 2026-07-18 | 关于页改名：阅读Max → 阅读M-Ink，新增 Mickey/chesm 开发者 |
| 2026-07-18 | 修复书源校验 startForeground 崩溃（channelIdReadAloud → channelIdDownload） |

### Widget 替换（W1/W2/W3，2026-07-19）

| 任务 | 内容 | 结果 |
|------|------|------|
| W1 | CircleImageView → ShapeableImageView，5 处 XML + attrs.xml 清理，删除 CircleImageView.kt | 编译通过。副作用：`ReadStyleDialog` 样式选择器丢失圆形点击范围判断（isInView 未迁移），影响极小，已确认忽略 |
| W2 | SmoothCheckBox → MaterialCheckBox，仅设置页面（dialog_read_book_style.xml、dialog_read_padding.xml） | 编译通过。dialog_select_section_export.xml 的非设置页用法按计划跳过 |
| W3 | RotateLoading E-Ink/普通双分支（onDraw/startAnimator/stopAnimator 三处判断 AppConfig.isEInkMode） | 编译通过。E-Ink 静态文字提示，普通模式保留原动画，零 XML/调用方改动 |

### ThemeStore 死代码清理（2026-07-19）

前置于 Phase 3B（E-Ink 颜色适配），基于全项目引用检索确认删除：

| 文件 | 删除内容 |
|------|----------|
| `ThemeStoreInterface.kt` | 整个文件（仅被 ThemeStore 自身 implements，零外部调用） |
| `ThemeStore.kt` | `markChanged()`、`isConfigured(context)`、`isConfigured(context, version)`，及 `: ThemeStoreInterface` 实现 |
| `ThemeStorePrefKeys.kt` | `IS_CONFIGURED_VERSION_KEY` 常量 |
| `ViewUtils.kt` | `removeOnGlobalLayoutListener()`、`setBackgroundTransition()`、`setBackgroundColorTransition()`（保留 `setBackgroundCompat()`，被 TintHelper.kt 使用） |
| `Selector.kt` | `DrawableSelector` 内部类 + `drawableBuild()` 工厂方法（保留 ShapeSelector/ColorSelector） |
| `MaterialValueHelper.kt` | `Context.primaryColorDark` 扩展属性 |

**排查后确认保留**：`MaterialValueHelper.kt` 中 10 个 `Fragment.*` 扩展属性（primaryColor/accentColor/backgroundColor/... /isDarkTheme）——grep 未命中因 Kotlin 扩展属性同名重载无法字面匹配，删除后编译报错，已全部恢复，实际有 80+ 处调用。编译通过。

### Phase 7 最终验证（2026-07-19）

全部通过，无阻塞问题：

| 检查项 | 结果 | 备注 |
|--------|------|------|
| 编译 | ✅ | BUILD SUCCESSFUL，无新增 warning |
| 已删类/方法残留引用 | ✅ | 仅 updateLog.md/README.md 文档提及已删功能名（历史记录，保留） |
| AndroidManifest.xml | ✅ | 无已删 Activity/Service 声明残留 |
| 数据库兼容性 | ✅ | Room schema v100 不变，HttpTTS entity 仍在，Book.ttsEngine 未删除 |
| 关键文件引用 | ✅ | ReadBook/MainActivity/BaseActivity 均已解耦，MediaHelp 已删除无残留 |
| 功能入口完整性 | ✅ | 5 个底部 tab 全部存在，阅读/书架/书源/设置/RSS 入口正常 |
| 资源完整性 | ✅ | 仅清理 1 个孤儿布局 `activity_tts_debug.xml`（已删 TtsDebugActivity 残留） |

保留的已知非阻塞项：`updateLog.md` 中提及已删功能名（历史发布记录）、`PreferKey.videoSetting` 死常量、配置文件中 TTS 相关死字符串——均不阻塞编译和运行。

### 首页卡片加载性能优化（2026-07-19）

**Commit**: `68f8474` + `2b8fea8`

| 优化 | 文件 | 改动 |
|------|------|------|
| P0 SQL 聚合 | `ReadRecordRepository.kt` | `getTotalReadTime()` 从全表内存聚合改为 `SELECT SUM(readTime)` SQL |
| P1 Room Flow | `ReadRecordDao.kt` | 新增 `observeCount()` Flow 方法，替代同步 `count` 属性 |
| P2 轻量查询 | `BookDao.kt` | 新增 `flowHomepageBooks()` — 11 列子集查询 + SQL 层 `isNotShelf` 过滤，替代 `flowAll()` SELECT * |
| P2 ViewModel | `HomepageViewModel.kt` | `dashboardState` 合并 5 条 Flow 流，`WhileSubscribed(5000)` 生命周期感知 |
| P2 数据类 | `HomepageContract.kt` | 新增 `HomepageBookSummary` 数据类 + `toBook()` 映射方法 |

**Bug 修复**（`2b8fea8`）：
- **问题**：首次使用需阅读 ~1 分钟首页才出现卡片。根因 `durChapterIndex > 0` 过滤（chapter 0 = 第一章被误杀）
- **修复**：移除 `durChapterIndex` 过滤，改用 `ReadRecord` 表交叉引用 `observeAllReadBookKeys()` 判断"已读"
- **匹配方式**：`name + author` 联合匹配（`ReadRecord` 表无 `bookUrl` 字段的已知限制，已文档化）

### E-Ink 动画全局优化 · 第一轮（2026-07-19）

**原则**：只改 `if (AppConfig.isEInkMode)` 分支，LCD/暗色模式行为不变。不动刷新接口、E-Ink HAL、阅读核心、数据库、翻页逻辑。

**已有保护（之前完成）**：PageDelegate 翻页 · FastScroller 滚动条 · RecyclerAdapter 列表入场 · BaseDialogFragment 对话框 · ReadMenu/MangaMenu/SearchMenu 菜单 · RotateLoading 加载指示器 · AutoPager 自动翻页 · ThemeBottomNavigationView 底部导航

| 优先级 | 项目 | 文件 | 改动 |
|--------|------|------|------|
| 🔴 H9 | RippleDrawable 触摸波纹 | `TintHelper.kt` | Button/FAB/Switch 3 处 `!AppConfig.isEInkMode &&` 跳过 RippleDrawable 着色 |
| 🔴 H8 | RecyclerView ItemAnimator | `ExploreShowFragment.kt` · `ExploreFragment.kt` | List/Grid 模式 E-Ink 下 `itemAnimator = null` |
| 🟡 H6 | BottomSheetDialogFragment 动画 | `BottomWebViewDialog.kt` · `SearchSourceStatusDialog.kt` · `ReadWebSearchPanel.kt` | `onStart()` 中 `windowAnimations = 0` + dim 清除 |

### E-Ink 动画全局优化 · 第二轮（2026-07-19）

| 优先级 | 项目 | 文件数 | 改动 |
|--------|------|--------|------|
| 🟡 M4 | AnimatedContent 页面切换 | `HomepageModuleManageSheet.kt` | E-Ink 下 `EnterTransition.None / ExitTransition.None` 替代 slideInHorizontally/slideOutHorizontally |
| 🟡 M1 | AnimatedVisibility 展开/折叠 | 8 文件 12 处 | enter/exit 全部替换为 `EnterTransition.None / ExitTransition.None`（原动画：fadeIn/fadeOut、expandVertically/shrinkVertically、默认 fade） |
| 🟡 M2 | DropdownMenu 下拉菜单 | `AppDropdownMenu.kt`（新）+ 5 文件 7 处 | E-Ink 模式用 Popup + Surface 静态显示（shadowElevation=0），LCD 模式代理到 Material3 DropdownMenu |

**M1 涉及文件**：`BlockRuleConfigDialog.kt`(4) · `DebugLogScreen.kt`(1) · `RssExecutionStatus.kt`(2) · `EntityDisplay.kt`(1) · `CoverGalleryScreen.kt`(1) · `TopFloatingStickyItem.kt`(1) · `ReadRecordScreen.kt`(1) · `BookReadRecordActivity.kt`(1)

**M2 涉及文件**：`BlockRuleConfigDialog.kt` · `ReadRecordScreen.kt` · `DebugLogScreen.kt` · `CoverGalleryScreen.kt` · `DirectLinkUploadScreen.kt`

**已评估并跳过**：Compose ModalBottomSheet × 5（`animationSpec` API internal，E-Ink Spring 动画影响极小）· SmoothCheckBox（仅 1 个对话框使用）· WebtoonRecyclerView（漫画非核心路径）· ExplosionView（娱乐效果）· Skeleton shimmer（加载态）· PopupMenu（25+ 处无统一入口，收益/工作量比低）· ScrollTextView/PhotoView（非核心阅读流程）

---

## 剪贴板传输功能 — 实现思路分析（2026-07-19）

为 WiFi 传书新增「剪贴板历史」功能，让手机一次发送多段文本到阅读器，阅读器保存为历史记录按需复制。同时优化 WiFi 首页布局。

### 一、现状总结

| 层面 | 现状 |
|------|------|
| HTTP 服务器 | `HttpServer.kt` — NanoHTTPD，端口 1122，路由硬编码在 `serve()` 的 `when()` 表达式中，无路由抽象层 |
| WiFi 服务 | `WebService.kt` — Android Foreground Service，管理 HttpServer + WebSocketServer 生命周期 |
| WiFi UI（Android 端） | **仅为两个 AlertDialog**：`HomepageFragment.showWifiTransferDialog()`（首页菜单触发）和 `LocalConfigFragment.showWifiTransferDialog()`（设置页触发）——两者代码**完全重复**，只有二维码 + URL + 确定按钮 |
| Web 前端 | `wifi/index.html` — 自包含，只做拖拽上传；`uploadBook/index.html` — jQuery 旧版。**二维码在前端不显示** |
| 上传接口 | `POST /addLocalBook` (multipart/form-data) → `BookController.addLocalBook()` |
| 数据库 | Room v100，31 个 entity。已有的 `UploadHistory` 是**直链上传规则**的历史（外键 `ruleId` → `DirectLinkUploadRule`），不适用于 WiFi 传书 |
| 路由注册 | `apiPaths: Set<String>` 硬编码，新增路由需同时修改此 set 和 `serve()` 中的 3 个 when 分支 |

### 二、需要新建/修改的文件

#### 📦 数据层（2 新 + 1 改）

| 文件 | 操作 | 说明 |
|------|------|------|
| `data/entities/WifiClipboard.kt` | **新建** | 剪贴板历史 Entity：`id, content, time`。最多 50 条，去重（相同 content 更新 time 并置顶），content 限制 5000 字 |
| `data/dao/WifiClipboardDao.kt` | **新建** | `@Transaction insertOrUpdate(content)` 封装完整去重逻辑；`deleteAll()` / `flowAll()` / `trimToMax(50)` |
| `data/AppDatabase.kt` | **修改** | 新增 1 个 entity + 1 个 DAO + 版本 100→101 + AutoMigration |

**关键设计决策：**
- ✅ Room 新表而非 JSON 文件 — 项目已有成熟迁移体系，新表不影响旧表兼容
- ✅ 命名 `WifiClipboard` 而非 `ClipboardItem` — 避免与 Android SDK `ClipboardManager` 混淆
- ✅ 单 `time` 字段（无 `createTime`）— 剪贴板场景 createTime 无独立意义，更新时覆盖即可
- ✅ 去重逻辑封装在 DAO `@Transaction insertOrUpdate()` — HTTP / 内部复制 / 未来 WebSocket 均可复用，不绑 Controller

#### 🌐 HTTP 接口层（1 新 + 1 改）

| 文件 | 操作 | 说明 |
|------|------|------|
| `api/controller/ClipboardController.kt` | **新建** | `receiveClipboard(postData: String?)` — 解析 JSON `{"items": [...]}` → 按空行分割（连续空行视为一个）→ `content.trim().take(5000)` → `dao.insertOrUpdate()` |
| `web/HttpServer.kt` | **修改** | 3 处：① `apiPaths` 加 `"/clipboard"` ② POST `when()` 添加路由分发 ③ 导入 ClipboardController |

#### 📱 Android UI 层

**③A：WiFi 首页改造（替代 AlertDialog → 新 Fragment）**

当前 `showWifiTransferDialog()` 在 `HomepageFragment` 和 `LocalConfigFragment` 中**代码完全重复**，改为统一的新 Fragment 后两处都只需一行跳转。Dialog 在墨水屏上体验差（弹出动画、遮罩灰度），Fragment 无弹出动画、无遮罩、可滚动。

| 文件 | 操作 | 说明 |
|------|------|------|
| `ui/wifi/WifiTransferFragment.kt` | **新建** | 3 个区域：① 二维码（缩至 70-80dp）+ 地址 ② 剪贴板预览（3-5 条，点击跳转完整页）③ 上传书籍入口。上传历史（WifiUploadRecord）推后到可选 Phase |
| `ui/wifi/WifiTransferViewModel.kt` | **新建** | 管理数据加载，复用已有 DAO Flow |
| `res/layout/fragment_wifi_transfer.xml` | **新建** | 或用 Compose（项目已有 Compose BOM 2025.04.01） |
| `ui/main/homepage/HomepageFragment.kt` | **修改** | `showWifiTransferDialog()` 改为启动 WifiTransferFragment |
| `ui/config/LocalConfigFragment.kt` | **修改** | 同上，消除重复代码 |

**③B：剪贴板历史页面**

| 文件 | 操作 | 说明 |
|------|------|------|
| `ui/wifi/ClipboardActivity.kt` | **新建** | RecyclerView 列表：内容（2 行省略）、时间、复制按钮、删除按钮 |
| `res/layout/activity_clipboard.xml` | **新建** | 顶部「清空全部」+ RecyclerView |
| `res/layout/item_clipboard.xml` | **新建** | 列表项布局 |
| `ui/wifi/ClipboardAdapter.kt` | **新建** | RecyclerView Adapter，复制→系统剪贴板 + Toast「已复制」 |
| `AndroidManifest.xml` | **修改** | 注册 ClipboardActivity |

**E-Ink 适配**：RecyclerView 设置 `itemAnimator = null`（已有模式：`ExploreFragment.kt`）

#### 🌍 Web 前端层（1 改，单页方案）

Web 页面**不做 Tab 导航**，上传与剪贴板合并在同一个页面内，更简洁，适合手机和 E-Ink 浏览器。

| 文件 | 操作 | 说明 |
|------|------|------|
| `assets/web/wifi/index.html` | **修改** | 上半部分：文件拖拽上传（已有）；下半部分：textarea + 发送按钮（新增）。两区之间分隔线区分 |

**页面布局：**
```
┌─ WiFi 传书 ────────────┐
│  📤 选择文件上传        │
│  [拖拽区域]            │
│  ────────────────────  │
│  📋 发送文本            │
│  [textarea]           │
│  [发送]               │
└────────────────────────┘
```

**发送 JS 核心逻辑：**
```javascript
// 按空行分割（连续空行视为一个分隔）
function splitByEmptyLines(text) {
    return text.split(/\n\s*\n/).map(s => s.trim()).filter(s => s.length > 0);
}
// POST /clipboard → JSON { items: [...] }
```

### 三、实现顺序（依赖关系）

```
Phase 1: 数据层
├── WifiClipboard.kt（entity: id, content, time）
├── WifiClipboardDao.kt（insertOrUpdate + deleteAll + flowAll + trimToMax）
├── AppDatabase.kt（v101 + entity + DAO）
└── → 编译 ✅

Phase 2: HTTP 接口
├── ClipboardController.kt（receiveClipboard，含 5000 字限制）
├── HttpServer.kt（POST /clipboard 路由）
└── → 编译 ✅

Phase 3: Web 前端（单页合并）
├── wifi/index.html（上传区 + 文本发送区，无 tab / 无独立页面）
└── → 浏览器独立测试

Phase 4: WifiTransferFragment（替代两处重复 Dialog）
├── WifiTransferFragment.kt + ViewModel + 布局
├── HomepageFragment.kt + LocalConfigFragment.kt（改为一行跳转）
└── → 编译 ✅

Phase 5: 剪贴板历史页
├── ClipboardActivity.kt + Adapter + 布局
├── AndroidManifest.xml
└── → 编译 ✅ → 完整测试

Phase 6: 上传历史（可选，独立加，不阻塞）
├── WifiUploadRecord entity + DAO + BookController 修改 + Fragment 区域
└── → 以后再做
```

### 四、关键设计决策

| 决策 | 理由 |
|------|------|
| 不修改 HttpServer 架构 | 只在现有 `when()` 分支添加一个 case，不引入路由框架 |
| 不新增 HTTP Server | 复用 NanoHTTPD 实例，避免端口冲突 |
| 不修改 Book/ReadRecord/AppConfig | 新 entity 完全独立 |
| `/clipboard` 放入 `apiPaths` | 自动获得 Bearer Token 鉴权（与上传接口一致） |
| WifiUploadRecord 推后到可选 Phase | 投入大（Entity/DAO/Migration/UI），价值小（仅信息展示），砍掉后代码量减半 |
| DAO 封装 `@Transaction insertOrUpdate()` | 去重逻辑不绑 Controller，HTTP/内部复制/WebSocket 均可复用 |
| 统一 Fragment 替代两处重复 AlertDialog | `HomepageFragment` + `LocalConfigFragment` 中代码完全相同，Dialog 在墨水屏上体验也差 |
| Web 单页方案（无 Tab） | 上传 + 文本发送合并一页，减少导航层次，手机和 E-Ink 浏览器都适用 |

### 五、风险与应对

| 风险 | 应对 |
|------|------|
| Room 迁移 v100→v101 | 新表 `@Entity` 自动建表，`exportSchema=true` + AutoMigration 安全。上线前测试旧版 DB 恢复 |
| E-Ink 模式 RecyclerView 动画残留 | `itemAnimator = null`（已有先例：ExploreFragment.kt） |
| 剪贴板去重性能 | `@Transaction` 内单次查询 + insert/update，单表 ≤50 行无性能问题 |
| 大文本误写入 | Controller 入口 `content.take(5000)`，DAO 层不重复检查 |
| 备份兼容 | Room 新表自动包含在 `legado.db` 备份中，恢复时表结构一致即可 |

### 六、预估改动量

- **新建文件：** 8 个（1 entity + 1 dao + 1 controller + 1 fragment + 1 viewmodel + 1 activity + 1 adapter + 1 layout）
- **修改文件：** 4 个（AppDatabase + HttpServer + HomepageFragment + LocalConfigFragment + AndroidManifest + index.html）
- **总代码量估计：** ~500-600 行（不含布局 XML）

### 七、GPT 审查与修正（2026-07-19）

GPT 审查了初版方案（~1000 行，12 新文件），8 条意见**全部采纳**：

| # | 意见 | 影响 | 修正 |
|---|------|------|------|
| ① | `ClipboardItem` 改名 | 低 | → `WifiClipboard`，避免与 Android `ClipboardManager` 混淆 |
| ② | content 加长度限制 | 低 | → Controller 入口 `take(5000)`，防大文本写库 |
| ③ | 合并 createTime + updateTime | 低 | → 单字段 `time`，剪贴板场景 createTime 无独立意义 |
| ④ | 去重逻辑下沉到 DAO | **高** | → `@Transaction insertOrUpdate()`，HTTP/内部复制/WebSocket 均可复用 |
| ⑤ | WifiTransferFragment 价值认可 | 中 | → 确认优先级，同时消除两处重复 Dialog 代码 |
| ⑥ | Web 不做 Tab 导航 | 中 | → 单页方案，上传 + 文本发送合并，砍掉 `clipboard.html` 独立页面 |
| ⑦ | 砍掉 WifiUploadRecord | **极高** | → 推后到可选 Phase 6，先完成剪贴板闭环。直接省 1 entity + 1 dao + 1 migration + BookController 修改 + UI 区域 |
| ⑧ | Room 迁移测试提醒 | 低 | → 实现后验证旧版 DB 恢复 |

**修正前后对比：**

| 指标 | 初版 | 修正后 |
|------|------|--------|
| 新 entity | 2 个 | **1 个** |
| 新 DAO | 2 个 | **1 个** |
| 新 Web 页面 | 1 个独立页 | **0 个**（合并） |
| 新文件总数 | 12 个 | **8 个** |
| 修改文件数 | 6 个 | **4 个** |
| 代码量 | ~900 行 | **~550 行** |
| Phase 数 | 6 | **5 + 1 可选** |

砍掉的内容：`WifiUploadRecord` entity + DAO + Migration + `BookController` 修改 + `clipboard.html` 独立页面。保留 90% 实际价值，代码量降低约 40%。

---

## WiFi 传书增强与剪贴板传输 — 实际实现与修复汇总（2026-07-19）

基于上方分析计划，完成 5 阶段实现后，按用户反馈进行多轮迭代修复。最终状态如下。

### 核心功能一：剪贴板传输（新增）

手机 → 阅读器的跨设备文本传输，替代手动打字/蓝牙传文本。

**完整链路：**

```
手机浏览器打开 WiFi 传书页面
        │
        ▼
  在 textarea 粘贴文本（多段用空行分隔）
        │
        ▼
  点击"发送" → POST /clipboard
        │  JSON: {"items": ["段落1", "段落2", ...]}
        ▼
  接收端 ClipboardController
        │  ├── 按空行分割 items
        │  ├── 每段 trim().take(5000)
        │  └── dao.insertOrUpdate() 去重写入
        ▼
  WifiTransferDialogFragment
        │  └── 显示剪贴板历史（5条，实时 Flow）
        │       ├── 点击"复制" → 写入系统剪贴板
        │       └── 点击"删除" → 从历史移除
        ▼
  用户在任何 App 粘贴使用
```

**设计要点：**
- 去重：相同 content 不新增记录，只更新时间戳置顶
- 上限：最多 5 条，超限自动裁剪最旧记录
- 安全：5000 字截断（Controller 层），Bearer Token 鉴权（复用 apiPaths）
- 不存数据库新表不影响备份兼容（Room AutoMigration）

### 核心功能二：WiFi 传书增强

发送端防重复 + 接收端目录检测 + 上传历史 + 文件删后自动清理书架。

### 实际落地架构

```
发送端 (Web)                          接收端 (Android)
─────────────                        ─────────────────
拖拽/选择文件                         HttpServer.kt (NanoHTTPD)
   │                                    ├── POST /addLocalBook → BookController
   │  ┌─ 检查重复(同名同大小)            │      ├── isOnBookShelf() 检测重复
   │  │                                   │      ├── saveBookFile() 保存文件
   ├──┤                                   │      ├── importFile() 导入书架
   │  │                                   │      └── WifiUploadRecord 记录历史
   │  └─ 过滤重复 → 提示已过滤 N 个       │
   │                                    ├── POST /clipboard → ClipboardController
   ├── 点击上传                           │      └── insertOrUpdate() 去重写入
   │  ├── 跳过已完成项                    │
   │  ├── 显示服务端错误(书籍已存在等)     │  WifiTransferDialogFragment (BaseDialogFragment)
   │  └── 状态持久化(删除文件不丢失)       │      ├── 二维码 + URL
   │                                    │      ├── 最近上传 (6条, 两列, 可清空)
   └── 剪贴板文本输入区                    │      ├── 剪贴板历史 (5条, 可复制/删除)
      ├── 空行分割多条                     │      └── 目录未设置 → dismiss + 引导
      └── 发送 JSON {items: [...]}         │
```

### 新增文件

| 文件 | 说明 |
|------|------|
| `data/entities/WifiClipboard.kt` | 剪贴板历史 Entity：id, content, time |
| `data/dao/WifiClipboardDao.kt` | @Transaction insertOrUpdate() + deleteAll + flowAll + trimToMax(5) |
| `data/entities/WifiUploadRecord.kt` | 上传历史 Entity：id, fileName, uploadTime |
| `data/dao/WifiUploadRecordDao.kt` | flowAll() + insert + trimToMax(6) + deleteAll() |
| `api/controller/ClipboardController.kt` | receiveClipboard() → 按空行分割 → content.take(5000) → dao.insertOrUpdate() |
| `ui/wifi/WifiTransferDialogFragment.kt` | BaseDialogFragment 弹窗，92%×75% 屏幕，E-Ink 适配 |
| `ui/wifi/ClipboardAdapter.kt` | ListAdapter + DiffUtil，复制(发送到系统剪贴板) + 删除 |
| `ui/wifi/UploadHistoryAdapter.kt` | 两列上传历史 Adapter |
| `res/layout/dialog_wifi_transfer.xml` | 弹窗布局 |
| `res/layout/item_clipboard.xml` | 剪贴板列表项：内容(maxLines=3) + 复制 + 删除 |
| `res/layout/item_upload_history.xml` | 上传历史项：单行文件名 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `data/AppDatabase.kt` | v100→v102 AutoMigration，新增 2 entity + 2 DAO |
| `web/HttpServer.kt` | apiPaths 加 /clipboard，POST when() 加分发 |
| `api/controller/BookController.kt` | addLocalBook() 加 isOnBookShelf() 重复检测 + WifiUploadRecord 记录 + trimToMax(6) |
| `ui/main/homepage/HomepageFragment.kt` | showWifiTransferDialog() 替换为 WifiTransferDialogFragment |
| `ui/config/LocalBookConfigFragment.kt` | 同上替换 + 删除 WiFi 传书入口 |
| `AndroidManifest.xml` | 已删除 ClipboardActivity 声明 |
| `assets/web/wifi/index.html` | 单页方案：文件上传 + 剪贴板文本发送 + 多项修复 |
| `res/xml/pref_config_local_book.xml` | 删除 WiFi 传书入口分类 + autoScanLocalBooks/scanSubDirs 默认值 true→false |

### 各轮修复详情

#### 第一轮：架构落实（Phase 1-5）

- 数据层：Room v100→v101→v102 AutoMigration
- HTTP 接口：ClipboardController + /clipboard 路由
- Web 前端：单页方案（上传 + 文本发送合并）
- Android UI：WifiTransferDialogFragment 替代两处重复 AlertDialog
- 剪贴板历史：ClipboardActivity + Adapter

#### 第二轮：Bug 修复

| # | 问题 | 修复 |
|---|------|------|
| 1 | ClipboardActivity 无内容 + 状态栏重叠 | 删除 ClipboardActivity，所有内容迁入 WifiTransferDialogFragment |
| 2 | 上传历史缺失 | 实现 WifiUploadRecord entity + DAO，BookController 记录上传 |
| 3 | Web 页面删除文件后状态重置 | files 数组重构为 `{file, statusClass, statusText}` 对象 |
| 4 | 已完成项目可重复上传 | doUpload() 跳过 status-ok 项，禁用条件含已完成判断 |

#### 第三轮：体验优化

| # | 改动 | 效果 |
|---|------|------|
| 1 | 弹窗扩大至 92%×75% 屏幕 | 容纳更多内容 |
| 2 | 去掉 Emoji + 时间显示 | 简洁 E-Ink 风格 |
| 3 | 上传历史两列布局 | GridLayoutManager(2)，省纵向空间 |
| 4 | 上传历史 5→6 条 + 清空按钮 | 更多记录 + 一键清空 |
| 5 | 剪贴板间距 16dp→8dp | 列表更紧凑 |
| 6 | 标题字体统一 15sp bold | UI 一致性 |

#### 第四轮：安全与入口

| # | 改动 | 效果 |
|---|------|------|
| 1 | 目录未设置拦截 | dismiss 弹窗 + LENGTH_LONG Toast 引导设置 |
| 2 | 服务端重复检测 | `isOnBookShelf()` 前置检查，已存在返回 errorMsg |
| 3 | 发送端展示具体错误 | `data.errorMsg \|\| '失败'`，需设置目录 / 书籍已存在 直接可见 |
| 4 | 发送端过滤重复文件 | 同名同大小文件提示"已过滤 N 个" |
| 5 | 发送端移除 ZIP/RAR/7Z | 只保留 TXT/EPUB/PDF/MOBI/AZW/AZW3/UMD |
| 6 | 删除本地书籍管理 WiFi 入口 | 精简设置页面 |

### 最终文件统计

| 指标 | 数量 |
|------|------|
| 新增文件 | 11 个 |
| 修改文件 | 9 个 |
| 删除文件 | 2 个（ClipboardActivity.kt + activity_clipboard.xml） |

### 遵守的约束

- ✅ 不修改 HttpServer 架构（仅 when() 加一个 case）
- ✅ 不新增 HTTP Server（复用 NanoHTTPD 实例）
- ✅ 不修改 Book / ReadRecord / AppConfig 结构
- ✅ 不破坏备份恢复兼容（Room AutoMigration）
- ✅ 不新增 Repository / 统计逻辑
- ✅ 复用已有组件：BaseDialogFragment（E-Ink 保护）、isOnBookShelf()、FileDoc.exists()、book.delete()

---

## 本地书籍管理：文件删除自动清理书架（2026-07-19）

实现 `cleanMissingBooks()`，补齐本地书籍管理闭环：文件被删 → 书架同步移除。

### 链路

```
文件被用户/文件管理器删除
        ↓
下次 AutoImportManager 扫描触发时
        ↓
cleanMissingBooks() — 遍历 type & local > 0 的所有 Book
        ↓
FileDoc.fromUri(book.bookUrl, false).exists()
        ↓  false（文件不存在）
book.delete() — 移除书架记录
```

### 改动

| 文件 | 改动 |
|------|------|
| `model/localBook/LocalBook.kt` | 新增 `cleanMissingBooks()`：遍历本地书籍，bookUrl 文件不存在则 `book.delete()`，返回清理数量 |
| `model/localBook/AutoImportManager.kt` | `scanAndImport()` 开头调用 `cleanMissingBooks()`，先清理残影再扫描新书 |
| `App.kt` | `autoScanLocalBooks` 运行时默认值 true→false（与 XML 保持一致） |

### 设计要点

- **判断依据用 bookUrl 而非文件名**：同一文件名可在不同目录，bookUrl 是导入时记录的唯一真实路径
- **FileDoc.exists() 同时支持 file:// 和 content://**：SAF 和直接路径均正确判断
- **book.delete() 与官方书架删除一致**：删 Book 行 + ReadBook 引用置空，不级联删 BookChapter/ReadRecord（设计如此，阅读历史可独立保留）
- **压缩包书同样有效**：archive 类型的 bookUrl 指向解压文件，文件被删时正确清理
- **不阻塞主线程**：在 `Dispatchers.IO` 上执行
- **触发频率由已有机制控制**：App 启动扫描有 5 分钟间隔防抖，不会每次启动都全量检查

### 关联功能的协同效果

```
发送端上传 → isOnBookShelf() 检测重复 → 拒绝上传（不覆盖）
     ↓
用户手动删旧文件 → AutoImportManager → cleanMissingBooks() 清理书架
     ↓
再次上传 → isOnBookShelf() = false → 上传成功
```

形成完整的"检测 → 清理 → 重新导入"闭环。

---

## 支持格式（确定保留）

| 格式 | 类型 | 说明 |
|------|------|------|
| TXT | 纯文本 | 基础必备 |
| EPUB | 电子书 | 开放标准，最主流 |
| PDF | 文档 | E-Ink 文档阅读刚需 |
| MOBI | 电子书 | E-Ink 用户与 Kindle 生态高度重叠 |
| AZW | 电子书 | Kindle 格式 |
| AZW3 | 电子书 | Kindle KF8 格式 |
| UMD | 电子书 | 国内手机电子书格式 |

**决策依据：** E-Ink 阅读器用户群和 Kindle 用户高度重叠，MOBI/AZW/AZW3 对目标用户不算小众。删格式支持 = 破坏兼容（用户书架里已有的书无法打开）。

---

## huajideshutiao/legado 参考分析（2026-07-18）

**仓库**: github.com/huajideshutiao/legado | 123 stars | 30 commits（2026-06-20 起）

### 定位差异

他们是**代码现代化 + UI 增强**版，不是精简版：
- 包名改为 `shutiao.reader`，数据库用 destructive migration（我们绝不能这样做）
- 新增 HomeTab/HomeSection 发现功能（与精简方向相反）
- 保留了 Audio/Video/TTS

**核心使用原则：借鉴实现方式，不复制架构。** 他们的最大价值是提供现代 Android UI 替换案例、组件精简案例、布局性能优化案例——不能直接合并。

### 可借鉴项（按优先级排序）

| 优先级 | 借鉴项 | 原因 | 注意事项 |
|--------|--------|------|----------|
| P0 | Widget 替换：RotateLoading→双分支、SmoothCheckBox→MaterialCheckBox、CircleImageView→ShapeableImageView | 减代码量 + E-Ink 适配直接受益，他们已验证可行 | RotateLoading 不能只换组件：E-Ink 分支必须用静态文字，LCD 分支用 CircularProgressIndicator |
| P1 | ThemeStore 死代码清理 | 应提前至 Phase 0.5：后续大量删功能/合并设置/调主题，ThemeStore 中的废弃代码会干扰 AI | 只删无引用资源和死代码，不改行为 |
| P2 | ConstraintLayout 替换嵌套 LinearLayout（书架布局） | 减少 layout pass，但 E-Ink 残影主要来自动画，布局优化收益有限，降级为 P2 | — |
| P2 | lib/prefs bug 修复（EditTextPreference isBottomBackground 未传递、IconListPreference 点击监听覆盖） | 有实际 bug，小改动高收益 | 需单独 commit，不要混入 UI 精简 |
| P3 | 属性委托简化 AppConfig | 好模式但纯代码现代化，不提升 E-Ink 体验 | Phase 7+ |

### 不适用项

| 项目 | 原因 |
|------|------|
| MOBI/AZW3 格式删除 | E-Ink 用户核心格式，删了反而伤用户 |
| 包名更改 + destructive migration | 数据库兼容是最高原则 |
| HomeTab/HomeSection 功能增加 | 与精简方向相反 |
| Rhino 改 Maven 官方版本 | 我们的 modules/rhino 是定制 fork，有针对书源规则引擎的 patch |
| TouchImageView 替换 PhotoView | 新增第三方依赖只是转移维护成本；PhotoView 能用且没 bug 就不换 |

