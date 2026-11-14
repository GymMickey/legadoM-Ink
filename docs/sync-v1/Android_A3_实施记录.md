# Android Sync-v1 A3 实施记录

日期：2026-11-13
分支：`codex/sync-v1-android-a3-chapters`
基线：`94a8795b14b976949853842e5c637eb85d6b0436`

## 范围

实现 Android 章节目录只读导出、不可变快照发布、持久化恢复和设置页手动入口。没有修改 Sync-v1 协议、Schema、fixtures、Kindle 项目、原始 `Book` / `BookChapter` / `AppDatabase` schema、`bookshelf.json` 或旧备份/恢复格式。没有实现进度、阅读时间、ack、远端清理或自动同步。

## 实现结构

- `SyncV1ChaptersRepository` 负责来源校验、章节键和语义摘要、远端候选扫描/收养、revision 分配、不可变发布、重试和批量 catalog 汇总。
- `SyncV1Protocol` 校验章节 JSON 的严格字段、版本、UUID、RFC 3339 时间、标题标量序列、索引顺序和冻结的 `chapter_key`。
- `AppWebDavSyncV1Transport` 复用 A1 已验证配置快照和 `uuid_verified`；目录操作使用 `ensureSyncV1Directory`，对结果不明的 MKCOL 再用 PROPFIND 确认，未确认时不会继续文件 PUT。
- `ConfigViewModel` 只从当前书架及已缓存的章节 DAO 读取输入，不刷新书源、不打开书籍、不加载正文。A3 仓库先恢复 pending catalog、再恢复 pending chapters，之后才调用输入读取回调。
- 设置页新增“发布 Sync-v1 章节目录”入口，并显示来源数、发布/收养/未变化数、catalog 状态和安全错误码；不显示 URL、凭据或目录内容。

## 目录来源与章节快照

来源区分 `AVAILABLE`、`KNOWN_EMPTY`、`UNAVAILABLE`、`FAILED`。生产读取只有缓存章节非空且数量与 `Book.totalChapterNum` 一致时标记为 `AVAILABLE`；读取失败标记为 `FAILED`。当前 Legado 数据/API 没有“已成功加载且权威为空”的持久凭据，因此 DAO 空列表保持 `UNAVAILABLE`；只有明确传入的 `KNOWN_EMPTY` 才允许发布空数组。失败和未加载不会写空快照或推进 revision。

章节 JSON 只写协议冻结字段。标题按原始 Kotlin 字符串验证：非空、无孤立 surrogate、最多 512 个 Unicode code point；不 trim、不替换、不规范化。`chapter_key` 使用 A3 冻结算法计算并在发布前校验。真实扁平索引严格递增且不重编号。6000 章和最终 UTF-8 JSON 2 MiB 上限均在序列化后、上传前检查，超限整体拒绝。

revision 语义摘要只包含 `work_id` 和有序章节记录。目录语义不变时复用已确认的 snapshot、路径和原始字节；标题或索引等目录语义变化时分配 `max(当前根目录本地已确认 revision, 当前根目录远端有效 revision) + 1`。远端相同语义快照可以收养，独立同步库丢失后也能据此恢复。revision 多 snapshot、路径/内容不匹配或不可变对象字节冲突会拒绝处理。

发布前 SQLite intent 保存 service/root/work、revision、snapshot、路径、完整原始 JSON、摘要、PUT 次数和阶段。上传最多两次 PUT；超时/结果不明先 GET，重试复用原 intent 字节。只有 GET 回读的原始字节及 SHA-256 完全一致才标记确认。已确认对象意外缺失时，只尝试用原路径和原始字节修复，不分配新 revision。

## 数据库升级与恢复

独立同步 SQLite 从 A2 版本 2 显式升级到版本 3，只新增：

- `chapter_publication_intent`
- `chapter_publication_state`
- `chapter_catalog_publication_state`

升级不删除或重建 A1/A2 表及记录，无 destructive migration。章节状态按 `serviceIdentity + rootIdentity + work_id` 隔离；catalog dirty 也按服务和根目录隔离。章节确认事务同时更新独立 `WorkIdentity.toc_revision` 并标记 catalog dirty。数据库 helper 支持指定数据库名仅用于隔离迁移测试；生产默认仍为 `legadoM_ink_sync.db`。

