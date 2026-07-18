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

**决策依据：** E-Ink 阅读器用户群和 Kindle 用户高度重叠，MOBI/AZW/AZW3 对目标用户不算小众。删格式支持 = 破坏兼容（用户书架里已有的书无法打开）。漫画 CBZ/CBR 保留以支持 E-Ink 漫画阅读场景。

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

