Prompt 0（以后每次任务都放最前面）
以后所有 Prompt 前面都加这一段，不需要每次重新修改。
这是 Legado Max E-Ink 项目。

项目目标：

不是开发新的阅读软件。

而是在保持与原版 Legado Max 完全兼容的前提下进行功能精简。

最高原则：

1. 与原版数据库兼容。
2. 与 Backup 完全兼容。
3. 与 Restore 完全兼容。
4. 与 WebDAV 完全兼容。
5. 与书源、RSS、订阅、下载完全兼容。
6. 保持官方 UI 设计语言。

实现原则：

对于任何需求：

请先分析项目是否已经存在对应能力。

如果已经存在：

必须直接复用。

不得重新实现。

不得新增业务逻辑。

不得重复查询数据库。

不得新增 Repository。

不得新增统计逻辑。

只有源码不存在该能力时，

才能新增实现，并说明原因。
如果项目已经存在某项能力，请不要为了完成任务重新实现。

优先级如下：

① 复用已有业务逻辑
② 复用已有数据流
③ 复用已有 UI 组件
④ 复用已有导航
⑤ 最后才考虑新增代码

如果需要新增代码，请先说明为什么现有架构无法满足需求，再进行实现。

任何情况下，都不要为了快速完成任务而绕过现有架构。

每次修改：

分析

↓

修改

↓

编译

↓

修复编译错误

↓

停止

不要继续修改其它功能。


以后所有 Prompt 都默认带这一段。

Prompt 1：首页分析
请完整分析首页。

分析：

首页入口。

导航。

数据来源。

UI组成。

ViewModel。

Repository。

生命周期。

首页涉及哪些公共组件。

哪些代码属于共享能力。

为什么作者这样设计。

如果修改首页会影响哪些地方。

最后输出：

首页架构图。

首页数据流。

首页修改风险。

不要修改代码。

Prompt 2：首页修改
根据刚才的分析。

开始修改首页。

目标：

将首页改成固定阅读仪表盘。

不是重新设计首页。

而是在保持官方 Legado Max UI 风格的前提下，

重新组织首页布局。

首页需要显示：

① 最近阅读

② 阅读统计

③ 最近阅读书籍

④ WebDAV 快捷操作

实现要求：

请先确认项目是否已经存在这些数据来源。

如果存在：

直接复用已有 Repository、ViewModel、业务逻辑。

不得重新实现。

不得重新查询数据库。

不得新增统计逻辑。

不得新增 Repository。

所有按钮必须直接调用已有功能。

例如：

WebDAV 必须直接调用已有同步逻辑。

阅读统计必须直接调用已有统计能力。

最近阅读必须直接调用已有阅读记录。

修改完成以后：

编译。

修复编译错误。

停止。

Prompt 3：分析 AI、音乐、视频、TTS
请完整分析：

AI

音乐播放

视频播放

TTS

不要修改代码。

对于每一个功能：

请找出：

源码位置。

菜单入口。

设置入口。

页面。

ViewModel。

Repository。

Service。

数据库。

资源文件。

依赖关系。

同时判断：

项目是否存在公共组件被它们共享。

例如：

播放器。

Notification。

Service。

Settings。

Reader。

最后输出：

每个功能的删除风险。

推荐删除顺序。

不要修改代码。

Prompt 4：删除 AI
开始删除 AI 功能。

要求：

请先确认 AI 使用了哪些已有能力。

删除时：

不要影响：

阅读。

书架。

阅读统计。

RSS。

下载。

WebDAV。

Backup。

Restore。

数据库兼容。

删除原则：

如果某个公共组件同时被其它功能使用，

必须保留。

不要因为删除 AI 而删除共享代码。

修改完成以后：

编译。

修复编译错误。

停止。
Prompt 5：删除音乐
开始删除音乐播放。

要求与 AI 相同。

请先确认：

播放器是否属于共享能力。

如果视频或其它功能仍在使用，

不得删除共享播放器。

只删除音乐功能。

完成以后：

编译。

停止。
Prompt 6：删除视频
开始删除视频播放。

请确认：

视频是否复用了播放器。

是否影响阅读。

是否影响下载。

删除完成以后：

编译。

停止。
Prompt 7：删除 TTS
开始删除 TTS。

请确认：

阅读器是否仍引用 TTS。

如果阅读菜单存在 TTS 入口，

请一并移除。

保持阅读功能正常。

完成以后：

编译。

停止。
Prompt 8：最终测试
请执行完整检查。

确认以下功能正常：

阅读。

书架。

最近阅读。

阅读统计。

RSS。

书源。

订阅。

下载。

Backup。

Restore。

WebDAV。

首页。

设置。

导航。

如果发现因本次修改导致的问题，

请修复。

修复完成以后：

编译。

停止。

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
| Phase 3 | ✅ 可行 | 只有菜单入口 + 调用 `AutoImportManager.scan()`，纯 UI |
| Phase 4 | ⚠️ 需调整 | PopupWindow → 建议用 Compose popup；WiFi Server 应复用已有 HttpServer.kt |
| Phase 5 | ⚠️ 需调整 | 静态 HTML/JS 没问题，但应与现有 `modules/web/` 统一或明确分界 |
| Phase 6 | ⚠️ 需调整 | 不应新建独立 HttpServer。应扩展现有 HttpServer.kt 新增 upload 路由 |
| Phase 7 | ✅ 可行 | 标准测试流程 |

### 关键意见

整个计划中 **最有价值的部分是 Phase 2 的 AutoImportManager 统一入口**。它解决了以后每加一个导书方式都需要重复写胶水代码的问题。

**唯一需要重新考虑的架构决策是 WiFi 传书的 HTTP 服务器设计**。已有 HttpServer.kt（NanoHTTPD）+ BookController.addLocalBook() 已经实现了"接收上传 → 保存文件 → 导入书架"全流程，新建第二个服务器是重复造轮子。建议 Phase 0 深入分析现有 HttpServer.kt 的路由和生命周期，确认能否复用。