每次发布按顺序恢复当前根目录的 pending catalog intent、pending chapters intent，再读取当前缓存 TOC。取消会继续传播并保留 intent。目录处理完毕后最多创建一个本轮汇总 catalog intent；它只使用当前根目录已经确认的章节 revision，失败或未确认的作品不产生悬空引用。部分作品失败时，其他作品仍可完成并返回 `partial_success`。ViewModel 有运行任务保护，仓库另用进程级 Mutex 防止 A3 并发分配 revision。

## 验证结果

使用 `F:\Android\Sdk`、外部 Gradle home `F:\Leagdo-mickey\.gradle` 和同盘临时目录完成验证：

- A3、A1 注册、A2 catalog 与 WorkIdentity 定向 JVM 测试：**69 项通过，0 失败**（A3 27、注册 21、catalog 13、identity 8）。
- A2→A3 SQLite instrumentation 升级测试：**模拟器 1/1 通过**；验证 A1/A2 注册、publisher ID、WorkIdentity/WorkAlias、catalog 状态及既有 intent 保留，并成功写入 A3 intent/确认状态。
- WebDAV MockServer instrumentation：**模拟器 4/4 通过**，包括目录创建结果不明后的 PROPFIND 确认。未连接真机。
- `:app:assembleAppLegacyDebug :app:assembleAppLegacyDebugAndroidTest`：**成功**。
- `git diff --check`：通过。
- Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta33_legacy.apk`，SHA-256 `99D302163EF67A1BCF348155A165C3F932651B1E9D1AB3904EDDF19F1607BE94`。
- Debug 包名：`io.legado.app.debug`；证书 SHA-256 与固定调试证书一致：`f5544859c5144b7e3b10c9e47837bedcd73ed4f806b32279cf96a12cb0501176`。
- AndroidTest APK：`app/build/outputs/apk/androidTest/appLegacy/debug/app-appLegacy-debug-androidTest.apk`，SHA-256 `C45B4F8495A30A07BD8083D6B2B5417F6A36ECDC3635AFE194ABCA46BA26CA1F`。

没有访问真实 WebDAV，也没有进行真机发布联调。instrumentation 测试只在 `Medium_Phone` 模拟器运行。未提交、未推送。

## 目录层级 HTTP 409 修复（2026-11-14）

真实 WebDAV 首次发布时，`android/` 下已有 `catalog/` 和 `device-state/`，但尚无 `chapters/`。旧流程直接 MKCOL `android/chapters/<work_id>/`；该服务不递归创建父目录，故返回 HTTP 409，且没有发布章节快照。

A3 现在先确保并验证共享 `android/chapters/` collection，再处理 pending chapter intent 或当前书籍；每个 work 目录都在已验证父目录下单独确保。pending intent 继续使用持久化的原路径、revision、snapshot_id、原始 JSON 字节和摘要。409 仍只有在后续 PROPFIND 明确验证目标为 collection 时才能接受。

新增严格层级目录回归测试：替身不会递归创建目录，缺少直接父目录时 MKCOL 孙目录返回 409；测试验证先创建 `chapters/`、再创建 `<work_id>/`，并成功发布、读取并核对章节快照路径与内容。A3 章节仓库定向 JVM 测试 **28/28 通过**（原 27 项加新增 1 项）。`:app:assembleAppLegacyDebug` **成功**。

本次 Debug APK：`app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta33_legacy.apk`，包名 `io.legado.app.debug`，大小 50,713,706 字节，SHA-256 `D299D6517E0CD86108EE8D17158CE4F81D4289ADC0437933DD07AC4BC39EBE9C`。本段测试环境未访问真实 WebDAV；真实服务验收结果见下节。未提交、未推送。

## 真实 WebDAV 验收（2026-11-14）

- 3 个 `AVAILABLE` 作品均创建独立 `work_id` 目录；每个目录仅有一个 `1-<snapshot_id>.json`，路径与快照内容一致。
- 首次章节目录发布只将 catalog 从 revision 3 推进至 revision 4。
- 重复发布结果为新发布 0、未变化 3、失败 0、错误码为空；没有生成 catalog revision 5 或额外 chapters 快照。
- 182 个 `UNAVAILABLE` 作品缺少完整本地章节目录。它们未被当作空目录发布，返回 `partial_success` 符合安全策略。
- 首次 HTTP 409 已确认是 `android/chapters/` 父目录缺失导致。按父目录、作品目录顺序创建后，真实 WebDAV 发布及幂等复测通过。
- “目录未发布”状态文案可能让人误解，可后续单独优化，不影响 A3 验收。

Android A3 章节目录 exporter 的真实 WebDAV 验收通过；本阶段不包含 Android A4、进度/阅读时间导入、ack、清理或 Kindle K2。

## Catalog 完整性修正（2026-11-14）

更正此前对 revision 4 的完整性描述：Kindle 严格解析到的作品总数实际为 **3**，且这 3 项 `toc_revision` 均为 1；此前把 revision 4 描述为包含 185 项不准确。该 revision 只反映当时已有章节快照的 3 个作品。revision 4 保持不可变，本次实现没有访问或修改真实 WebDAV。

根因是 A3 只在 `catalogDirty` 为 true 时运行 catalog 发布，并把 `revisions.keys` 作为作品过滤条件，导致没有章节快照的当前书架作品被遗漏。修正后每次手动 A3 批次结束都会进行一次完整 catalog 语义对账，即使 dirty 已清除也会执行；catalog 输入为当前书架上成功解析的全部作品身份，不包含历史孤立身份。章节 revision 只取当前 `serviceIdentity + rootIdentity` 的已确认映射；映射缺少的当前作品写 0，只有该映射中的正 revision 才能写入 catalog。

pending catalog intent 仍先于章节处理和书架读取恢复，使用原路径、revision、snapshot 和持久化原始字节；之后再对完整当前书架做一次对账。新测试覆盖 185 项书架、唯一 work_id、3 项 revision 1 / 182 项 revision 0、revision 0 无章节文件要求、跨根目录 revision 隔离、dirty=false 时从不完整 catalog 生成 revision 5、重复运行不生成 revision 6/额外章节快照，以及 pending intent 原字节先恢复后再对账。

定向验证：A3 chapters/catalog JVM 测试 **31/31 通过**，A2 catalog 回归 **13/13 通过**；`:app:assembleAppLegacyDebug` 成功。Debug APK 为 `app/build/outputs/apk/appLegacy/debug/LegadoM-Ink_兼容版_3.26-beta33_legacy.apk`，包名 `io.legado.app.debug`，SHA-256 `1ADF29661013F2FE8DF899F9F5D28EC6FFBA462869ADAED89797BE3D91248727`。

本次实现、定向测试和 Debug 构建阶段未访问真实 WebDAV；本记录现补充后续真实设备验收结果。

## Catalog 完整性真实 WebDAV 验收（2026-11-15）

- Android 发布了不可变 catalog revision 5，包含 **185 个唯一 `work_id`**：3 项 `toc_revision=1`，182 项 `toc_revision=0`。revision 4 保持不变。
- 远端仍只有原来的 3 个 chapters 作品目录及快照，没有重发、覆盖或新增章节快照。
- Kindle 在线解析显示 revision 5、作品数 185、可用目录 3。章节缓存结果为新增 0、未变化 3、失败 0。
- 当前目录缓存为 3，旧目录缓存为 0；离线缓存结果一致。
- 重复发布没有生成 catalog revision 6，章节快照数量也未增加。

以上完成 A3 catalog 完整性真实 WebDAV 验收。本轮只记录既有验收，不重新构建或运行测试；未提交、未推送。

## 已知限制与后续边界

- 由于旧数据没有权威空目录回执，生产路径目前不能自动产生 `KNOWN_EMPTY`；空 DAO 结果安全地显示为 `UNAVAILABLE`。
- 目前设置页手动发布章节目录，不包含任何章节正文或缓存内容。
- `KNOWN_EMPTY` 仍需权威空目录来源；真实 WebDAV 的 A3 章节发布和幂等复测已通过。
- 本记录止于 A3 章节目录 exporter；不包含 Android A4、进度/阅读时间导入、ack、清理或 Kindle K2。
